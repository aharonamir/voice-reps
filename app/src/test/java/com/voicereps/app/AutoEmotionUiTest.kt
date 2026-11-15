package com.voicereps.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.chip.ChipGroup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], qualifiers = "w393dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutoEmotionUiTest {
    @Test fun checkboxDisablesAllManualControlsAndPlaybackControlsAreReachable() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = controller.get()
        a.findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.nav_speak
        a.supportFragmentManager.executePendingTransactions()
        val f = a.supportFragmentManager.findFragmentByTag("speak") as SpeakFragment
        val auto = a.findViewById<CheckBox>(R.id.auto_emotions)
        auto.isChecked = false
        val sliders = listOf(R.id.pitch_bar, R.id.expression_bar, R.id.pace_bar)
        assertTrue(sliders.all { a.findViewById<SeekBar>(it).isEnabled })
        auto.performClick()
        assertTrue(auto.isChecked)
        assertTrue(sliders.none { a.findViewById<SeekBar>(it).isEnabled })
        val chips = a.findViewById<ChipGroup>(R.id.emotion_chips)
        assertTrue((0 until chips.childCount).none { chips.getChildAt(it).isEnabled })
        val ctl = SpeakFragment::class.java.getDeclaredMethod("controlsString", String::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        SpeakFragment::class.java.getDeclaredField("pitch").apply { isAccessible = true }.setDouble(f, 2.0)
        assertEquals("emotions.happy=2.0", ctl.invoke(f, "Happy", false))
        assertTrue((ctl.invoke(f, "Happy", true) as String).contains("delivery.pitch=2.0"))
        fun render(name: String) {
            auto.jumpDrawablesToCurrentState()
            val root = a.findViewById<View>(R.id.main_root)
            repeat(2) { root.measure(View.MeasureSpec.makeMeasureSpec(786, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 786, 1600) }
            val b = Bitmap.createBitmap(786,1600,Bitmap.Config.ARGB_8888); root.draw(Canvas(b))
            File("/downloads/api${android.os.Build.VERSION.SDK_INT}-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
        render("voice-reps-0.4.0-auto")
        val busy = SpeakFragment::class.java.getDeclaredMethod("setBusy", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        busy.invoke(f, true)
        val pause = a.findViewById<Button>(R.id.pause_button)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.playback_controls).visibility)
        assertTrue(a.findViewById<Button>(R.id.stop_button).isEnabled)
        pause.isEnabled = true // simulate first streaming audio without a real TTS model
        pause.performClick(); assertEquals("Resume", pause.text.toString())
        a.findViewById<TextView>(R.id.auto_emotion_status).text = "Detected: joy → Happy"
        render("voice-reps-0.4.0-paused")
        val root = a.findViewById<android.view.ViewGroup>(R.id.main_root)
        if (android.os.Build.VERSION.SDK_INT < 30) {
            val platform = android.view.WindowInsets::class.java.getConstructor(android.graphics.Rect::class.java)
                .newInstance(android.graphics.Rect(0, 48, 0, 640))
            root.dispatchApplyWindowInsets(platform)
        } else {
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root,
                androidx.core.view.WindowInsetsCompat.Builder()
                    .setInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars(), androidx.core.graphics.Insets.of(0,48,0,48))
                    .setInsets(androidx.core.view.WindowInsetsCompat.Type.ime(), androidx.core.graphics.Insets.of(0,0,0,640))
                    .setVisible(androidx.core.view.WindowInsetsCompat.Type.ime(), true).build())
        }
        render("voice-reps-0.4.0-keyboard-before-scroll")
        val row = a.findViewById<View>(R.id.playback_controls)
        row.requestRectangleOnScreen(android.graphics.Rect(0,0,row.width,row.height), true)
        render("voice-reps-0.4.0-paused-keyboard")
        val rect = android.graphics.Rect(0,0,row.width,row.height)
        root.offsetDescendantRectToMyCoords(row, rect)
        assertTrue("playback controls remain above the keyboard and nav", rect.bottom <= a.findViewById<View>(R.id.bottom_nav).top)
        pause.performClick(); assertEquals("Pause", pause.text.toString())
        a.findViewById<Button>(R.id.stop_button).performClick()
        assertFalse(pause.isEnabled)
        assertFalse(a.findViewById<Button>(R.id.stop_button).isEnabled)
        busy.invoke(f, false)
        auto.performClick()
        assertTrue(sliders.all { a.findViewById<SeekBar>(it).isEnabled })
        controller.pause().stop().destroy()
    }
}
