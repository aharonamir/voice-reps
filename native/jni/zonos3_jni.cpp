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
#include "zonos3-steer.h"
#include "zac.h"
#include "spk-encoder.h"
#include "wav.h"

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <sys/stat.h>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
#define ZLOGI(...) __android_log_print(ANDROID_LOG_INFO, "Zonos3JNI", __VA_ARGS__)
#define ZLOGE(...) __android_log_print(ANDROID_LOG_ERROR, "Zonos3JNI", __VA_ARGS__)
#else
#define ZLOGI(...) do { fprintf(stderr, "zonos3jni: "); fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#define ZLOGE(...) ZLOGI(__VA_ARGS__)
#endif

static bool file_exists_with_size(const char * path, long & size_out) {
    struct stat st;
    if (!path || stat(path, &st) != 0) return false;
    size_out = (long) st.st_size;
    return true;
}

// ---------------------------------------------------------------------------
// Engine
// ---------------------------------------------------------------------------

struct zonos3_engine;
static void set_err(zonos3_engine * e, const std::string & m);

struct zonos3_engine {
    zonos3_model model;
    bool model_ok = false;
    zac_model decoder;
    bool decoder_ok = false;
    zonos3_steer_bundle steer;    // expression/emotion directions (steer.gguf)
    bool steer_ok = false;
    std::vector<float> speaker;   // 192-d embedding; empty = unconditioned
    std::string last_error;
    std::mutex mu;                // one synthesis at a time per engine
};

// "id=dose;id=dose" (e.g. "emotions.happy=2;delivery.tempo=-1"), ids as in /v2/controls.
static bool parse_controls(const std::string & s, std::map<std::string, double> & out) {
    size_t i = 0;
    while (i < s.size()) {
        size_t semi = s.find(';', i);
        std::string kv = s.substr(i, semi == std::string::npos ? std::string::npos : semi - i);
        i = semi == std::string::npos ? s.size() : semi + 1;
        if (kv.empty()) continue;
        size_t eq = kv.find('=');
        if (eq == std::string::npos || eq == 0) return false;
        char * end = nullptr;
        double v = strtod(kv.c_str() + eq + 1, &end);
        if (!end || *end) return false;
        out[kv.substr(0, eq)] = v;
    }
    return true;
}

// Resolves expression controls for one synthesis: hidden-space directions (emotions, mood,
// delivery, expressive) become a steering delta for generation; raw-space directions move a
// COPY of the speaker embedding (the stored embedding is never modified). steering must
// outlive the zonos3_generate call that consumes *steering_out.
static bool engine_apply_controls(zonos3_engine * e, const std::string & controls_str,
                                  std::vector<float> & spk_inout, zonos3_steering & steering,
                                  const zonos3_steering ** steering_out) {
    *steering_out = nullptr;
    if (controls_str.empty()) return true;
    if (!e->steer_ok) {
        set_err(e, "expression controls unavailable: steer.gguf is not loaded (re-download models)");
        return false;
    }
    std::map<std::string, double> controls;
    if (!parse_controls(controls_str, controls)) {
        set_err(e, "malformed controls string: " + controls_str);
        return false;
    }
    if (controls.empty()) return true;
    std::string err;
    if (!zonos3_steer_resolve(e->steer, controls, /*spans=*/{}, /*text_chars=*/0, /*frames_per_char=*/1.0,
                              spk_inout.empty() ? nullptr : &spk_inout, steering, err)) {
        set_err(e, "steering: " + err);
        return false;
    }
    *steering_out = &steering;
    ZLOGI("controls applied: %s", controls_str.c_str());
    return true;
}

static void set_err(zonos3_engine * e, const std::string & m) {
    if (e) e->last_error = m;
}

