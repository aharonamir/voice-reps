package com.voicereps.app

import android.content.Context
import android.util.Log
import com.zyphra.zonos3.Zonos3
import java.io.File

/** App-level wrapper: model paths, lazy engine/encoder handles, voice embedding. */
object Zonos3Engine {

    private const val TAG = "Zonos3Engine"
    const val SAMPLE_RATE = 44100
    const val EMBEDDING_DIM = 192

    private const val HF_BASE = "https://huggingface.co/Zyphra/ZONOS3-Mini/resolve/main/gguf"
    private const val BACKBONE = "backbone-ptq.gguf" // 663 MB, Q4 experts / Q8 mixers (renamed upstream 2026-11-11, was zonos3-ptq.gguf)
    private const val DECODER = "zac-keep2.gguf"     // 452 MB, waveform decoder
    private const val SPK = "spk-encoder.gguf"       // 45 MB, speaker encoder
    private const val STEER = "steer.gguf"           // 0.2 MB, expression/emotion steering directions

    data class ModelFile(val name: String, val minBytes: Long) {
        val url get() = "$HF_BASE/$name"
    }

    val requiredFiles = listOf(
        ModelFile(BACKBONE, 600_000_000L),
        ModelFile(DECODER, 400_000_000L),
        ModelFile(SPK, 40_000_000L),
        ModelFile(STEER, 100_000L),
    )

    fun modelsDir(ctx: Context) = File(ctx.filesDir, "models").apply {
        mkdirs()
        // 0.1.0 saved the backbone under its pre-rename upstream name; migrate in place.
        val legacy = File(this, "zonos3-ptq.gguf")
        val current = File(this, BACKBONE)
        if (legacy.exists() && !current.exists()) legacy.renameTo(current)
        val legacyPart = File(this, "zonos3-ptq.gguf.part")
        val currentPart = File(this, "$BACKBONE.part")
        if (legacyPart.exists() && !currentPart.exists()) legacyPart.renameTo(currentPart)
    }
    fun modelFile(ctx: Context, name: String) = File(modelsDir(ctx), name)
    fun modelsReady(ctx: Context) = requiredFiles.all { modelFile(ctx, it.name).length() >= it.minBytes }

    /** Human-readable reason the last native operation failed ("" when none). */
    @Volatile var lastNativeError: String = ""
        private set

    /** Non-null when the native library itself is not usable on this device. */
    val nativeLoadError: String? get() = Zonos3.loadError

    @Volatile private var engine: Long = 0
    @Volatile private var spkEncoder: Long = 0

    @Synchronized
    fun engine(ctx: Context): Long {
        Zonos3.checkLoaded()
        if (engine == 0L) {
            val b = modelFile(ctx, BACKBONE)
            val d = modelFile(ctx, DECODER)
            val s = modelFile(ctx, STEER)
            Log.i(TAG, "init engine: backbone=${b.absolutePath} (${b.length()} bytes), decoder=${d.absolutePath} (${d.length()} bytes), steer=${s.absolutePath} (${s.length()} bytes)")
            engine = Zonos3.nativeInit(b.absolutePath, d.absolutePath, s.absolutePath)
            val err = Zonos3.nativeLastError(engine)
            Log.i(TAG, "engine handle=$engine, native error='${err}'")
            if (err.isNotEmpty()) lastNativeError = err
        }
        return engine
    }

    @Synchronized
    fun spkEncoder(ctx: Context): Long {
        Zonos3.checkLoaded()
        if (spkEncoder == 0L) {
            val f = modelFile(ctx, SPK)
            Log.i(TAG, "init speaker encoder: ${f.absolutePath} (${f.length()} bytes)")
            spkEncoder = Zonos3.nativeSpkEncoderInit(f.absolutePath)
            Log.i(TAG, "speaker encoder handle=$spkEncoder")
        }
        return spkEncoder
    }

    fun lastError(handle: Long): String = try { Zonos3.nativeLastError(handle) } catch (t: Throwable) { "$t" }

    /** Synthesize text with a saved 192-d voice embedding (null = default voice). controls: "id=dose;..." steering ("" = none). */
    fun synthesize(ctx: Context, text: String, embedding: FloatArray?, controls: String = ""): FloatArray? = try {
        val h = engine(ctx)
        if (embedding != null) Zonos3.nativeSetSpeakerEmbedding(h, embedding) else Zonos3.nativeClearSpeaker(h)
        val pcm = Zonos3.nativeSynthesize(h, text, seed(), 0, controls)
        if (pcm == null) lastNativeError = Zonos3.nativeLastError(h).ifEmpty { "synthesis returned no audio" }
        pcm
    } catch (t: Throwable) { fail("synthesize", t) }

    fun synthesizeStreaming(ctx: Context, text: String, embedding: FloatArray?, controls: String = "", listener: Zonos3.PcmListener): Boolean = try {
        val h = engine(ctx)
        if (embedding != null) Zonos3.nativeSetSpeakerEmbedding(h, embedding) else Zonos3.nativeClearSpeaker(h)
        val ok = Zonos3.nativeSynthesizeStreaming(h, text, seed(), 0, controls, listener)
        if (!ok) lastNativeError = Zonos3.nativeLastError(h).ifEmpty { "streaming synthesis failed" }
        ok
    } catch (t: Throwable) { fail("synthesizeStreaming", t); false }

    fun synthesizeToWav(ctx: Context, text: String, embedding: FloatArray?, wav: File, controls: String = ""): Boolean = try {
        val h = engine(ctx)
        if (embedding != null) Zonos3.nativeSetSpeakerEmbedding(h, embedding) else Zonos3.nativeClearSpeaker(h)
        val ok = Zonos3.nativeSynthesizeToFile(h, text, wav.absolutePath, seed(), 0, controls)
        if (!ok) lastNativeError = Zonos3.nativeLastError(h).ifEmpty { "WAV render failed" }
        ok
    } catch (t: Throwable) { fail("synthesizeToWav", t); false }

    /** Compute a 192-d speaker embedding from mono PCM (44.1k or 16k, up to 60 s used). Null on failure - read lastNativeError. */
    fun embedVoice(ctx: Context, pcm: FloatArray, sampleRate: Int): FloatArray? = try {
        Log.i(TAG, "embedVoice: ${pcm.size} samples @ $sampleRate Hz")
        val h = spkEncoder(ctx)
        if (h == 0L) {
            lastNativeError = "speaker encoder failed to load (models/spk-encoder.gguf missing or corrupt - re-download models)"
            Log.e(TAG, lastNativeError)
            null
        } else {
            val emb = Zonos3.nativeSpkEmbed(h, pcm, sampleRate)
            when {
                emb == null -> { lastNativeError = "speaker encoder returned no embedding"; null }
                emb.size != EMBEDDING_DIM -> { lastNativeError = "unexpected embedding size ${emb.size}"; null }
                else -> { Log.i(TAG, "embedVoice ok"); emb }
            }
        }
    } catch (t: Throwable) { fail("embedVoice", t) }

    private fun seed() = (System.currentTimeMillis() % 100000).toInt()

    private fun fail(where: String, t: Throwable): FloatArray? {
        lastNativeError = "${t.javaClass.simpleName}: ${t.message}"
        Log.e(TAG, "$where failed", t)
        return null
    }
}
