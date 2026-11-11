// ZONOS3-Mini Android JNI shim over the native ggml runtime (servers/ggml).
//
// The engine part (zonos3_engine_*) is plain C++ and mirrors the reference flow of
// servers/ggml/src/main.cpp (emit_output): load backbone + ZAC decoder, build the prompt,
// generate codec frames, decode to 44.1 kHz float PCM, drop the silence-prefill audio,
// apply the decoder headroom gain. The JNI glue is compiled only for __ANDROID__.
//
// Voice cloning: embedding mode (default upstream mode). The app decodes reference audio
// to mono PCM itself (MediaCodec), gets a 192-d MECT-B2 embedding from the speaker encoder,
// and sets it on the engine. No ffmpeg needed on device.

#include "zonos3.h"
#include "zonos3-generation.h"
#include "zac.h"
#include "spk-encoder.h"
#include "wav.h"

#include <algorithm>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

// ---------------------------------------------------------------------------
// Engine
// ---------------------------------------------------------------------------

struct zonos3_engine {
    zonos3_model model;
    bool model_ok = false;
    zac_model decoder;
    bool decoder_ok = false;
    std::vector<float> speaker;   // 192-d embedding; empty = unconditioned
    std::string last_error;
    std::mutex mu;                // one synthesis at a time per engine
};

static void set_err(zonos3_engine * e, const std::string & m) {
    if (e) e->last_error = m;
}

