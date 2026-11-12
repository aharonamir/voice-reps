// Steering regression: repeated Speak on one engine with expression controls and a speaker embedding.
#define main zonos3_cli_main
#include "../native/jni/zonos3_jni.cpp"
#undef main
#include "ggml-cpu.h"
#include <chrono>
#include <cmath>
#include <fstream>
static std::vector<float> run(long h, const char *ctl, const char *label) {
    auto t0 = std::chrono::steady_clock::now();
    std::vector<float> pcm; bool nan = false;
    bool ok = zonos3_engine_synthesize_stream(h, (getenv("TXT") ? getenv("TXT") : "Hello. Welcome back."), getenv("SEED") ? atoi(getenv("SEED")) : 7, getenv("MF") ? atoi(getenv("MF")) : 64, ctl,
        [&](const float *p, size_t n) { pcm.insert(pcm.end(), p, p + n); return true; });
    double peak = 0; for (float v : pcm) { if (!std::isfinite(v)) nan = true; peak = std::max(peak, (double)std::abs(v)); }
    fprintf(stderr, "%s [%s]: ok=%d n=%zu peak=%.6f nan=%d err='%s' %llds\n", label, ctl, ok, pcm.size(), peak, nan,
        zonos3_engine_last_error(h), (long long)std::chrono::duration_cast<std::chrono::seconds>(std::chrono::steady_clock::now() - t0).count());
    return pcm;
}
int main(int argc, char **argv) {
    std::string dir = argv[1];
    long h = zonos3_engine_init((dir + "/backbone-ptq.gguf").c_str(), (dir + "/zac-keep2.gguf").c_str(), (dir + "/steer.gguf").c_str());
    auto *e = (zonos3_engine *)h;
    ggml_backend_cpu_set_n_threads(e->model.backend, 1);
    ggml_backend_cpu_set_n_threads(e->decoder.backend, 1);
    const char *ex = argc > 2 ? argv[2] : "emotions.ecstatic=2;mood.arousal=1;expressive.expressive=6";
    if (getenv("NOSPK") == nullptr) {}
    for (int sd = 1; sd <= 2; sd++) { setenv("SEED", std::to_string(sd * 1111).c_str(), 1); run(h, ex, "excited"); }
    return 0;
}
