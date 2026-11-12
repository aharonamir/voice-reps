package com.zyphra.zonos3

import android.util.Log

/** JNI bridge to the native ZONOS3-Mini ggml runtime (libzonos3jni.so). */
object Zonos3 {
    private const val TAG = "Zonos3"

    /** Non-null when libzonos3jni failed to load; every native call will fail in that case. */
    @Volatile var loadError: String? = null
        private set

    init {
        try {
            System.loadLibrary("zonos3jni")
            Log.i(TAG, "libzonos3jni loaded")
        } catch (t: Throwable) {
            loadError = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "libzonos3jni failed to load", t)
        }
    }

    /** Throws UnsatisfiedLinkError with the real load failure when the native lib is missing. */
    fun checkLoaded() {
        loadError?.let { throw UnsatisfiedLinkError("native engine unavailable: $it") }
    }

    interface PcmListener { fun onChunk(pcm: FloatArray) }

    @JvmStatic external fun nativeInit(backboneGguf: String, zacDecoderGguf: String, steerGguf: String): Long
    @JvmStatic external fun nativeFree(handle: Long)
    @JvmStatic external fun nativeLastError(handle: Long): String
    @JvmStatic external fun nativeSampleRate(): Int
    @JvmStatic external fun nativeSynthesize(handle: Long, text: String, seed: Int, maxFrames: Int, controls: String): FloatArray?
    @JvmStatic external fun nativeSynthesizeToFile(handle: Long, text: String, wavPath: String, seed: Int, maxFrames: Int, controls: String): Boolean
    @JvmStatic external fun nativeSynthesizeStreaming(handle: Long, text: String, seed: Int, maxFrames: Int, controls: String, listener: PcmListener): Boolean
    @JvmStatic external fun nativeSetSpeakerEmbedding(handle: Long, embedding: FloatArray)
    @JvmStatic external fun nativeClearSpeaker(handle: Long)
    @JvmStatic external fun nativeSpkEncoderInit(spkEncoderGguf: String): Long
    @JvmStatic external fun nativeSpkEncoderFree(handle: Long)
    @JvmStatic external fun nativeSpkEmbed(handle: Long, pcm: FloatArray, sampleRate: Int): FloatArray?
}
