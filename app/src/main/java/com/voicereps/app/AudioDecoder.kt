package com.voicereps.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/** Decodes an audio Uri to mono float PCM at 44.1 kHz (linear resample). Up to maxSeconds. */
object AudioDecoder {

    fun decodeToFloat(ctx: Context, uri: Uri, maxSeconds: Int = 60): FloatArray? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(ctx, uri, null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; format = f; break }
            }
            if (track < 0 || format == null) return null
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val inRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val pcm = ArrayList<Float>(inRate * maxSeconds)
            val info = MediaCodec.BufferInfo()
            val maxSamples = 44100 * maxSeconds
            var done = false
            while (!done && pcm.size < maxSamples * channels) {
                val inIdx = codec.dequeueInputBuffer(10000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    else { codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0); extractor.advance() }
                }
                var outIdx = codec.dequeueOutputBuffer(info, 10000)
                while (outIdx >= 0) {
                    val out = codec.getOutputBuffer(outIdx)!!
                    out.order(ByteOrder.LITTLE_ENDIAN)
                    val sb = out.asShortBuffer()
                    while (sb.remaining() >= channels && pcm.size < maxSamples * channels) {
                        var sum = 0f
                        for (c in 0 until channels) sum += sb.get() / 32768f
                        pcm.add(sum / channels)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { done = true; break }
                    outIdx = codec.dequeueOutputBuffer(info, 0)
                }
            }
            codec.stop(); codec.release()
            val mono = pcm.toFloatArray()
            if (inRate == 44100) mono else resampleLinear(mono, inRate, 44100)
        } catch (e: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    fun decodeWavFile(file: java.io.File): FloatArray? = try {
        val bytes = file.readBytes()
        if (bytes.size < 44) null
        else {
            val n = (bytes.size - 44) / 2
            FloatArray(n) { i ->
                val lo = bytes[44 + i * 2].toInt() and 0xFF
                val hi = bytes[44 + i * 2 + 1].toInt()
                ((hi shl 8) or lo).toShort() / 32768f
            }
        }
    } catch (e: Exception) { null }

    private fun resampleLinear(src: FloatArray, from: Int, to: Int): FloatArray {
        if (src.isEmpty()) return src
        val outLen = src.size.toLong() * to / from
        return FloatArray(outLen.toInt()) { i ->
            val pos = i.toDouble() * from / to
            val i0 = pos.toInt().coerceIn(0, src.size - 1)
            val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            val frac = (pos - i0).toFloat()
            src[i0] * (1 - frac) + src[i1] * frac
        }
    }
}
