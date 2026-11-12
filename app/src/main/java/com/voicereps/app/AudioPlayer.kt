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
    ) {
        @Volatile internal var closed = false
        internal var written = 0L
        internal val stats = PcmStats()
    }

    /** Call only when PCM is ready, not before a potentially long model load. */
    fun beginStream(ctx: Context? = null): Stream {
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
            return Stream(t, manager, focus).also { stream = it; t.play(); playing = true }
        } catch (t: Throwable) {
            if (focus != null) manager.abandonAudioFocusRequest(focus)
            stop()
            throw t
        }
    }

    fun writeStream(s: Stream, pcm: FloatArray) {
        check(!s.closed && stream === s) { "Playback was interrupted. Try Speak again." }
        s.stats.add(pcm)
        var offset = 0
        while (offset < pcm.size) {
            check(!s.closed) { "Playback was interrupted. Try Speak again." }
            val n = s.track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
            check(n > 0) { "Android audio write failed ($n). Try Speak again." }
            offset += n
            s.written += n
        }
    }

    /** Drain buffered audio, then release. Never claim success on a stalled/dead track. */
    fun finishStream(s: Stream, timeoutMs: Long = 30000) {
        try {
            s.stats.requireAudible()
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (true) {
                check(!s.closed) { "Playback was interrupted. Try Speak again." }
                val played = s.track.playbackHeadPosition.toLong() and 0xffffffffL
                if (played >= s.written) break
                check(s.track.playState == AudioTrack.PLAYSTATE_PLAYING) { "Android stopped audio playback. Try Speak again." }
                check(SystemClock.elapsedRealtime() < deadline) { "Android audio playback stalled. Try Speak again." }
                Thread.sleep(25)
            }
            Log.i("AudioPlayer", "played ${s.written} frames; peak=${s.stats.peak}; route=${s.track.routedDevice?.productName}")
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

    fun cancelStream(s: Stream) { close(s) }
    fun stop() { stream?.let { close(it) } }
}