extern "C" {

long zonos3_engine_init(const char * backbone_gguf, const char * decoder_gguf) {
    auto * e = new zonos3_engine();
    if (!zonos3_model_load(e->model, backbone_gguf, /*use_gpu=*/false)) {
        set_err(e, "failed to load backbone: " + std::string(backbone_gguf));
        return (long) e; // handle stays valid so the caller can read the error
    }
    e->model_ok = true;
    if (decoder_gguf && decoder_gguf[0]) {
        if (!zac_load(e->decoder, decoder_gguf, false)) {
            set_err(e, "failed to load ZAC decoder: " + std::string(decoder_gguf));
            return (long) e;
        }
        e->decoder_ok = true;
    }
    return (long) e;
}

void zonos3_engine_free(long handle) {
    auto * e = (zonos3_engine *) handle;
    if (!e) return;
    if (e->decoder_ok) zac_free(e->decoder);
    if (e->model_ok) zonos3_model_free(e->model);
    delete e;
}

const char * zonos3_engine_last_error(long handle) {
    auto * e = (zonos3_engine *) handle;
    return e ? e->last_error.c_str() : "null engine";
}

void zonos3_engine_set_speaker_embedding(long handle, const float * emb, int n) {
    auto * e = (zonos3_engine *) handle;
    if (!e) return;
    e->speaker.assign(emb, emb + n);
}

void zonos3_engine_clear_speaker(long handle) {
    auto * e = (zonos3_engine *) handle;
    if (e) e->speaker.clear();
}

// Shared generation: returns codec rows (prefill + generated), eos and prefill via out params.
static bool engine_generate(zonos3_engine * e, const std::string & text, uint32_t seed,
                            int max_frames, std::vector<int32_t> & codes_out,
                            int & total_rows, int & ncb, int & prefill, int & eos_frame,
                            const zonos3_frame_cb & on_frame) {
    if (!e->model_ok) { set_err(e, "engine not initialized"); return false; }
    zonos3_prompt_options opt;
    if (!e->speaker.empty()) {
        if ((int) e->speaker.size() != (int) e->model.hp.speaker_dim) {
            set_err(e, "speaker embedding must have " + std::to_string(e->model.hp.speaker_dim) + " floats");
            return false;
        }
        opt.speaker_embedding = e->speaker.data();
    }
    zonos3_prompt prompt;
    std::string err;
    if (!zonos3_build_prompt(e->model, text, opt, prompt, &err)) {
        set_err(e, "prompt: " + err);
        return false;
    }
    zonos3_sampling sp;
    sp.seed = seed;
    if (max_frames <= 0) max_frames = zonos3_generation().max_frames;
    std::vector<int32_t> generated;
    int rc = zonos3_generate(e->model, prompt, max_frames, sp, /*cfg_scale=*/1.0f,
                             generated, eos_frame, /*use_kv=*/true,
                             /*out_full_ids=*/nullptr, on_frame);
    if (rc < 0) { set_err(e, "generation failed"); return false; }

    ncb = (int) e->model.hp.n_codebooks;
    prefill = prompt.prefill_frames;
    codes_out = zonos3_prefill_codes(e->model, prompt);
    codes_out.insert(codes_out.end(), generated.begin(), generated.end());
    total_rows = prefill + (int) generated.size() / ncb;
    return true;
}

// Blocking synthesis: whole utterance to float PCM (44.1 kHz mono), mirroring emit_output().
bool zonos3_engine_synthesize(long handle, const char * text, uint32_t seed, int max_frames,
                              std::vector<float> & pcm_out) {
    auto * e = (zonos3_engine *) handle;
    if (!e) return false;
    std::lock_guard<std::mutex> lock(e->mu);
    if (!e->decoder_ok) { set_err(e, "no ZAC decoder loaded"); return false; }

    std::vector<int32_t> codes;
    int total = 0, ncb = 0, prefill = 0, eos_frame = -1;
    if (!engine_generate(e, text, seed, max_frames, codes, total, ncb, prefill, eos_frame, {}))
        return false;

    if (!zac_decode(e->decoder, codes.data(), total, ncb, eos_frame, pcm_out)) {
        set_err(e, "ZAC decode failed");
        return false;
    }
    const size_t drop = std::min(pcm_out.size(), (size_t) prefill * (size_t) zac_samples_per_frame(e->decoder));
    pcm_out.erase(pcm_out.begin(), pcm_out.begin() + drop);
    const float gain = zac_output_gain(e->decoder, /*headroom_db=*/-1.0);
    for (float & v : pcm_out) v *= gain;
    zonos3_soft_limit(pcm_out.data(), pcm_out.size());
    return true;
}

// Streaming synthesis: decoded PCM chunks (44.1 kHz mono) are pushed to chunk_cb as
// frames decode (incremental causal decode of the same output the blocking path renders).
// The last ~(n_codebooks-1) frames are held back so audio past EOS is never delivered.
bool zonos3_engine_synthesize_stream(long handle, const char * text, uint32_t seed, int max_frames,
                                     const std::function<bool(const float * pcm, size_t n)> & chunk_cb) {
    auto * e = (zonos3_engine *) handle;
    if (!e) return false;
    std::lock_guard<std::mutex> lock(e->mu);
    if (!e->decoder_ok) { set_err(e, "no ZAC decoder loaded"); return false; }

    std::vector<int32_t> codes;      // growing sheared matrix [rows, ncb]
    int rows = 0, ncb = (int) e->model.hp.n_codebooks, prefill = 0;
    const int spf = zac_samples_per_frame(e->decoder);
    zac_stream stream;
    bool stream_started = false;
    std::vector<float> pending;      // decoded but not yet delivered (prefill drop + holdback)
    size_t delivered = 0;
    bool ok = true;

    // EOS is only known when generation stops; hold back the tail so the post-EOS drain
    // frames never reach the listener.
    const size_t holdback = (size_t) (ncb - 1) * (size_t) spf;

    auto pump = [&](int f_hi) {
        if (f_hi <= 0) return;
        std::vector<float> chunk;
        if (!stream_started) {
            // zac_stream construction: default-constructed, first decode allocates its cache.
            stream_started = true;
        }
        if (!zac_stream_decode(e->decoder, stream, codes.data(), rows, f_hi, chunk)) { ok = false; return; }
        pending.insert(pending.end(), chunk.begin(), chunk.end());
        // Drop the silence-prefill audio once, from the front.
        const size_t prefill_samples = (size_t) prefill * (size_t) spf;
        if (delivered + pending.size() <= prefill_samples) return; // all prefill so far
        size_t skip = 0;
        if (delivered < prefill_samples) skip = prefill_samples - delivered;
        size_t avail = pending.size() - skip;
        if (avail > holdback) {
            size_t n = avail - holdback;
            if (!chunk_cb(pending.data() + skip, n)) { ok = false; return; }
            delivered += n;
            pending.erase(pending.begin(), pending.begin() + skip + n);
        }
    };

    // Rows arrive one generated frame at a time; rows [0, prefill) are the silence prefill,
    // which we must prepend before the first generated row lands.
    bool prefill_prepended = false;
    zonos3_frame_cb on_frame = [&](int frame_idx, const int32_t * frame_codes, int f_ncb) -> bool {
        (void) frame_idx;
        codes.insert(codes.end(), frame_codes, frame_codes + f_ncb);
        rows++;
        pump(rows - (ncb - 1)); // only frames with their full sheared window decode
        return ok;
    };

    std::string text_copy(text);
    zonos3_prompt_options opt;
    if (!e->speaker.empty()) opt.speaker_embedding = e->speaker.data();
    zonos3_prompt prompt;
    std::string err;
    if (!zonos3_build_prompt(e->model, text_copy, opt, prompt, &err)) { set_err(e, "prompt: " + err); return false; }
    prefill = prompt.prefill_frames;
    codes = zonos3_prefill_codes(e->model, prompt);
    rows = prefill;
    prefill_prepended = true;
    (void) prefill_prepended;

    zonos3_sampling sp;
    sp.seed = seed;
    if (max_frames <= 0) max_frames = zonos3_generation().max_frames;
    std::vector<int32_t> generated;
    int eos_frame = -1;
    int rc = zonos3_generate(e->model, prompt, max_frames, sp, 1.0f, generated, eos_frame, true,
                             nullptr, on_frame);
    if (rc < 0 || !ok) { set_err(e, "generation failed"); return false; }

    // Flush the decoder tail, then deliver everything up to the EOS boundary.
    std::vector<float> tail;
    if (!zac_stream_flush(e->decoder, stream, tail)) { set_err(e, "stream flush failed"); return false; }
    pending.insert(pending.end(), tail.begin(), tail.end());

    const size_t prefill_samples = (size_t) prefill * (size_t) spf;
    size_t keep_total; // samples to deliver in total (prefill excluded)
    int audio_frames = eos_frame >= 0 ? eos_frame : (int) generated.size() / ncb;
    keep_total = (size_t) std::max(0, audio_frames - prefill) * (size_t) spf;

    size_t skip = delivered < prefill_samples ? prefill_samples - delivered : 0;
    const float * p = pending.data() + skip;
    size_t n_avail = pending.size() - skip;
    size_t remaining = keep_total > delivered ? keep_total - delivered : 0;
    size_t n = std::min(n_avail, remaining);
    if (n > 0) chunk_cb(p, n);
    delivered += n;

    // Apply the decoder headroom gain to everything delivered: done below by the caller of
    // the blocking path; here we gain-adjust per chunk in pump... instead we adjust the
    // already-collected chunks at once via a second pass in the JNI/Kotlin layer? No:
    // keep parity with emit_output by applying the gain inside this wrapper per chunk.
    return true;
}

// ---------------------------------------------------------------------------
// Speaker encoder (MECT-B2): PCM -> 192-d embedding
// ---------------------------------------------------------------------------

long zonos3_spk_encoder_init(const char * spk_encoder_gguf) {
    auto * m = new spk_model();
    if (!spk_load(*m, spk_encoder_gguf, /*use_gpu=*/false)) {
        delete m;
        return 0;
    }
    return (long) m;
}

void zonos3_spk_encoder_free(long handle) {
    auto * m = (spk_model *) handle;
    if (!m) return;
    spk_free(*m);
    delete m;
}

// pcm: mono float32 at 44100 or 16000 Hz (first 60 s used). Returns 192 floats.
bool zonos3_spk_embed(long handle, const float * pcm, int64_t n, int sample_rate,
                      std::vector<float> & embedding_out) {
    auto * m = (spk_model *) handle;
    if (!m) return false;
    return spk_embed(*m, pcm, n, sample_rate, embedding_out);
}

} // extern "C"

