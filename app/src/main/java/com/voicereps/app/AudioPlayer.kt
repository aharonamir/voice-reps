package com.voicereps.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** Simple AudioTrack player for 44.1 kHz mono float PCM. */
class AudioPlayer {
    private var track: AudioTrack? = null
    @Volatile var playing = false; private set

    fun play(pcm: FloatArray, onDone: () -> Unit = {}) {
        stop()
        playing = true
        Thread {
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(44100).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size * 4)
                .setTransferMode(AudioTrack.MODE_STATIC).build()
            track = t
            t.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            t.play()
            Thread.sleep((pcm.size * 1000L / 44100) + 100)
            playing = false
            onDone()
        }.start()
    }

    @Volatile private var streamFramesWritten = 0L

    /** Create a streaming track the caller writes chunks to via writeStream; finishStream() to end. */
    fun beginStream(): AudioTrack {
        stop()
        streamFramesWritten = 0L
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(44100).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(44100 * 4)
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        track = t
        t.play()
        playing = true
        return t
    }

    fun writeStream(t: AudioTrack, pcm: FloatArray) {
        val n = t.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
        if (n > 0) streamFramesWritten += n
    }

    /**
     * Wait until every written frame has actually played, then stop. stop() alone returns while up to
     * the whole stream buffer (1 s here) is still queued, and release() right after discards it -
     * that cut off the end of the speech (0.1.1: the last word went missing).
     */
    fun finishStream(timeoutMs: Long = 30000) {
        val t = track ?: return
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (t.playState != AudioTrack.PLAYSTATE_PLAYING) break
                val played = t.playbackHeadPosition.toLong() and 0xffffffffL
                if (played >= streamFramesWritten) break
                Thread.sleep(25)
            }
            t.stop()
            t.release()
        } catch (_: Exception) {}
        track = null
        playing = false
        streamFramesWritten = 0L
    }

    fun stop() {
        track?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        track = null
        playing = false
        streamFramesWritten = 0L
    }
}
