package com.voicereps.app

import kotlin.math.abs

/** Also used by regression tests without an Android audio device. */
internal class PcmStats {
    var frames = 0L; private set
    var peak = 0f; private set
    fun add(pcm: FloatArray) {
        for (v in pcm) {
            check(v.isFinite()) { "The engine returned invalid audio. Try Speak again." }
            peak = maxOf(peak, abs(v))
        }
        frames += pcm.size
    }
    fun requireAudible() {
        check(frames > 0) { "The engine returned no audio. Try Speak again." }
        check(peak > 0.00001f) { "The engine returned silent audio. Try Speak again." }
    }
}
