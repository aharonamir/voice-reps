// Real-weight host regression: default and persisted-speaker synthesis after a fresh load.
// This includes the same engine implementation compiled into the Android JNI library.
#define main zonos3_cli_main
#include "../native/jni/zonos3_jni.cpp"
#undef main
#include "ggml-cpu.h"
#include <cmath>
#include <fstream>
#include <stdexcept>

static void require(bool ok, const char *message) {
    if (!ok) throw std::runtime_error(message);
}
static long load(const std::string &dir) {
    long h = zonos3_engine_init((dir + "/backbone-ptq.gguf").c_str(),
                               (dir + "/zac-keep2.gguf").c_str(), (dir + "/steer.gguf").c_str());
    require(h && !strlen(zonos3_engine_last_error(h)), "engine load failed");
    auto *e = (zonos3_engine *)h;
    ggml_backend_cpu_set_n_threads(e->model.backend, 1);
    ggml_backend_cpu_set_n_threads(e->decoder.backend, 1);
    return h;
}
static void audible(const std::vector<float> &pcm, const char *label) {
    require(!pcm.empty(), "no audio");
    double peak = 0, rms = 0;
    for (float v : pcm) {
        require(std::isfinite(v), "invalid PCM");
        peak = std::max(peak, (double)std::abs(v)); rms += v * v;
    }
    require(peak > 1e-5, "silent PCM");
    fprintf(stderr, "PASS %s: %zu samples, peak %.6f RMS %.6f\n", label, pcm.size(), peak, std::sqrt(rms / pcm.size()));
}
static std::vector<float> stream(long h) {
    std::vector<float> pcm;
    require(zonos3_engine_synthesize_stream(h, "Hello. Welcome back.", 1, 64, "",
        [&](const float *p, size_t n) { pcm.insert(pcm.end(), p, p + n); return true; }), zonos3_engine_last_error(h));
    audible(pcm, "stream");
    return pcm;
}
int main(int argc, char **argv) {
    if (argc != 3) { fprintf(stderr, "usage: native_reentry <models-dir> <output-dir>\n"); return 2; }
    try {
        const std::string dir = argv[1], out = argv[2];
        long h = load(dir);
        auto initial = stream(h);
        std::vector<float> blocking;
        require(zonos3_engine_synthesize(h, "Hello. Welcome back.", 1, 64, "", blocking), "blocking synthesis failed");
        audible(blocking, "WAV path");
        require(initial.size() == blocking.size(), "stream/WAV duration mismatch");
        wav_pcm16_write((out + "/stream.wav").c_str(), initial, 44100);
        wav_pcm16_write((out + "/blocking.wav").c_str(), blocking, 44100);
        long spk = zonos3_spk_encoder_init((dir + "/spk-encoder.gguf").c_str());
        require(spk != 0, "speaker encoder failed");
        std::vector<float> emb;
        require(zonos3_spk_embed(spk, initial.data(), (int)initial.size(), 44100, emb), "speaker embedding failed");
        require(emb.size() == 192, "embedding size mismatch");
        std::ofstream(out + "/test.emb", std::ios::binary).write((char *)emb.data(), emb.size() * sizeof(float));
        zonos3_spk_encoder_free(spk);
        zonos3_engine_free(h); // model memory and all engine state genuinely gone
        emb.assign(192, 0);
        std::ifstream(out + "/test.emb", std::ios::binary).read((char *)emb.data(), emb.size() * sizeof(float));
        h = load(dir);
        zonos3_engine_set_speaker_embedding(h, emb.data(), (int)emb.size());
        auto reloaded = stream(h);
        wav_pcm16_write((out + "/reloaded-voice.wav").c_str(), reloaded, 44100);
        auto repeat = stream(h);
        require(repeat.size() == reloaded.size(), "repeat duration mismatch");
        zonos3_engine_free(h);
        fprintf(stderr, "PASS saved embedding -> fresh engine -> repeat speak; stream/WAV length parity\n");
        return 0;
    } catch (const std::exception &e) {
        fprintf(stderr, "FAIL: %s\n", e.what()); return 1;
    }
}
