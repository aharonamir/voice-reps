package com.voicereps.app

import org.junit.Assert.*
import org.junit.Test

/** Deterministic virtual-time simulation of a slow/bursty generator feeding a real-time output. Not a phone measurement. */
class JitterBufferTest {
    private val rate = 1000 // 1 kHz keeps the numbers readable; the policy only uses ratios.

    private class FakeSink(val rate: Int, val capacity: Int = 1000) : PcmSink {
        var now = 0L // ms, advanced by the simulation
        var playing = false
        var buffered = 0L
        var played = 0L
        var lastTick = 0L
        val out = ArrayList<Float>()
        var stalls = 0 // moments where playing with an empty output
        fun advance(to: Long) {
            val dt = to - lastTick; lastTick = to
            if (playing) {
                val can = minOf(buffered, dt * rate / 1000)
                buffered -= can; played += can
                if (can < dt * rate / 1000) stalls++
            }
        }
        override fun write(pcm: FloatArray, offset: Int, count: Int): Int {
            val n = minOf(count.toLong(), capacity - buffered).toInt()
            for (i in 0 until n) out.add(pcm[offset + i])
            buffered += n; return n
        }
        override fun playedFrames() = played
        override fun play() { playing = true }
        override fun pause() { playing = false }
    }

    /** Producer yields `chunkMs` of audio every `periodMs` of wall time, `chunks` times. Returns (sink, buffer). */
    private fun run(mode: BufferMode, chunkMs: Int, periodMs: Int, chunks: Int, initialMs: Int = mode.initialMs): Pair<FakeSink, JitterBuffer> {
        val sink = FakeSink(rate)
        val buf = JitterBuffer(sink, rate, mode, initialMs, clock = { sink.now }, maxTargetMs = 20000)
        var seq = 0f
        var produced = 0
        var t = 0L
        val end = chunks.toLong() * periodMs + 200000
        while (t < end) {
            sink.advance(t); sink.now = t
            if (produced < chunks && t % periodMs == 0L) {
                buf.offer(FloatArray(chunkMs * rate / 1000) { seq++ })
                produced++
                if (produced == chunks) buf.markDone()
            }
            if (buf.step()) break
            t += 10
        }
        sink.advance(t)
        return sink to buf
    }

    @Test fun fasterThanRealTimeNeverStallsAndKeepsEverySampleInOrder() {
        val (sink, buf) = run(BufferMode.AUTO, chunkMs = 1000, periodMs = 500, chunks = 20)
        assertEquals(0, buf.underruns)
        assertEquals(20000, sink.out.size)
        assertTrue("samples in order", sink.out.indices.all { sink.out[it] == it.toFloat() })
        assertEquals(JitterBuffer.State.DONE, buf.state)
    }

    @Test fun slowGeneratorRefillsInsteadOfPlayingGaps() {
        // 0.5x real time: 500 ms of audio per 1000 ms of wall time.
        val (sink, buf) = run(BufferMode.AUTO, chunkMs = 500, periodMs = 1000, chunks = 40)
        assertTrue("slow generator must trigger refills", buf.underruns >= 1)
        assertEquals("no audio dropped or reordered", 20000, sink.out.size)
        assertTrue(sink.out.indices.all { sink.out[it] == it.toFloat() })
        assertEquals("output never ran dry while playing (it pauses first)", 0, sink.stalls)
        assertEquals(20000L, sink.played)
    }

    @Test fun targetGrowsAfterEachRefillAndIsCapped() {
        val (_, buf) = run(BufferMode.AUTO, chunkMs = 300, periodMs = 1000, chunks = 120)
        assertTrue(buf.underruns >= 2)
        assertTrue(buf.currentTargetMs() <= 20000)
        assertTrue(buf.currentTargetMs() >= 3000)
    }

    @Test fun burstyStallIsAbsorbedByALargerBuffer() {
        // Fast producer (2x) with one 3 s hang in the middle.
        fun scenario(initial: Int): Int {
            val sink = FakeSink(rate)
            val buf = JitterBuffer(sink, rate, BufferMode.AUTO, initial, clock = { sink.now })
            var t = 0L; var produced = 0
            while (t < 120000) {
                sink.advance(t); sink.now = t
                val hang = t in 8000 until 11000
                if (!hang && t % 1000 == 0L && produced < 20) {
                    buf.offer(FloatArray(1000)); produced++
                    if (produced == 20) buf.markDone()
                }
                if (buf.step()) break
                t += 10
            }
            return buf.underruns
        }
        assertTrue("short buffer stalls on a 3 s hang", scenario(800) >= 1)
        assertEquals("a 5 s buffer rides it out", 0, scenario(5000))
    }

    @Test fun wholeTextModeWaitsForGenerationThenNeverStalls() {
        val (sink, buf) = run(BufferMode.WHOLE, chunkMs = 500, periodMs = 1000, chunks = 20)
        assertEquals(0, buf.underruns)
        assertEquals(10000, sink.out.size)
        assertEquals(0, sink.stalls)
    }

    @Test fun pausingOutputDoesNotLoseQueuedAudio() {
        val sink = FakeSink(rate)
        val buf = JitterBuffer(sink, rate, BufferMode.AUTO, 300, clock = { sink.now })
        buf.offer(FloatArray(2000) { it.toFloat() }); buf.markDone()
        buf.step(); assertTrue(sink.playing)
        buf.onResume() // idempotent while playing
        var t = 0L
        while (!buf.step() && t < 10000) { t += 10; sink.advance(t); sink.now = t }
        assertEquals(2000, sink.out.size)
    }

    @Test fun realTimeFactorExcludesTimeBlockedOnAFullBuffer() {
        val sink = FakeSink(rate)
        val buf = JitterBuffer(sink, rate, BufferMode.AUTO, 800, clock = { sink.now })
        sink.now = 0; buf.offer(FloatArray(1000))
        sink.now = 4000; buf.offer(FloatArray(1000))
        assertEquals(0.5, buf.stats().realTimeFactor!!, 0.01)
        buf.addBlockedMs(2000)
        assertEquals(1.0, buf.stats().realTimeFactor!!, 0.01)
    }
}