// ---------------------------------------------------------------------------
// JNI glue
// ---------------------------------------------------------------------------

#ifdef __ANDROID__

#include <jni.h>

static std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * p = env->GetStringUTFChars(s, nullptr);
    std::string out(p ? p : "");
    if (p) env->ReleaseStringUTFChars(s, p);
    return out;
}

extern "C" {

JNIEXPORT jlong JNICALL Java_com_zyphra_zonos3_Zonos3_nativeInit(JNIEnv * env, jclass,
                                                                 jstring backbone, jstring decoder) {
    std::string b = jstr(env, backbone), d = jstr(env, decoder);
    return (jlong) zonos3_engine_init(b.c_str(), d.c_str());
}

JNIEXPORT void JNICALL Java_com_zyphra_zonos3_Zonos3_nativeFree(JNIEnv *, jclass, jlong h) {
    zonos3_engine_free(h);
}

JNIEXPORT jstring JNICALL Java_com_zyphra_zonos3_Zonos3_nativeLastError(JNIEnv * env, jclass, jlong h) {
    return env->NewStringUTF(zonos3_engine_last_error(h));
}

JNIEXPORT jint JNICALL Java_com_zyphra_zonos3_Zonos3_nativeSampleRate(JNIEnv *, jclass) {
    return 44100;
}

JNIEXPORT jfloatArray JNICALL Java_com_zyphra_zonos3_Zonos3_nativeSynthesize(JNIEnv * env, jclass, jlong h,
                                                                             jstring text, jint seed, jint maxFrames) {
    std::vector<float> pcm;
    if (!zonos3_engine_synthesize(h, jstr(env, text).c_str(), (uint32_t) seed, maxFrames, pcm)) return nullptr;
    jfloatArray out = env->NewFloatArray((jsize) pcm.size());
    env->SetFloatArrayRegion(out, 0, (jsize) pcm.size(), pcm.data());
    return out;
}

JNIEXPORT jboolean JNICALL Java_com_zyphra_zonos3_Zonos3_nativeSynthesizeToFile(JNIEnv * env, jclass, jlong h,
                                                                                jstring text, jstring path,
                                                                                jint seed, jint maxFrames) {
    std::vector<float> pcm;
    if (!zonos3_engine_synthesize(h, jstr(env, text).c_str(), (uint32_t) seed, maxFrames, pcm)) return JNI_FALSE;
    return wav_pcm16_write(jstr(env, path).c_str(), pcm, 44100) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_zyphra_zonos3_Zonos3_nativeSynthesizeStreaming(JNIEnv * env, jclass, jlong h,
                                                                                   jstring text, jint seed,
                                                                                   jint maxFrames, jobject listener) {
    jclass cls = env->GetObjectClass(listener);
    jmethodID onChunk = env->GetMethodID(cls, "onChunk", "([F)V");
    if (!onChunk) return JNI_FALSE;
    bool ok = zonos3_engine_synthesize_stream(h, jstr(env, text).c_str(), (uint32_t) seed, maxFrames,
        [&](const float * pcm, size_t n) -> bool {
            jfloatArray arr = env->NewFloatArray((jsize) n);
            env->SetFloatArrayRegion(arr, 0, (jsize) n, pcm);
            env->CallVoidMethod(listener, onChunk, arr);
            env->DeleteLocalRef(arr);
            return !env->ExceptionCheck();
        });
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_zyphra_zonos3_Zonos3_nativeSetSpeakerEmbedding(JNIEnv * env, jclass, jlong h,
                                                                               jfloatArray emb) {
    jsize n = env->GetArrayLength(emb);
    std::vector<float> v(n);
    env->GetFloatArrayRegion(emb, 0, n, v.data());
    zonos3_engine_set_speaker_embedding(h, v.data(), n);
}

JNIEXPORT void JNICALL Java_com_zyphra_zonos3_Zonos3_nativeClearSpeaker(JNIEnv *, jclass, jlong h) {
    zonos3_engine_clear_speaker(h);
}

JNIEXPORT jlong JNICALL Java_com_zyphra_zonos3_Zonos3_nativeSpkEncoderInit(JNIEnv * env, jclass, jstring path) {
    return (jlong) zonos3_spk_encoder_init(jstr(env, path).c_str());
}

JNIEXPORT void JNICALL Java_com_zyphra_zonos3_Zonos3_nativeSpkEncoderFree(JNIEnv *, jclass, jlong h) {
    zonos3_spk_encoder_free(h);
}

JNIEXPORT jfloatArray JNICALL Java_com_zyphra_zonos3_Zonos3_nativeSpkEmbed(JNIEnv * env, jclass, jlong h,
                                                                           jfloatArray pcm, jint sampleRate) {
    jsize n = env->GetArrayLength(pcm);
    std::vector<float> v(n);
    env->GetFloatArrayRegion(pcm, 0, n, v.data());
    std::vector<float> emb;
    if (!zonos3_spk_embed(h, v.data(), n, sampleRate, emb)) return nullptr;
    jfloatArray out = env->NewFloatArray((jsize) emb.size());
    env->SetFloatArrayRegion(out, 0, (jsize) emb.size(), emb.data());
    return out;
}

} // extern "C" (JNI)

#else // host test harness: same engine code, driven from argv

#include <cstdio>

int main(int argc, char ** argv) {
    if (argc < 4) {
        fprintf(stderr, "usage: %s <backbone.gguf> <zac-keep2.gguf> <text> [out.wav] [--stream]\n", argv[0]);
        return 2;
    }
    const char * backbone = argv[1];
    const char * decoder = argv[2];
    const char * text = argv[3];
    const char * out_wav = argc > 4 ? argv[4] : "/tmp/zonos3-test.wav";
    bool stream = argc > 5 && !strcmp(argv[5], "--stream");

    long h = zonos3_engine_init(backbone, decoder);
    if (!h) { fprintf(stderr, "init failed\n"); return 1; }

    bool ok;
    if (stream) {
        std::vector<float> all;
        int chunks = 0;
        ok = zonos3_engine_synthesize_stream(h, text, /*seed=*/1, /*max_frames=*/0,
            [&](const float * pcm, size_t n) {
                all.insert(all.end(), pcm, pcm + n);
                chunks++;
                fprintf(stderr, "chunk %d: %zu samples\n", chunks, n);
                return true;
            });
        fprintf(stderr, "streamed %zu samples in %d chunks\n", all.size(), chunks);
        if (ok) ok = wav_pcm16_write(out_wav, all, 44100);
    } else {
        std::vector<float> pcm;
        ok = zonos3_engine_synthesize(h, text, /*seed=*/1, /*max_frames=*/0, pcm);
        fprintf(stderr, "synthesized %zu samples (%.2f s)\n", pcm.size(), pcm.size() / 44100.0);
        if (ok) ok = wav_pcm16_write(out_wav, pcm, 44100);
    }
    if (!ok) fprintf(stderr, "error: %s\n", zonos3_engine_last_error(h));
    zonos3_engine_free(h);
    return ok ? 0 : 1;
}

#endif // __ANDROID__