extern "C" {

long zonos3_engine_init(const char * backbone_gguf, const char * decoder_gguf, const char * steer_gguf) {
    auto * e = new zonos3_engine();
    long sz = 0;
    if (!file_exists_with_size(backbone_gguf, sz)) {
        set_err(e, "backbone model not found: " + std::string(backbone_gguf ? backbone_gguf : "(null)"));
        ZLOGE("engine_init: %s", e->last_error.c_str());
        return (long) e;
    }
    ZLOGI("engine_init: backbone %.1f MB, loading", sz / 1e6);
    if (!zonos3_model_load(e->model, backbone_gguf, /*use_gpu=*/false)) {
        set_err(e, "failed to load backbone: " + std::string(backbone_gguf));
        ZLOGE("engine_init: %s", e->last_error.c_str());
        return (long) e; // handle stays valid so the caller can read the error
    }
    ZLOGI("engine_init: backbone loaded");
    e->model_ok = true;
    if (decoder_gguf && decoder_gguf[0]) {
        if (!zac_load(e->decoder, decoder_gguf, false)) {
            set_err(e, "failed to load ZAC decoder: " + std::string(decoder_gguf));
            return (long) e;
        }
        e->decoder_ok = true;
    }
    if (steer_gguf && steer_gguf[0] && file_exists_with_size(steer_gguf, sz)) {
        std::string serr;
        if (zonos3_steer_load(e->steer, steer_gguf, e->model, serr)) {
            e->steer_ok = true;
            ZLOGI("engine_init: steer bundle '%s': %zu directions", e->steer.name.c_str(), e->steer.dirs.size());
        } else {
            ZLOGE("engine_init: steer load failed: %s", serr.c_str());
        }
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
                            int max_frames, const std::string & controls_str,
                            std::vector<int32_t> & codes_out,
                            int & total_rows, int & ncb, int & prefill, int & eos_frame,
                            const zonos3_frame_cb & on_frame) {
    if (!e->model_ok) { set_err(e, "engine not initialized"); return false; }
    zonos3_prompt_options opt;
    std::vector<float> spk = e->speaker; // copy: raw steering moves the copy, never the stored embedding
    zonos3_steering steering;
    const zonos3_steering * steering_ptr = nullptr;
    if (!engine_apply_controls(e, controls_str, spk, steering, &steering_ptr)) return false;
    if (!spk.empty()) {
        if ((int) spk.size() != (int) e->model.hp.speaker_dim) {
            set_err(e, "speaker embedding must have " + std::to_string(e->model.hp.speaker_dim) + " floats");
            return false;
        }
        opt.speaker_embedding = spk.data();
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
                             /*out_full_ids=*/nullptr, on_frame, steering_ptr);
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
                              const char * controls, std::vector<float> & pcm_out) {
    auto * e = (zonos3_engine *) handle;
    if (!e) return false;
    std::lock_guard<std::mutex> lock(e->mu);
    if (!e->decoder_ok) { set_err(e, "no ZAC decoder loaded"); return false; }

    std::vector<int32_t> codes;
    int total = 0, ncb = 0, prefill = 0, eos_frame = -1;
    if (!engine_generate(e, text, seed, max_frames, controls ? controls : "", codes, total, ncb, prefill, eos_frame, {}))
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
                                     const char * controls,
                                     const std::function<bool(const float * pcm, size_t n)> & chunk_cb) {
    auto * e = (zonos3_engine *) handle;
    if (!e) return false;
    std::lock_guard<std::mutex> lock(e->mu);
    if (!e->decoder_ok) { set_err(e, "no ZAC decoder loaded"); return false; }

    std::vector<int32_t> codes;      // growing sheared matrix [rows, ncb]
    int rows = 0, ncb = (int) e->model.hp.n_codebooks, prefill = 0;
    // The decoder only consumes its own codebooks (keep2 = 2 of the backbone's 10), so the
    // decodable-frame limit and the delivery holdback use the DECODER's codebook count, not
    // the backbone's - using hp.n_codebooks here starved the tail (0.1.1 dropped the last ~0.25 s).
    const int dncb = e->decoder.n_codebooks;
    const int spf = zac_samples_per_frame(e->decoder);
    zac_stream stream;
    bool stream_started = false;
    std::vector<float> pending;      // decoded but not yet delivered (prefill drop + holdback)
    size_t delivered = 0; // audible samples only, never the discarded prefill
    size_t prefill_remaining = 0;
    bool ok = true;
    const float gain = zac_output_gain(e->decoder, /*headroom_db=*/-1.0);

    // EOS is only known when generation stops; hold back the tail so the post-EOS drain
    // frames never reach the listener.
    const size_t holdback = (size_t) (dncb - 1) * (size_t) spf;

    auto pump = [&](int f_hi) {
        if (f_hi <= 0) return;
        std::vector<float> chunk;
        if (!stream_started) {
            // zac_stream construction: default-constructed, first decode allocates its cache.
            stream_started = true;
        }
        if (!zac_stream_decode(e->decoder, stream, codes.data(), rows, f_hi, chunk)) { ok = false; return; }
        pending.insert(pending.end(), chunk.begin(), chunk.end());
        // Track discarded prefill separately. Using `delivered` for both counts
        // repeatedly discarded real speech in short utterances (and could underflow).
        const size_t skip = std::min(prefill_remaining, pending.size());
        pending.erase(pending.begin(), pending.begin() + skip);
        prefill_remaining -= skip;
        if (pending.size() > holdback) {
            const size_t n = pending.size() - holdback;
            for (size_t i = 0; i < n; ++i) pending[i] *= gain;
            zonos3_soft_limit(pending.data(), n);
            if (!chunk_cb(pending.data(), n)) { ok = false; return; }
            delivered += n;
            pending.erase(pending.begin(), pending.begin() + n);
        }
    };

    // Rows arrive one generated frame at a time; rows [0, prefill) are the silence prefill,
    // which we must prepend before the first generated row lands.
    bool prefill_prepended = false;
    zonos3_frame_cb on_frame = [&](int frame_idx, const int32_t * frame_codes, int f_ncb) -> bool {
        (void) frame_idx;
        codes.insert(codes.end(), frame_codes, frame_codes + f_ncb);
        rows++;
        pump(rows - (dncb - 1)); // only frames with their full sheared window decode
        return ok;
    };

    std::string text_copy(text);
    zonos3_prompt_options opt;
    std::vector<float> spk = e->speaker; // copy: raw steering moves the copy, never the stored embedding
    zonos3_steering steering;
    const zonos3_steering * steering_ptr = nullptr;
    if (!engine_apply_controls(e, controls ? controls : "", spk, steering, &steering_ptr)) return false;
    if (!spk.empty()) opt.speaker_embedding = spk.data();
    zonos3_prompt prompt;
    std::string err;
    if (!zonos3_build_prompt(e->model, text_copy, opt, prompt, &err)) { set_err(e, "prompt: " + err); return false; }
    prefill = prompt.prefill_frames;
    prefill_remaining = (size_t) prefill * (size_t) spf;
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
                             nullptr, on_frame, steering_ptr);
    if (rc < 0 || !ok) { set_err(e, "generation failed"); return false; }

    // Decode any rows the per-frame pumps could not finish (their residual codebook arrived with a
    // later row), then flush the decoder tail and deliver everything up to the EOS boundary.
    pump(rows - (dncb - 1));
    if (!ok) { set_err(e, "generation failed"); return false; }
    std::vector<float> tail;
    if (!zac_stream_flush(e->decoder, stream, tail)) { set_err(e, "stream flush failed"); return false; }
    pending.insert(pending.end(), tail.begin(), tail.end());

    // Match zac_decode: EOS is an absolute frame in the combined prefill +
    // generated matrix; without EOS only rows with complete codebooks can decode.
    const int decodable = std::max(0, rows - (dncb - 1));
    const int audio_frames = eos_frame >= 0 ? std::min(eos_frame, decodable) : decodable;
    const size_t keep_total = (size_t) std::max(0, audio_frames - prefill) * (size_t) spf;
    const size_t skip = std::min(prefill_remaining, pending.size());
    pending.erase(pending.begin(), pending.begin() + skip);
    prefill_remaining -= skip;
    const size_t remaining = keep_total > delivered ? keep_total - delivered : 0;
    const size_t n = std::min(pending.size(), remaining);
    if (n > 0) {
        for (size_t i = 0; i < n; ++i) pending[i] *= gain;
        zonos3_soft_limit(pending.data(), n);
        if (!chunk_cb(pending.data(), n)) { set_err(e, "audio listener interrupted"); return false; }
        delivered += n;
    }
    if (delivered == 0 || delivered != keep_total) {
        set_err(e, "stream returned " + std::to_string(delivered) + " of " + std::to_string(keep_total) + " audio samples");
        return false;
    }
    return true;
}

// ---------------------------------------------------------------------------
// Speaker encoder (MECT-B2): PCM -> 192-d embedding
// ---------------------------------------------------------------------------

long zonos3_spk_encoder_init(const char * spk_encoder_gguf) {
    long sz = 0;
    if (!file_exists_with_size(spk_encoder_gguf, sz)) {
        ZLOGE("spk_encoder_init: file not found: %s", spk_encoder_gguf ? spk_encoder_gguf : "(null)");
        return 0;
    }
    ZLOGI("spk_encoder_init: %.1f MB, loading", sz / 1e6);
    auto * m = new spk_model();
    if (!spk_load(*m, spk_encoder_gguf, /*use_gpu=*/false)) {
        ZLOGE("spk_encoder_init: spk_load failed");
        delete m;
        return 0;
    }
    ZLOGI("spk_encoder_init: loaded");
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

// Convert any C++ exception escaping the engine into a Java IllegalStateException
// instead of letting it terminate the process.
template <typename F>
static auto jni_guard(JNIEnv * env, F && f) -> decltype(f()) {
    try {
        return f();
    } catch (const std::exception & ex) {
        ZLOGE("native exception: %s", ex.what());
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), ex.what());
    } catch (...) {
        ZLOGE("unknown native exception");
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "unknown native error");
    }
    return decltype(f())();
}

