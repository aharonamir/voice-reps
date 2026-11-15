package com.voicereps.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Runs only when a host build of libvoicerepsmp3 is on java.library.path (see native/mp3). */
class Mp3EncoderTest {
    private fun wav(file: File, seconds: Int, channels: Int) {
        val rate = 44100
        val n = rate * seconds * channels
        val data = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (12000 * Math.sin(2 * Math.PI * 440 * (i / channels) / rate)).toInt().toShort().toInt()
            data[i * 2] = v.toByte(); data[i * 2 + 1] = (v shr 8).toByte()
        }
        fun le(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        val h = "RIFF".toByteArray() + le(36 + data.size) + "WAVEfmt ".toByteArray() + le(16) +
            byteArrayOf(1, 0, channels.toByte(), 0) + le(rate) + le(rate * 2 * channels) +
            byteArrayOf((2 * channels).toByte(), 0, 16, 0) + "data".toByteArray() + le(data.size)
        file.writeBytes(h + data)
    }

    private fun encode(channels: Int) {
        val ok = try { Mp3Encoder.wavToMp3(File("/nonexistent"), File("/tmp/x.mp3")); true } catch (e: Throwable) { println("MP3LOADFAIL $e"); false }
        assumeTrue(ok)
        val w = File.createTempFile("mp3t", ".wav"); val m = File.createTempFile("mp3t", ".mp3")
        wav(w, 3, channels)
        assertTrue(Mp3Encoder.wavToMp3(w, m))
        val b = m.readBytes()
        assertTrue(b.size > 10000)
        // MPEG-1 Layer III frame sync: 0xFFFB / 0xFFFA, or ID3 tag first
        val i = if (b[0] == 'I'.code.toByte()) b.indexOfFirst { it == 0xFF.toByte() } else 0
        assertEquals(0xFF, b[i].toInt() and 0xFF)
        assertEquals(0xE0, b[i + 1].toInt() and 0xE0)
        println("MP3TEST ch=$channels bytes=${b.size}")
    }

    @Test fun monoWavBecomesMp3() = encode(1)
    @Test fun stereoWavBecomesMp3() = encode(2)
}
