package com.voicereps.app

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.exp

/** All input stays on the device. Download only immutable, hash-verified model weights. */
object EmotionModel {
    const val REVISION = "ac26dadbdafc26276e01093790104679bf33aec5"
    private const val HASH = "7fed4f82f2e1c4039452876e87ef948f5eedfdcf45f351470010e0ed65f1e9ee"
    private const val SIZE = 268449278L
    private val labels = arrayOf("admiration", "amusement", "anger", "annoyance", "approval", "caring", "confusion", "curiosity", "desire", "disappointment", "disapproval", "disgust", "embarrassment", "excitement", "fear", "gratitude", "grief", "joy", "love", "nervousness", "optimism", "pride", "realization", "relief", "remorse", "sadness", "surprise", "neutral")
    data class Choice(val label: String, val preset: String, val windows: Int)
    fun ready(ctx: Context) = file(ctx).length() == SIZE
    private fun file(ctx: Context) = File(ctx.filesDir, "emotions-$REVISION.onnx")
    private fun download(ctx: Context, active: () -> Boolean, progress: (String) -> Unit): File {
        val out = file(ctx)
        if (ready(ctx)) return out
        val part = File(out.path + ".part")
        val connection = URL("https://huggingface.co/Horizon-Labs/multilingual-emotions-small/resolve/$REVISION/onnx/model_quantized.onnx").openConnection() as HttpURLConnection
        connection.connectTimeout = 20000; connection.readTimeout = 20000
        try {
            check(connection.responseCode == 200) { "Emotion model download: HTTP ${connection.responseCode}. Try again on Wi-Fi." }
            val hash = MessageDigest.getInstance("SHA-256"); var got = 0L; var lastPercent = -1
            connection.inputStream.use { source -> part.outputStream().use { dest ->
                val b = ByteArray(256 * 1024)
                while (true) {
                    check(active()) { "Stopped" }
                    val n = source.read(b); if (n < 0) break
                    dest.write(b, 0, n); hash.update(b, 0, n); got += n
                    val percent = (got * 100 / SIZE).toInt()
                    if (percent != lastPercent) { progress("Downloading emotion model: $percent% (${got / 1_000_000}/268 MB)"); lastPercent = percent }
                }
            } }
            check(got == SIZE && hash.digest().joinToString("") { "%02x".format(it) } == HASH) { "Emotion model download is incomplete or damaged. Try again." }
            check(part.renameTo(out)) { "Could not save emotion model. Check free storage." }
            return out
        } finally { connection.disconnect(); part.delete() }
    }
    /** Release tokenizer and ONNX session before TTS, rather than cache two inference engines. */
    @Synchronized fun classify(ctx: Context, text: String, active: () -> Boolean, progress: (String) -> Unit): Choice {
        require(text.length <= 100000) { "Auto emotions supports up to 100,000 characters per request. Split this text or turn Auto emotions off. No text was cut." }
        val model = download(ctx, active, progress)
        check(active()) { "Stopped" }; progress("Detecting emotion on device...")
        val windows = ctx.assets.open("emotion-tokenizer.bin").use { EmotionTokenizer.windows(EmotionTokenizer(it).encode(text)) }
        val scores = DoubleArray(28); var weight = 0
        val env = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
            options.setMemoryPatternOptimization(false)
            env.createSession(model.path, options).use { session ->
                windows.forEachIndexed { index, ids ->
                    check(active()) { "Stopped" }; progress("Detecting emotion: ${index + 1}/${windows.size} text sections...")
                    OnnxTensor.createTensor(env, arrayOf(ids)).use { input ->
                        OnnxTensor.createTensor(env, arrayOf(LongArray(ids.size) { 1L })).use { mask ->
                            session.run(mapOf("input_ids" to input, "attention_mask" to mask)).use { result ->
                                @Suppress("UNCHECKED_CAST")
                                val logits = (result[0].value as Array<FloatArray>)[0]
                                check(logits.size == 28 && logits.all { it.isFinite() }) { "Emotion model returned invalid scores." }
                                val n = (ids.size - 2).coerceAtLeast(1); weight += n
                                logits.forEachIndexed { i, v -> scores[i] += n / (1.0 + exp(-v.toDouble())) }
                            }
                        }
                    }
                }
            }
        }
        check(active()) { "Stopped" }
        return choose(DoubleArray(28) { scores[it] / weight }, windows.size)
    }
    internal fun choose(scores: DoubleArray, windows: Int = 1): Choice {
        val best = scores.indices.maxByOrNull { scores[it] } ?: 27
        // Conservative global threshold: confidence is not steering strength. Ambiguous and
        // unsupported emotions keep neutral delivery, never guess Calm or Whisper.
        val label = labels[best]
        val preset = if (scores[best] < 0.5 || scores[27] >= scores[best]) "Neutral" else when (label) {
            "joy", "amusement", "gratitude", "love", "optimism", "pride", "admiration" -> "Happy"
            "sadness", "grief", "disappointment", "remorse" -> "Sad"
            "anger", "annoyance" -> "Angry"
            "excitement" -> "Excited"
            else -> "Neutral"
        }
        return Choice(label, preset, windows)
    }
}
