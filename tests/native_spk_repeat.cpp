// Host check: two consecutive speaker embeddings on one encoder handle must both succeed.
#include <cmath>
#include <cstdio>
#include <vector>

#define main zonos3_cli_main
#include "../native/jni/zonos3_jni.cpp"
#undef main

static std::vector<float> tone(double f0, double f1, int seconds, int sr) {
    std::vector<float> pcm((size_t) seconds * sr);
    double phase = 0.0;
    for (size_t i = 0; i < pcm.size(); ++i) {
        double t = (double) i / sr;
        double f = f0 + (f1 - f0) * t / seconds;
        phase += 2.0 * M_PI * f / sr;
        // add a harmonic + slight wobble so it is voice-ish, not a pure sine
        pcm[i] = (float) (0.4 * sin(phase) + 0.15 * sin(2.7 * phase) * sin(2.0 * M_PI * 4.0 * t));
    }
    return pcm;
}

static double norm(const std::vector<float> & v) {
    double s = 0.0;
    for (float x : v) s += (double) x * x;
    return sqrt(s);
}

int main(int argc, char ** argv) {
    if (argc < 2) { fprintf(stderr, "usage: %s <spk-encoder.gguf>\n", argv[0]); return 2; }
    long h = zonos3_spk_encoder_init(argv[1]);
    if (!h) { fprintf(stderr, "encoder init failed\n"); return 1; }

    auto a = tone(110, 180, 4, 44100);
    auto b = tone(220, 300, 4, 44100);

    std::vector<float> e1, e2, e3;
    bool ok1 = zonos3_spk_embed(h, a.data(), (int64_t) a.size(), 44100, e1);
    bool ok2 = zonos3_spk_embed(h, b.data(), (int64_t) b.size(), 44100, e2);
    bool ok3 = zonos3_spk_embed(h, a.data(), (int64_t) a.size(), 44100, e3);
    printf("embed1 ok=%d dim=%zu norm=%.4f\n", ok1, e1.size(), norm(e1));
    printf("embed2 ok=%d dim=%zu norm=%.4f\n", ok2, e2.size(), norm(e2));
    printf("embed3 ok=%d dim=%zu norm=%.4f\n", ok3, e3.size(), norm(e3));

    if (ok1 && ok3 && e1.size() == e3.size()) {
        double d = 0.0;
        for (size_t i = 0; i < e1.size(); ++i) d += fabs((double) e1[i] - e3[i]);
        printf("repeat L1 diff: %.6f\n", d);
    }
    zonos3_spk_encoder_free(h);
    bool pass = ok1 && ok2 && ok3 && e1.size() == 192 && e2.size() == 192;
    printf(pass ? "PASS\n" : "FAIL\n");
    return pass ? 0 : 1;
}
