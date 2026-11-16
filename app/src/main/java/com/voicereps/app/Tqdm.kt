package com.voicereps.app

import java.io.File

/**
 * tqdm-style text progress. Every number comes from something measured (bytes read, sections
 * done, audio seconds buffered, elapsed time). When no measured total exists the bar is left
 * empty ("indeterminate") and only the elapsed time moves; no percentage is invented.
 */
object Tqdm {
    private const val FULL = '█'
    private const val EMPTY = '░'

    fun bar(fraction: Double?, width: Int = 10): String {
        if (fraction == null || fraction.isNaN()) return "|" + EMPTY.toString().repeat(width) + "|"
        val f = fraction.coerceIn(0.0, 1.0)
        val filled = (f * width).toInt().coerceIn(0, width)
        return "|" + FULL.toString().repeat(filled) + EMPTY.toString().repeat(width - filled) + "|"
    }

    fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    /** "Loading engine 42%|████░░░░| 280/663 MB [00:11<00:15, 25.4 MB/s]" - ETA only with a measured total. */
    fun line(desc: String, done: Double?, total: Double?, unit: String, elapsedMs: Long): String {
        val frac = if (done != null && total != null && total > 0) (done / total).coerceIn(0.0, 1.0) else null
        val sb = StringBuilder(desc)
        if (frac != null) sb.append(" %3d%%".format((frac * 100).toInt()))
        sb.append(bar(frac))
        if (done != null && total != null) sb.append("\n").append("%s/%s %s".format(num(done), num(total), unit))
        val rate = if (done != null && elapsedMs >= 1000 && done > 0) done / (elapsedMs / 1000.0) else null
        sb.append(if (done != null && total != null) " [" else "  [").append(clock(elapsedMs))
        if (frac != null && rate != null && rate > 0 && total != null && done != null && done < total) {
            sb.append('<').append(clock(((total - done) / rate * 1000).toLong()))
        }
        if (rate != null) sb.append(", ").append("%s %s/s".format(num(rate), unit))
        return sb.append(']').toString()
    }

    private fun num(v: Double) = if (v >= 100) "%.0f".format(v) else "%.1f".format(v)
}

/**
 * Bytes this process has read so far (Linux /proc/self/io "rchar"). The native model loader reads
 * the model files with fread, so the growth of this counter while loading is a real progress
 * measure. Null when the platform does not expose it; callers then show stage + elapsed only.
 */
class IoMeter(private val path: String = "/proc/self/io") {
    fun readBytes(): Long? = try {
        File(path).useLines { lines ->
            lines.firstOrNull { it.startsWith("rchar:") }?.substringAfter(':')?.trim()?.toLongOrNull()
        }
    } catch (_: Throwable) { null }
}

/** Snapshot sent to the UI by a long step (engine load, voice creation, generation). */
data class StepProgress(
    val stage: String,
    val done: Double? = null,
    val total: Double? = null,
    val unit: String = "",
    val elapsedMs: Long = 0,
    val stepIndex: Int = 0,
    val stepCount: Int = 0,
) {
    val fraction: Double? get() = if (done != null && total != null && total > 0) (done / total).coerceIn(0.0, 1.0) else null
    fun text(): String {
        val prefix = if (stepCount > 1) "[$stepIndex/$stepCount] " else ""
        return prefix + Tqdm.line(stage, done, total, unit, elapsedMs)
    }
}

/**
 * Runs [block] while a poller reports bytes read (relative to the start) against [totalBytes].
 * Progress is capped below 100% until [block] returns, because the file reads are followed by
 * in-memory setup that has no counter.
 */
fun <T> withReadProgress(
    stage: String, totalBytes: Long, stepIndex: Int, stepCount: Int,
    meter: IoMeter = IoMeter(), intervalMs: Long = 200,
    clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    report: ((StepProgress) -> Unit)?, block: () -> T,
): T {
    if (report == null) return block()
    val start = clock()
    val base = meter.readBytes()
    report(StepProgress(stage, if (base != null) 0.0 else null, if (base != null) totalBytes / 1e6 else null, "MB", 0, stepIndex, stepCount))
    val stop = java.util.concurrent.atomic.AtomicBoolean(false)
    val poller = Thread {
        while (!stop.get()) {
            try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { break }
            if (stop.get()) break
            val now = meter.readBytes()
            val readMb = if (base != null && now != null) ((now - base).coerceIn(0, totalBytes)).toDouble() / 1e6 else null
            // Cap at 99%: the last in-memory setup step has no counter.
            val shown = readMb?.coerceAtMost(totalBytes / 1e6 * 0.99)
            report(StepProgress(stage, shown, if (shown != null) totalBytes / 1e6 else null, "MB", clock() - start, stepIndex, stepCount))
        }
    }
    poller.isDaemon = true
    poller.start()
    try { return block() } finally {
        stop.set(true); poller.interrupt()
    }
}

/** Reports elapsed time for a step that has no measurable total (no percentage is shown). */
fun <T> withElapsed(
    stage: String, stepIndex: Int, stepCount: Int, intervalMs: Long = 250,
    clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    report: ((StepProgress) -> Unit)?, block: () -> T,
): T {
    if (report == null) return block()
    val start = clock()
    report(StepProgress(stage, stepIndex = stepIndex, stepCount = stepCount))
    val stop = java.util.concurrent.atomic.AtomicBoolean(false)
    val ticker = Thread {
        while (!stop.get()) {
            try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { break }
            if (!stop.get()) report(StepProgress(stage, elapsedMs = clock() - start, stepIndex = stepIndex, stepCount = stepCount))
        }
    }
    ticker.isDaemon = true; ticker.start()
    try { return block() } finally { stop.set(true); ticker.interrupt() }
}
