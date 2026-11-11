package com.voicereps.app

import android.content.Context
import com.zyphra.zonos3.Zonos3
import java.io.File

/** App-level wrapper: model paths, lazy engine/encoder handles, voice embedding. */
object Zonos3Engine {

    const val SAMPLE_RATE = 44100

    private const val HF_BASE = "https://huggingface.co/Zyphra/ZONOS3-Mini/resolve/main/gguf"
    private const val BACKBONE = "zonos3-ptq.gguf"   // 663 MB, Q4 experts / Q8 mixers
    private const val DECODER = "zac-keep2.gguf"     // 452 MB, waveform decoder
    private const val SPK = "spk-encoder.gguf"       // 45 MB, speaker encoder

    data class ModelFile(val name: String, val minBytes: Long) {
        val url get() = "$HF_BASE/$name"
    }

    val requiredFiles = listOf(
        ModelFile(BACKBONE, 600_000_000L),
        ModelFile(DECODER, 400_000_000L),
        ModelFile(SPK, 40_000_000L),
    )

    fun modelsDir(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }
    fun modelFile(ctx: Context, name: String) = File(modelsDir(ctx), name)
    fun modelsReady(ctx: Context) = requiredFiles.all { modelFile(ctx, it.name).length() >= it.minBytes }

    @Volatile private var engine: Long = 0
    @Volatile private var spkEncoder: Long = 0

    @Synchronized
    fun engine(ctx: Context): Long {
        if (engine == 0L) {
            engine = Zonos3.nativeInit(
                modelFile(ctx, BACKBONE).absolutePath,
                modelFile(ctx, DECODER).absolutePath)
        }
        return engine
    }

    @Synchronized
    fun spkEncoder(ctx: Context): Long {
        if (spkEncoder == 0L) spkEncoder = Zonos3.nativeSpkEncoderInit(modelFile(ctx, SPK).absolutePath)
        return spkEncoder
    }

    fun lastError(handle: Long): String = Zonos3.nativeLastError(handle)

    /** Synthesize text with a saved 192-d voice embedding (null = default voice). */
    fun synthesize(ctx: Context, text: String, embedding: FloatArray?): FloatArray? {
        val h = engine(ctx)
        if (embedding != null) Zonos3.nativeSetSpeakerEmbedding(h, embedding) else Zonos3.nativeClearSpeaker(h)
        return Zonos3.nativeSynthesize(h, text, /*seed=*/(System.currentTimeMillis() % 100000).toInt(), /*maxFrames=*/0)
    }

    fun synthesizeStreaming(ctx: Context, text: String, embedding: FloatArray?, listener: Zonos3.PcmListener): Boolean {
        val h = engine(ctx)
        if (embedding != null) Zonos3.nativeSetSpeakerEmbedding(h, embedding) else Zonos3.nativeClearSpeaker(h)
        return Zonos3.nativeSynthesizeStreaming(h, text, (System.currentTimeMillis() % 100000).toInt(), 0, listener)
    }

    fun synthesizeToWav(ctx: Context, text: String, embedding: FloatArray?, wav: File): Boolean {
        val h = engine(ctx)
        if (embedding != null) Zonos3.nativeSetSpeakerEmbedding(h, embedding) else Zonos3.nativeClearSpeaker(h)
        return Zonos3.nativeSynthesizeToFile(h, text, wav.absolutePath, (System.currentTimeMillis() % 100000).toInt(), 0)
    }

    /** Compute a 192-d speaker embedding from mono PCM (44.1k or 16k, up to 60 s used). */
    fun embedVoice(ctx: Context, pcm: FloatArray, sampleRate: Int): FloatArray? =
        Zonos3.nativeSpkEmbed(spkEncoder(ctx), pcm, sampleRate)
}
