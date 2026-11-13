package com.voicereps.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Recovers voices whose setup was interrupted: the recording is persisted before the
 * heavy embedding step, so a process death (backgrounding during "can take a minute")
 * or a native failure leaves a saved voice with no profile. The Speak picker only lists
 * ready voices, so such a voice silently never appears there. finishPending rebuilds
 * those embeddings in the background, once per voice per process, so the voice shows up
 * on its own. The manual retry in the Voices tab stays as the fallback.
 */
object VoiceSetup {

    private val running = AtomicBoolean(false)
    private val attemptedThisProcess = mutableSetOf<String>()

    /** Replaceable for host tests, where the native encoder is unavailable. */
    @Volatile
    var embedder: (Context, FloatArray) -> FloatArray? = { ctx, pcm ->
        Zonos3Engine.embedVoice(ctx, pcm, Zonos3Engine.SAMPLE_RATE)
    }

    /**
     * Rebuild the embedding of every saved voice that is not ready yet. No-op while a
     * previous run is active. [onReady] is posted to the main thread if at least one
     * voice became ready; [onDone] runs on the worker thread when the sweep ends
     * (used by tests).
     */
    fun finishPending(ctx: Context, onDone: (() -> Unit)? = null, onReady: (() -> Unit)? = null) {
        val appCtx = ctx.applicationContext
        val store = VoiceStore(appCtx)
        val pending = store.list().filter { !it.ready && !attemptedThisProcess.contains(it.id) }
        if (pending.isEmpty() || !running.compareAndSet(false, true)) {
            onDone?.invoke()
            return
        }
        Thread {
            var completed = false
            try {
                for (v in pending) {
                    attemptedThisProcess.add(v.id)
                    try {
                        val pcm = AudioDecoder.decodeWavFile(v.referenceWav)
                        val emb = pcm?.let { embedder(appCtx, it) }
                        if (emb != null) {
                            store.complete(v, emb)
                            completed = true
                        } else {
                            CrashLog.record(appCtx, "finishPending(${v.id})",
                                Exception("embedding failed: ${Zonos3Engine.lastNativeError.ifEmpty { "no detail" }}"))
                        }
                    } catch (t: Throwable) {
                        CrashLog.record(appCtx, "finishPending(${v.id})", t)
                    }
                }
            } finally {
                running.set(false)
                onDone?.invoke()
            }
            if (completed && onReady != null) {
                Handler(Looper.getMainLooper()).post { onReady() }
            }
        }.start()
    }
}
