package com.voicereps.app

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/** Records mono 44.1 kHz PCM16 to a WAV file, with a live level callback. Max 60 s. */
class AudioRecorder(private val outFile: File) {

    private val sampleRate = 44100
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    var maxSeconds = 60

    val isRecording get() = running.get()

    fun start(onLevel: (Float) -> Unit, onMaxReached: () -> Unit) {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
        running.set(true)
        rec.startRecording()
        thread = Thread {
            val buf = ShortArray(2048)
            val raf = RandomAccessFile(outFile, "rw")
            raf.write(ByteArray(44)) // header placeholder
            var total = 0
            val maxSamples = maxSeconds * sampleRate
            while (running.get() && total < maxSamples) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    val bytes = ByteArray(n * 2)
                    var peak = 0
                    for (i in 0 until n) {
                        val v = buf[i].toInt()
                        if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
                        bytes[i * 2] = (v and 0xFF).toByte()
                        bytes[i * 2 + 1] = (v shr 8 and 0xFF).toByte()
                    }
                    raf.write(bytes)
                    total += n
                    onLevel(peak / 32768f)
                }
            }
            rec.stop(); rec.release()
            writeWavHeader(raf, total)
            raf.close()
            running.set(false)
            if (total >= maxSamples) onMaxReached()
        }.also { it.start() }
    }

    fun stop() { running.set(false); thread?.join(2000) }

    private fun writeWavHeader(raf: RandomAccessFile, samples: Int) {
        val dataLen = samples * 2
        raf.seek(0)
        raf.writeBytes("RIFF"); raf.writeIntLE(36 + dataLen); raf.writeBytes("WAVEfmt ")
        raf.writeIntLE(16); raf.writeShortLE(1); raf.writeShortLE(1)
        raf.writeIntLE(sampleRate); raf.writeIntLE(sampleRate * 2)
        raf.writeShortLE(2); raf.writeShortLE(16)
        raf.writeBytes("data"); raf.writeIntLE(dataLen)
    }

    private fun RandomAccessFile.writeIntLE(v: Int) =
        write(byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte(), (v shr 16 and 0xFF).toByte(), (v shr 24 and 0xFF).toByte()))
    private fun RandomAccessFile.writeShortLE(v: Int) =
        write(byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte()))
}
