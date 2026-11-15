package com.voicereps.app

import android.media.AudioTrack
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class AudioPlayerControlsTest {
    @Test fun pauseAndResumeChangeTheTrackAndStopReleasesPausedWriter() {
        val p = AudioPlayer()
        val s = p.beginStream()
        assertEquals(AudioTrack.PLAYSTATE_PLAYING, s.track.playState)
        p.pause()
        assertTrue(s.paused)
        assertFalse(p.playing)
        assertEquals(AudioTrack.PLAYSTATE_PAUSED, s.track.playState)
        p.resume()
        assertFalse(s.paused)
        assertTrue(p.playing)
        assertEquals(AudioTrack.PLAYSTATE_PLAYING, s.track.playState)
        p.pause()
        val failure = AtomicReference<Throwable>()
        val writer = Thread { try { p.writeStream(s, FloatArray(4410) { 0.1f }) } catch (t: Throwable) { failure.set(t) } }
        writer.start()
        Thread.sleep(100)
        assertTrue("writer waits rather than dropping paused audio", writer.isAlive)
        p.stop()
        writer.join(1000)
        assertFalse("Stop releases a paused writer promptly", writer.isAlive)
        assertTrue(s.closed)
        assertNotNull(failure.get())
        assertFalse(p.playing)
        // Another Speak uses a fresh track rather than the stopped/paused track.
        val next = p.beginStream()
        assertFalse(next.paused)
        assertFalse(next.closed)
        p.stop()
    }
}
