package com.voicereps.app

/** How much audio to hold back before and during playback. */
enum class BufferMode(val label: String, val initialMs: Int, val aheadCapSeconds: Int) {
    AUTO("Auto (adapts to this phone)", 800, 30),
    LONG("Extra buffer (6 s)", 6000, 60),
    WHOLE("Whole text first", Int.MAX_VALUE, 600);

    companion object {
        fun fromName(name: String?) = values().firstOrNull { it.name == name } ?: AUTO
    }
}

/** Non-blocking audio output, so the buffering policy can be tested without Android. */
interface PcmSink {
    /** Accepts up to [count] frames starting at [offset]; returns frames taken (0 when full). */
    fun write(pcm: FloatArray, offset: Int, count: Int): Int
    /** Frames the output has actually played so far. */
    fun playedFrames(): Long
    fun play()
    fun pause()
}

data class JitterStats(
    val state: JitterBuffer.State,
    val aheadMs: Long,
    val targetMs: Long,
    val underruns: Int,
    val producedMs: Long,
    val playedMs: Long,
    /** Audio produced per second of generation time, excluding time spent waiting on a full buffer. Null until measurable. */
    val realTimeFactor: Double?,
    val rebuffering: Boolean,
)

/**
 * Jitter buffer between a speech generator (bursty, may be slower than real time on weak phones)
 * and the audio output. Playback starts only after [targetMs] of audio is queued. If the queue
 * runs dry before generation has finished, playback pauses cleanly (no click, no dropped audio),
 * raises the target and resumes after refilling. It cannot make generation faster: when the phone
 * generates slower than real time, this only trades one long wait for a few shorter, rarer ones.
 */
class JitterBuffer(
    private val sink: PcmSink,
    private val sampleRate: Int,
    val mode: BufferMode = BufferMode.AUTO,
    initialMs: Int = mode.initialMs,
    private val clock: () -> Long,
    private val maxTargetMs: Int = 20000,
    private val guardMs: Int = 80,
) {
    enum class State { BUFFERING, PLAYING, DONE }

    private val lock = Any()
    private val queue = ArrayDeque<FloatArray>()
    private var headOffset = 0
    private var queued = 0L
    private var produced = 0L
    private var written = 0L
    private var done = false
    private var firstOfferAt = -1L
    private var blockedMs = 0L
    private var target: Long = if (initialMs == Int.MAX_VALUE) Long.MAX_VALUE else frames(initialMs.coerceAtLeast(300))
    private var rebuffering = false
    var state = State.BUFFERING; private set
    var underruns = 0; private set
    val maxAheadFrames: Long = mode.aheadCapSeconds.toLong() * sampleRate

    private fun frames(ms: Int) = ms.toLong() * sampleRate / 1000
    private fun ms(frames: Long) = frames * 1000 / sampleRate

    fun offer(pcm: FloatArray) = synchronized(lock) {
        if (pcm.isEmpty()) return@synchronized
        if (firstOfferAt < 0) firstOfferAt = clock()
        queue.addLast(pcm); queued += pcm.size; produced += pcm.size
    }

    fun markDone() = synchronized(lock) { done = true }
    fun addBlockedMs(ms: Long) = synchronized(lock) { blockedMs += ms }
    fun queuedFrames() = synchronized(lock) { queued }
    fun isFull() = synchronized(lock) { queued >= maxAheadFrames }
    fun producedFrames() = synchronized(lock) { produced }
    fun writtenFrames() = synchronized(lock) { written }
    fun isDrained() = synchronized(lock) { done && queued == 0L }
    fun currentTargetMs(): Long = synchronized(lock) { if (target == Long.MAX_VALUE) Long.MAX_VALUE else ms(target) }

    /** Called after the user resumes: restart output when the buffer is in its playing state. */
    fun onResume() = synchronized(lock) { if (state == State.PLAYING) sink.play() }

    /** One non-blocking pass of the writer loop. Returns true when everything has been played out. */
    fun step(): Boolean = synchronized(lock) {
        if (state == State.BUFFERING) {
            val ahead = queued + (written - sink.playedFrames())
            if (ahead >= target || queued >= maxAheadFrames || (done && ahead > 0)) {
                sink.play(); state = State.PLAYING; rebuffering = false
            } else if (done) state = State.DONE
        }
        if (state == State.PLAYING) {
            feed()
            val played = sink.playedFrames()
            val inOutput = (written - played).coerceAtLeast(0)
            if (done && queued == 0L && inOutput == 0L) state = State.DONE
            else if (!done && queued + inOutput <= frames(guardMs)) {
                sink.pause(); underruns++; rebuffering = true
                val grown = if (target == Long.MAX_VALUE) target else (target * 2).coerceAtLeast(frames(3000)).coerceAtMost(frames(maxTargetMs))
                target = grown
                state = State.BUFFERING
            }
        }
        state == State.DONE
    }

    private fun feed() {
        while (queue.isNotEmpty()) {
            val head = queue.first()
            val n = sink.write(head, headOffset, head.size - headOffset)
            if (n <= 0) break
            headOffset += n; written += n; queued -= n
            if (headOffset >= head.size) { queue.removeFirst(); headOffset = 0 }
        }
    }

    fun stats(): JitterStats = synchronized(lock) {
        val played = sink.playedFrames().coerceAtMost(written)
        val elapsed = if (firstOfferAt < 0) 0 else clock() - firstOfferAt - blockedMs
        JitterStats(
            state, ms(queued + (written - played).coerceAtLeast(0)),
            if (target == Long.MAX_VALUE) -1 else ms(target), underruns, ms(produced), ms(played),
            if (elapsed >= 1500 && produced > 0) (ms(produced).toDouble() / elapsed) else null,
            rebuffering,
        )
    }
}
