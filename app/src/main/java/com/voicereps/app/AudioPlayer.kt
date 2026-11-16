package com.voicereps.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log

/** 44.1 kHz mono float PCM. Playback errors must reach the caller, not look like success. */
class AudioPlayer {
    @Volatile private var stream: Stream? = null
    @Volatile var playing = false; private set

    private fun attributes() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    class Stream internal constructor(
        internal val track: AudioTrack,
        internal val manager: AudioManager?,
        internal val focus: AudioFocusRequest?,
        mode: BufferMode,
        initialMs: Int,
    ) {
        @Volatile internal var closed = false
        @Volatile internal var paused = false
        internal val stats = PcmStats()
        internal val sink = object : PcmSink {
            override fun write(pcm: FloatArray, offset: Int, count: Int): Int =
                track.write(pcm, offset, count, AudioTrack.WRITE_NON_BLOCKING).also {
                    check(it >= 0) { "Android audio write failed ($it). Try Speak again." }
                }
            override fun playedFrames(): Long = track.playbackHeadPosition.toLong() and 0xffffffffL
            override fun play() { track.play() }
            override fun pause() { track.pause() }
        }
        val buffer = JitterBuffer(sink, 44100, mode, initialMs, clock = { SystemClock.elapsedRealtime() })
        @Volatile internal var writerError: Throwable? = null
        internal val written get() = buffer.writtenFrames()
        fun snapshot() = buffer.stats()
    }

    /**
     * Call only when PCM is ready, not before a potentially long model load. Audio is queued in a
     * jitter buffer and played by a writer thread; [mode]/[initialBufferMs] set how much is held
     * back before playback starts.
     */
    fun beginStream(ctx: Context? = null, mode: BufferMode = BufferMode.AUTO, initialBufferMs: Int = mode.initialMs): Stream {
        stop()
        val manager = ctx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        check(manager == null || manager.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) {
            "Media volume is muted. Turn it up and try Speak again."
        }
        val focus = manager?.let { am ->
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes())
                .setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
                }.build().also {
                    check(am.requestAudioFocus(it) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        "Audio is in use by another app. Try Speak again."
                    }
                }
        }
        try {
            val t = AudioTrack.Builder().setAudioAttributes(attributes())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(44100)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(44100 * 4).setTransferMode(AudioTrack.MODE_STREAM).build()
            if (t.state != AudioTrack.STATE_INITIALIZED) {
                t.release()
                error("Android could not initialize media playback. Try Speak again.")
            }
            val s = Stream(t, manager, focus, mode, initialBufferMs)
            stream = s
            // The writer thread owns track.play()/pause() for buffering; it starts playback once
            // enough audio is queued (or generation finished).
            Thread({ writerLoop(s) }, "voice-reps-audio-writer").also { it.isDaemon = true }.start()
            return s
        } catch (t: Throwable) {
            if (focus != null) manager.abandonAudioFocusRequest(focus)
            stop()
            throw t
        }
    }

    private fun writerLoop(s: Stream) {
        try {
            while (!s.closed) {
                if (s.paused) { Thread.sleep(20); continue }
                val finished = synchronized(s) { if (s.closed || s.paused) false else s.buffer.step() }
                playing = !s.paused && s.buffer.stats().state == JitterBuffer.State.PLAYING && stream === s
                if (finished) break
                Thread.sleep(10)
            }
        } catch (t: Throwable) {
            if (!s.closed) { s.writerError = t; Log.e("AudioPlayer", "writer failed", t) }
        }
    }

    /** Queue PCM. Blocks only when the generator is far ahead of playback (bounded memory). */
    fun writeStream(s: Stream, pcm: FloatArray) {
        check(!s.closed && stream === s) { "Playback was interrupted. Try Speak again." }
        s.writerError?.let { throw IllegalStateException(it.message ?: "Audio playback failed.", it) }
        s.stats.add(pcm)
        s.buffer.offer(pcm)
        val waitStart = SystemClock.elapsedRealtime()
        var deadline = waitStart + 30000
        while (s.buffer.isFull()) {
            check(!s.closed) { "Playback was interrupted. Try Speak again." }
            s.writerError?.let { throw IllegalStateException(it.message ?: "Audio playback failed.", it) }
            if (s.paused) deadline = SystemClock.elapsedRealtime() + 30000
            check(SystemClock.elapsedRealtime() < deadline) { "Android audio playback stalled." }
            Thread.sleep(10)
        }
        val waited = SystemClock.elapsedRealtime() - waitStart
        if (waited > 0) s.buffer.addBlockedMs(waited)
    }

    /** Drain buffered audio, then release. Never claim success on a stalled/dead track. */
    fun finishStream(s: Stream, timeoutMs: Long = 30000): JitterStats {
        try {
            s.stats.requireAudible()
            s.buffer.markDone()
            var deadline = SystemClock.elapsedRealtime() + timeoutMs
            var previous = -1L
            while (true) {
                check(!s.closed) { "Playback was interrupted. Try Speak again." }
                s.writerError?.let { throw IllegalStateException(it.message ?: "Audio playback failed.", it) }
                if (s.paused) { deadline = SystemClock.elapsedRealtime() + timeoutMs; Thread.sleep(25); continue }
                val played = synchronized(s) {
                    check(!s.closed) { "Playback interrupted" }
                    s.sink.playedFrames()
                }
                val state = s.buffer.stats().state
                if (played != previous || state != JitterBuffer.State.PLAYING) { deadline = SystemClock.elapsedRealtime() + timeoutMs; previous = played }
                if (s.buffer.isDrained() && played >= s.written) break
                if (s.paused) continue
                check(state != JitterBuffer.State.PLAYING || s.track.playState == AudioTrack.PLAYSTATE_PLAYING) { "Android stopped audio playback. Try Speak again." }
                check(SystemClock.elapsedRealtime() < deadline) { "Android audio playback stalled. Try Speak again." }
                Thread.sleep(25)
            }
            val st = s.buffer.stats()
            Log.i("AudioPlayer", "played ${s.written} frames; peak=${s.stats.peak}; underruns=${st.underruns}; rtf=${st.realTimeFactor}; route=${s.track.routedDevice?.productName}")
            return st
        } finally { close(s) }
    }

    fun play(pcm: FloatArray, onDone: () -> Unit = {}) {
        Thread {
            try {
                val s = beginStream()
                writeStream(s, pcm)
                finishStream(s)
            } catch (t: Throwable) {
                Log.e("AudioPlayer", "reference playback failed", t)
                stop()
            } finally { onDone() }
        }.start()
    }

    private fun close(s: Stream) {
        synchronized(s) {
            if (s.closed) return
            s.closed = true
            try { s.track.stop() } catch (_: IllegalStateException) {}
            s.track.release()
            if (s.focus != null) s.manager?.abandonAudioFocusRequest(s.focus)
            if (stream === s) { stream = null; playing = false }
        }
    }

    fun pause() { stream?.let { s -> synchronized(s) {
        if (!s.closed) { s.paused = true; s.track.pause(); playing = false }
    } } }
    fun resume() { stream?.let { s -> synchronized(s) {
        if (!s.closed) { s.paused = false; s.buffer.onResume(); playing = true }
    } } }

    fun cancelStream(s: Stream) { close(s) }
    fun stop() { stream?.let { close(it) } }
}