extern "C" {

JNIEXPORT jlong JNICALL Java_com_zyphra_zonos3_Zonos3_nativeInit(JNIEnv * env, jclass,
                                                                 jstring backbone, jstring decoder, jstring steer) {
    return jni_guard(env, [&]() -> jlong {
        std::string b = jstr(env, backbone), d = jstr(env, decoder), s = jstr(env, steer);
        return (jlong) zonos3_engine_init(b.c_str(), d.c_str(), s.c_str());
    });
}

JNIEXPORT void JNICALL Java_com_zyphra_zonos3_Zonos3_nativeFree(JNIEnv * env, jclass, jlong h) {
    jni_guard(env, [&] { zonos3_engine_free(h); });
}

JNIEXPORT jstring JNICALL Java_com_zyphra_zonos3_Zonos3_nativeLastError(JNIEnv * env, jclass, jlong h) {
    return jni_guard(env, [&]() -> jstring {
        return env->NewStringUTF(zonos3_engine_last_error(h));
    });
}

JNIEXPORT jint JNICALL Java_com_zyphra_zonos3_Zonos3_nativeSampleRate(JNIEnv *, jclass) {
    return 44100;
}

JNIEXPORT jfloatArray JNICALL Java_com_zyphra_zonos3_Zonos3_nativeSynthesize(JNIEnv * env, jclass, jlong h,
                                                                             jstring text, jint seed, jint maxFrames, jstring controls) {
    return jni_guard(env, [&]() -> jfloatArray {
        std::vector<float> pcm;
        if (!zonos3_engine_synthesize(h, jstr(env, text).c_str(), (uint32_t) seed, maxFrames, jstr(env, controls).c_str(), pcm)) return nullptr;
        jfloatArray out = env->NewFloatArray((jsize) pcm.size());
        env->SetFloatArrayRegion(out, 0, (jsize) pcm.size(), pcm.data());
        return out;
    });
}

JNIEXPORT jboolean JNICALL Java_com_zyphra_zonos3_Zonos3_nativeSynthesizeToFile(JNIEnv * env, jclass, jlong h,
                                                                                jstring text, jstring path,
                                                                                jint seed, jint maxFrames, jstring controls) {
    return jni_guard(env, [&]() -> jboolean {
        std::vector<float> pcm;
        if (!zonos3_engine_synthesize(h, jstr(env, text).c_str(), (uint32_t) seed, maxFrames, jstr(env, controls).c_str(), pcm)) return JNI_FALSE;
        return wav_pcm16_write(jstr(env, path).c_str(), pcm, 44100) ? JNI_TRUE : JNI_FALSE;
    });
}

JNIEXPORT jboolean JNICALL Java_com_zyphra_zonos3_Zonos3_nativeSynthesizeStreaming(JNIEnv * env, jclass, jlong h,
                                                                                   jstring text, jint seed,
                                                                                   jint maxFrames, jstring controls, jobject listener) {
    return jni_guard(env, [&]() -> jboolean {
        jclass cls = env->GetObjectClass(listener);
        jmethodID onChunk = env->GetMethodID(cls, "onChunk", "([F)V");
        if (!onChunk) return JNI_FALSE;
        bool ok = zonos3_engine_synthesize_stream(h, jstr(env, text).c_str(), (uint32_t) seed, maxFrames,
            jstr(env, controls).c_str(),
            [&](const float * pcm, size_t n) -> bool {
                jfloatArray arr = env->NewFloatArray((jsize) n);
                env->SetFloatArrayRegion(arr, 0, (jsize) n, pcm);
                env->CallVoidMethod(listener, onChunk, arr);
                env->DeleteLocalRef(arr);
                return !env->ExceptionCheck();
            });
        return ok ? JNI_TRUE : JNI_FALSE;
    });
}

JNIEXPORT void JNICALL Java_com_zyphra_zonos3_Zonos3_nativeSetSpeakerEmbedding(JNIEnv * env, jclass, jlong h,
                                                                               jfloatArray emb) {
    jni_guard(env, [&] {
        jsize n = env->GetArrayLength(emb);
        std::vector<float> v(n);
        env->GetFloatArrayRegion(emb, 0, n, v.data());
        zonos3_engine_set_speaker_embedding(h, v.data(), n);
    });
}

JNIEXPORT void JNICALL Java_com_zyphra_zonos3_Zonos3_nativeClearSpeaker(JNIEnv * env, jclass, jlong h) {
    jni_guard(env, [&] { zonos3_engine_clear_speaker(h); });
}

JNIEXPORT jlong JNICALL Java_com_zyphra_zonos3_Zonos3_nativeSpkEncoderInit(JNIEnv * env, jclass, jstring path) {
    return jni_guard(env, [&]() -> jlong {
        return (jlong) zonos3_spk_encoder_init(jstr(env, path).c_str());
    });
}

JNIEXPORT void JNICALL Java_com_zyphra_zonos3_Zonos3_nativeSpkEncoderFree(JNIEnv * env, jclass, jlong h) {
    jni_guard(env, [&] { zonos3_spk_encoder_free(h); });
}

JNIEXPORT jfloatArray JNICALL Java_com_zyphra_zonos3_Zonos3_nativeSpkEmbed(JNIEnv * env, jclass, jlong h,
                                                                           jfloatArray pcm, jint sampleRate) {
    return jni_guard(env, [&]() -> jfloatArray {
        jsize n = env->GetArrayLength(pcm);
        ZLOGI("nativeSpkEmbed: %d samples @ %d Hz", (int) n, (int) sampleRate);
        std::vector<float> v(n);
        env->GetFloatArrayRegion(pcm, 0, n, v.data());
        std::vector<float> emb;
        if (!zonos3_spk_embed(h, v.data(), n, sampleRate, emb)) return (jfloatArray) nullptr;
        ZLOGI("nativeSpkEmbed: %zu-d embedding", emb.size());
        jfloatArray out = env->NewFloatArray((jsize) emb.size());
        env->SetFloatArrayRegion(out, 0, (jsize) emb.size(), emb.data());
        return out;
    });
}

} // extern "C" (JNI)


