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

    /** Create a streaming track the caller writes chunks to (call stop() to end). */
    fun beginStream(): AudioTrack {
        stop()
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

    fun stop() {
        track?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        track = null
        playing = false
    }
}
