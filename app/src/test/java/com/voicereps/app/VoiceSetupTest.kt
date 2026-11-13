package com.voicereps.app

import android.content.Context
import android.widget.Spinner
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VoiceSetupTest {

    private lateinit var ctx: Context
    private lateinit var store: VoiceStore

    @Before fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        File(ctx.filesDir, "voices").deleteRecursively()
        store = VoiceStore(ctx)
        VoiceSetup.embedder = { _, pcm -> FloatArray(192) { pcm.firstOrNull() ?: 0f } }
    }

    private fun fakeWav(seed: Int): File {
        val f = File(ctx.cacheDir, "test_$seed.wav")
        val samples = ShortArray(44100) { (((it + seed) % 200) - 100).toShort() }
        val bytes = ByteArray(44 + samples.size * 2)
        for (i in samples.indices) {
            bytes[44 + i * 2] = (samples[i].toInt() and 0xFF).toByte()
            bytes[44 + i * 2 + 1] = (samples[i].toInt() shr 8 and 0xFF).toByte()
        }
        f.writeBytes(bytes)
        return f
    }

    private fun awaitFinish(ctx: Context, onReady: (() -> Unit)? = null) {
        val latch = CountDownLatch(1)
        VoiceSetup.finishPending(ctx, onReady = onReady, onDone = { latch.countDown() })
        assertTrue("finishPending timed out", latch.await(30, TimeUnit.SECONDS))
    }

    @Test fun interruptedVoiceSetupIsFinishedAutomatically() {
        val readyVoice = store.addPending("First", "record", fakeWav(1))
        store.complete(readyVoice, FloatArray(192) { 1f })
        val stuck = store.addPending("Second", "record", fakeWav(2))
        assertTrue(store.list().first { it.id == readyVoice.id }.ready)
        assertFalse(store.list().first { it.id == stuck.id }.ready)

        awaitFinish(ctx)

        val after = store.list()
        assertEquals(2, after.size)
        assertTrue("pending voice became ready", after.first { it.id == stuck.id }.ready)
    }

    @Test fun failedEmbeddingIsNotRetriedWithinOneProcess() {
        val stuck = store.addPending("Bad", "record", fakeWav(3))
        var calls = 0
        VoiceSetup.embedder = { _, _ -> calls++; null }

        awaitFinish(ctx)
        awaitFinish(ctx)

        assertEquals("one attempt per voice per process", 1, calls)
        assertFalse(store.list().first { it.id == stuck.id }.ready)
    }

    @Test fun speakPickerListsEveryReadyVoice() {
        val a = store.addPending("First", "record", fakeWav(4))
        store.complete(a, FloatArray(192) { 1f })
        val b = store.addPending("Second", "record", fakeWav(5))
        store.complete(b, FloatArray(192) { 2f })

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav)
            .selectedItemId = R.id.nav_speak
        activity.supportFragmentManager.executePendingTransactions()
        val spinner = activity.findViewById<Spinner>(R.id.voice_spinner)
        val labels = (0 until spinner.adapter.count).map { spinner.adapter.getItem(it).toString() }
        assertEquals(listOf("Default voice", "Second", "First"), labels)
    }
}