#else // host test harness: same engine code, driven from argv

#include <cstdio>

int main(int argc, char ** argv) {
    if (argc < 4) {
        fprintf(stderr, "usage: %s <backbone.gguf> <zac-keep2.gguf> <text> [out.wav] [--stream] [--steer steer.gguf] [--controls \"id=dose;...\"]\n", argv[0]);
        return 2;
    }
    const char * backbone = argv[1];
    const char * decoder = argv[2];
    const char * text = argv[3];
    const char * out_wav = argc > 4 ? argv[4] : "/tmp/zonos3-test.wav";
    bool stream = false;
    const char * steer = "";
    const char * controls = "";
    for (int i = 5; i < argc; i++) {
        if (!strcmp(argv[i], "--stream")) stream = true;
        else if (!strcmp(argv[i], "--steer") && i + 1 < argc) steer = argv[++i];
        else if (!strcmp(argv[i], "--controls") && i + 1 < argc) controls = argv[++i];
    }

    long h = zonos3_engine_init(backbone, decoder, steer);
    if (!h) { fprintf(stderr, "init failed\n"); return 1; }

    bool ok;
    if (stream) {
        std::vector<float> all;
        int chunks = 0;
        ok = zonos3_engine_synthesize_stream(h, text, /*seed=*/1, /*max_frames=*/0, controls,
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
        ok = zonos3_engine_synthesize(h, text, /*seed=*/1, /*max_frames=*/0, controls, pcm);
        fprintf(stderr, "synthesized %zu samples (%.2f s)\n", pcm.size(), pcm.size() / 44100.0);
        if (ok) ok = wav_pcm16_write(out_wav, pcm, 44100);
    }
    if (!ok) fprintf(stderr, "error: %s\n", zonos3_engine_last_error(h));
    zonos3_engine_free(h);
    return ok ? 0 : 1;
}

#endif // __ANDROID__
