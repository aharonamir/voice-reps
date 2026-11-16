package com.voicereps.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TqdmTest {
    @Test fun barFillsProportionallyAndClamps() {
        assertEquals("|█████░░░░░|", Tqdm.bar(0.5))
        assertEquals("|" + "█".repeat(10) + "|", Tqdm.bar(7.0))
        assertEquals("|" + "░".repeat(10) + "|", Tqdm.bar(null))
    }
    @Test fun lineShowsMeasuredTotalsRateAndEta() {
        val l = Tqdm.line("Loading engine", 300.0, 600.0, "MB", 10_000)
        assertTrue(l, l.startsWith("Loading engine  50%|"))
        assertTrue(l, l.contains("300/600 MB"))
        assertTrue(l, l.contains("[00:10<00:10, 30.0 MB/s]"))
    }
    @Test fun approximateProgressShowsRoundedPercentAndNoCountsOrEta() {
        val l = Tqdm.line("Loading engine", 281.0, 663.0, "MB", 11_000, approx = true)
        assertTrue(l, l.startsWith("Loading engine ~40%|"))
        assertFalse(l, l.contains("MB") || l.contains("<"))
        assertTrue(l, l.contains("[00:11]"))
    }
    @Test fun noTotalMeansNoPercentAndNoEta() {
        val l = Tqdm.line("Analysing voice", null, null, "", 4000)
        assertFalse(l, l.contains("%"))
        assertFalse(l, l.contains("<"))
        assertTrue(l, l.contains("[00:04]"))
    }
    @Test fun stepPrefixOnlyForMultiStep() {
        assertTrue(StepProgress("A", stepIndex = 2, stepCount = 3).text().startsWith("[2/3] A"))
        assertTrue(StepProgress("A").text().startsWith("A"))
    }
    @Test fun ioMeterReadsRcharAndFailsSoftly() {
        val f = File.createTempFile("iometer", ".txt").apply { writeText("rchar: 12345\nwchar: 9\n") }
        assertEquals(12345L, IoMeter(f.path).readBytes())
        assertNull(IoMeter("/nonexistent/io").readBytes())
    }
    @Test fun readProgressTracksBytesAndNeverReaches100BeforeTheCallReturns() {
        val f = File.createTempFile("iometer", ".txt").apply { writeText("rchar: 1000\n") }
        val seen = java.util.Collections.synchronizedList(ArrayList<StepProgress>())
        withReadProgress("Loading engine", 10_000_000, 1, 1, IoMeter(f.path), 20, { System.nanoTime() / 1_000_000 }, { seen.add(it) }) {
            f.writeText("rchar: ${1000 + 5_000_000}\n"); Thread.sleep(150)
            f.writeText("rchar: ${1000 + 50_000_000}\n"); Thread.sleep(150) // over-read is clamped
        }
        assertEquals(0.0, seen.first().done!!, 0.0)
        assertTrue(seen.any { it.done!! in 4.9..5.1 })
        assertTrue(seen.all { it.fraction!! <= 0.99 + 1e-9 })
        assertTrue(seen.all { it.approx })
    }
}
