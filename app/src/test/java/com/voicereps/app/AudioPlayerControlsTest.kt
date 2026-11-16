package com.voicereps.app

import android.media.AudioTrack
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34]) // Robolectric's API 28 AudioTrack shadow lacks getPlaybackHeadPosition support
class AudioPlayerControlsTest {
    @Test fun pauseAndResumeChangeTheTrackAndStopReleasesPausedWriter() {
        val p = AudioPlayer()
        val s = p.beginStream(null, BufferMode.AUTO, 300)
        // Jitter buffer: output starts only after the target amount of audio is queued.
        assertNotEquals(AudioTrack.PLAYSTATE_PLAYING, s.track.playState)
        p.writeStream(s, FloatArray(44100) { 0.1f })
        val until = System.currentTimeMillis() + 2000
        while (s.track.playState != AudioTrack.PLAYSTATE_PLAYING && System.currentTimeMillis() < until) Thread.sleep(10)
        assertEquals("err=${s.writerError} stats=${s.snapshot()}", AudioTrack.PLAYSTATE_PLAYING, s.track.playState)
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
        // Fill the bounded buffer completely so the producer must wait (backpressure) while paused.
        val writer = Thread { try { while (true) p.writeStream(s, FloatArray(44100 * 5) { 0.1f }) } catch (t: Throwable) { failure.set(t) } }
        writer.start()
        Thread.sleep(300)
        assertTrue("producer waits rather than dropping paused audio", writer.isAlive)
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
