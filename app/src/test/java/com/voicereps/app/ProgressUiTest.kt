package com.voicereps.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProgressUiTest {
    private fun render(a: android.app.Activity, name: String, scrollId: Int) {
        val root = a.findViewById<View>(R.id.main_root)
        repeat(2) { root.measure(View.MeasureSpec.makeMeasureSpec(786, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 786, 1600) }
        fun scrolls(v: View): List<ScrollView> = (if (v is ScrollView && v.isShown) listOf(v) else emptyList()) +
            ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { scrolls(g.getChildAt(it)) } } ?: emptyList())
        scrolls(root).forEach { it.fullScroll(View.FOCUS_DOWN) }
        repeat(2) { root.measure(View.MeasureSpec.makeMeasureSpec(786, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 786, 1600) }
        val b = Bitmap.createBitmap(786, 1600, Bitmap.Config.ARGB_8888); root.draw(Canvas(b))
        File("/downloads/frames/$name.png").also { it.parentFile.mkdirs() }.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun speakShowsEngineLoadBufferingAndPlayingProgress() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.nav_speak
        a.supportFragmentManager.executePendingTransactions()
        val f = a.supportFragmentManager.findFragmentByTag("speak") as SpeakFragment
        val busy = SpeakFragment::class.java.getDeclaredMethod("setBusy", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        busy.invoke(f, true)
        val panel = ProgressPanel(f.view!!.findViewById(R.id.speak_progress))
        panel.show(StepProgress("Loading engine", 281.0, 663.0, "MB", 11_000, approx = true))
        a.findViewById<TextView>(R.id.speak_status).text = "Loading voice engine..."
        assertEquals(View.VISIBLE, f.view!!.findViewById<View>(R.id.speak_progress).visibility)
        val bar = f.view!!.findViewById<ProgressBar>(R.id.step_bar)
        assertFalse(bar.isIndeterminate)
        assertTrue(bar.progress.toString(), Math.abs(bar.progress - 423) <= 2)
        render(a, "voice-reps-0.5.0-engine-loading", 0)

        // A real stream and jitter buffer, with a producer that has only delivered 2 s of a 6 s target.
        val player = AudioPlayer()
        val s = player.beginStream(null, BufferMode.LONG, 6000)
        player.writeStream(s, FloatArray(88200) { 0.1f })
        SpeakFragment::class.java.getDeclaredField("activeStream").apply { isAccessible = true }.set(f, s)
        SpeakFragment::class.java.getDeclaredField("generating").apply { isAccessible = true }.setBoolean(f, true)
        val tick = SpeakFragment::class.java.getDeclaredMethod("tick").apply { isAccessible = true }
        Thread.sleep(150)
        tick.invoke(f)
        val text = f.view!!.findViewById<TextView>(R.id.step_text).text.toString()
        assertTrue(text, text.startsWith("Buffering"))
        assertTrue(text, text.contains("2.0/6.0 s"))
        render(a, "voice-reps-0.5.0-buffering", 0)

        player.writeStream(s, FloatArray(44100 * 5) { 0.1f })
        Thread.sleep(300)
        tick.invoke(f)
        val playing = f.view!!.findViewById<TextView>(R.id.step_text).text.toString()
        assertTrue(playing, playing.startsWith("Playing"))
        render(a, "voice-reps-0.5.0-playing", 0)
        player.stop()
    }

    @Test fun recordShowsVoiceCreationStages() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.nav_record
        a.supportFragmentManager.executePendingTransactions()
        val rf = a.supportFragmentManager.fragments.first { it is RecordFragment && it.isVisible }
        val rv = rf.view!!
        rv.findViewById<View>(R.id.consent_card).visibility = View.GONE
        rv.findViewById<View>(R.id.record_card).visibility = View.VISIBLE
        val panel = ProgressPanel(rv.findViewById(R.id.create_progress))
        panel.show(StepProgress("Loading speaker encoder", 31.0, 45.0, "MB", 3_000, 1, 2, approx = true))
        rv.findViewById<TextView>(R.id.record_status).text = "Building voice profile for \"Amir\"..."
        render(a, "voice-reps-0.5.0-voice-load", 0)
        panel.show(StepProgress("Analysing voice", elapsedMs = 4_000, stepIndex = 2, stepCount = 2))
        assertTrue(rv.findViewById<ProgressBar>(R.id.step_bar).isIndeterminate)
        render(a, "voice-reps-0.5.0-voice-analysing", 0)
    }
}
