// Minimal JNI wrapper around LAME (libmp3lame 3.100) for voice-reps MP3 export.
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <lame.h>

JNIEXPORT jlong JNICALL Java_com_voicereps_app_Mp3Encoder_nativeInit(
        JNIEnv *env, jclass cls, jint rate, jint channels, jint kbps) {
    lame_global_flags *g = lame_init();
    if (!g) return 0;
    lame_set_in_samplerate(g, rate);
    lame_set_num_channels(g, channels);
    lame_set_mode(g, channels == 1 ? MONO : JOINT_STEREO);
    lame_set_brate(g, kbps);
    lame_set_quality(g, 2);
    if (lame_init_params(g) < 0) { lame_close(g); return 0; }
    return (jlong)(intptr_t) g;
}

// Mono: pcm holds n samples. Stereo: interleaved, n = frames * 2.
JNIEXPORT jint JNICALL Java_com_voicereps_app_Mp3Encoder_nativeEncode(
        JNIEnv *env, jclass cls, jlong h, jshortArray pcm, jint n, jbyteArray out) {
    lame_global_flags *g = (lame_global_flags *)(intptr_t) h;
    jsize cap = (*env)->GetArrayLength(env, out);
    jshort *in = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte *o = (*env)->GetByteArrayElements(env, out, NULL);
    int r;
    if (lame_get_num_channels(g) == 1)
        r = lame_encode_buffer(g, in, NULL, n, (unsigned char *) o, cap);
    else
        r = lame_encode_buffer_interleaved(g, in, n / 2, (unsigned char *) o, cap);
    (*env)->ReleaseShortArrayElements(env, pcm, in, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, o, 0);
    return r;
}

JNIEXPORT jint JNICALL Java_com_voicereps_app_Mp3Encoder_nativeFlush(
        JNIEnv *env, jclass cls, jlong h, jbyteArray out) {
    lame_global_flags *g = (lame_global_flags *)(intptr_t) h;
    jsize cap = (*env)->GetArrayLength(env, out);
    jbyte *o = (*env)->GetByteArrayElements(env, out, NULL);
    int r = lame_encode_flush(g, (unsigned char *) o, cap);
    (*env)->ReleaseByteArrayElements(env, out, o, 0);
    return r;
}

JNIEXPORT void JNICALL Java_com_voicereps_app_Mp3Encoder_nativeClose(
        JNIEnv *env, jclass cls, jlong h) {
    if (h) lame_close((lame_global_flags *)(intptr_t) h);
}
