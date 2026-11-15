package com.voicereps.app

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** Encodes a PCM16 WAV file to MP3 on device with LAME (libvoicerepsmp3.so). */
object Mp3Encoder {
    private const val KBPS = 128
    private const val CHUNK_SAMPLES = 44100 * 2

    init { System.loadLibrary("voicerepsmp3") }

    @JvmStatic private external fun nativeInit(rate: Int, channels: Int, kbps: Int): Long
    @JvmStatic private external fun nativeEncode(h: Long, pcm: ShortArray, n: Int, out: ByteArray): Int
    @JvmStatic private external fun nativeFlush(h: Long, out: ByteArray): Int
    @JvmStatic private external fun nativeClose(h: Long)

    /** Returns true when [mp3] holds a complete encoded file. Deletes a partial [mp3] on failure. */
    fun wavToMp3(wav: File, mp3: File): Boolean {
        var h = 0L
        try {
            RandomAccessFile(wav, "r").use { raf ->
                if (raf.length() <= 44) return false
                val header = ByteArray(44)
                raf.readFully(header)
                val channels = (header[22].toInt() and 0xFF) or ((header[23].toInt() and 0xFF) shl 8)
                val rate = (header[24].toInt() and 0xFF) or ((header[25].toInt() and 0xFF) shl 8) or
                    ((header[26].toInt() and 0xFF) shl 16) or ((header[27].toInt() and 0xFF) shl 24)
                if (channels !in 1..2 || rate <= 0) return false
                h = nativeInit(rate, channels, KBPS)
                if (h == 0L) return false
                val samplesPerChunk = CHUNK_SAMPLES - CHUNK_SAMPLES % channels
                val raw = ByteArray(samplesPerChunk * 2)
                val pcm = ShortArray(samplesPerChunk)
                // LAME worst case: 1.25 * samples + 7200 bytes.
                val out = ByteArray(samplesPerChunk * 5 / 4 + 7200)
                var wrote = 0L
                BufferedOutputStream(FileOutputStream(mp3)).use { os ->
                    while (true) {
                        val got = raf.read(raw)
                        if (got <= 0) break
                        val n = got / 2 - (got / 2) % channels
                        if (n == 0) break
                        for (i in 0 until n) {
                            pcm[i] = ((raw[i * 2].toInt() and 0xFF) or (raw[i * 2 + 1].toInt() shl 8)).toShort()
                        }
                        val r = nativeEncode(h, pcm, n, out)
                        if (r < 0) return false.also { mp3.delete() }
                        os.write(out, 0, r); wrote += r
                    }
                    val f = nativeFlush(h, out)
                    if (f < 0) return false.also { mp3.delete() }
                    os.write(out, 0, f); wrote += f
                }
                if (wrote <= 0) { mp3.delete(); return false }
            }
            return true
        } catch (t: Throwable) {
            mp3.delete()
            return false
        } finally {
            if (h != 0L) nativeClose(h)
        }
    }
}
