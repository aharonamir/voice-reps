package com.zyphra.zonos3

/** JNI bridge to the native ZONOS3-Mini ggml runtime (libzonos3jni.so). */
object Zonos3 {
    init { System.loadLibrary("zonos3jni") }

    interface PcmListener { fun onChunk(pcm: FloatArray) }

    @JvmStatic external fun nativeInit(backboneGguf: String, zacDecoderGguf: String): Long
    @JvmStatic external fun nativeFree(handle: Long)
    @JvmStatic external fun nativeLastError(handle: Long): String
    @JvmStatic external fun nativeSampleRate(): Int
    @JvmStatic external fun nativeSynthesize(handle: Long, text: String, seed: Int, maxFrames: Int): FloatArray?
    @JvmStatic external fun nativeSynthesizeToFile(handle: Long, text: String, wavPath: String, seed: Int, maxFrames: Int): Boolean
    @JvmStatic external fun nativeSynthesizeStreaming(handle: Long, text: String, seed: Int, maxFrames: Int, listener: PcmListener): Boolean
    @JvmStatic external fun nativeSetSpeakerEmbedding(handle: Long, embedding: FloatArray)
    @JvmStatic external fun nativeClearSpeaker(handle: Long)
    @JvmStatic external fun nativeSpkEncoderInit(spkEncoderGguf: String): Long
    @JvmStatic external fun nativeSpkEncoderFree(handle: Long)
    @JvmStatic external fun nativeSpkEmbed(handle: Long, pcm: FloatArray, sampleRate: Int): FloatArray?
}
