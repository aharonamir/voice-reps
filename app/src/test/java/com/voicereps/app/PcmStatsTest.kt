package com.voicereps.app

import org.junit.Assert.*
import org.junit.Test

class PcmStatsTest {
    @Test fun rejectsNoChunks() { assertThrows(IllegalStateException::class.java) { PcmStats().requireAudible() } }
    @Test fun rejectsSilence() {
        val stats = PcmStats(); stats.add(FloatArray(4410))
        assertThrows(IllegalStateException::class.java) { stats.requireAudible() }
    }
    @Test fun rejectsNonFinite() {
        assertThrows(IllegalStateException::class.java) { PcmStats().add(floatArrayOf(Float.NaN)) }
        assertThrows(IllegalStateException::class.java) { PcmStats().add(floatArrayOf(Float.POSITIVE_INFINITY)) }
    }
    @Test fun acceptsAudibleChunks() {
        val stats = PcmStats(); stats.add(floatArrayOf(0f, .02f, -.03f)); stats.requireAudible()
        assertEquals(3L, stats.frames); assertEquals(.03f, stats.peak, 0f)
    }
}
