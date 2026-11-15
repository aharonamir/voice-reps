package com.voicereps.app

import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.robolectric.annotation.GraphicsMode
import android.widget.EditText
import android.widget.Spinner
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w393dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReturnToAppTest {
    @Test fun recreatedActivityHasExactlyOneSpeakScreen() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        var activity = controller.get()
        activity.findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.nav_speak
        activity.supportFragmentManager.executePendingTransactions()
        activity.findViewById<EditText>(R.id.speak_input).setText("Hello again")
        repeat(3) {
            controller.recreate()
            activity = controller.get()
            val fm = activity.supportFragmentManager
            assertEquals(3, fm.fragments.size)
            assertEquals(1, fm.fragments.count { it is SpeakFragment })
            assertFalse(fm.findFragmentByTag("speak")!!.isHidden)
            assertEquals(R.id.nav_speak, activity.findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId)
            assertEquals("Hello again", activity.findViewById<EditText>(R.id.speak_input).text.toString())
        }
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(786, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 786, 1600)
        val bitmap = Bitmap.createBitmap(786, 1600, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File("/downloads").mkdirs()
        File("/downloads/voice-reps-0.1.3-speak.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.pause().stop().destroy()
    }

    @Test fun playbackErrorIsVisibleAndLogged() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = controller.get()
        a.findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.nav_speak
        a.supportFragmentManager.executePendingTransactions()
        val fragment = a.supportFragmentManager.findFragmentByTag("speak") as SpeakFragment
        val method = SpeakFragment::class.java.getDeclaredMethod("reportError", Int::class.javaPrimitiveType,
            android.content.Context::class.java, String::class.java, Throwable::class.java)
        method.isAccessible = true
        val operationField = SpeakFragment::class.java.getDeclaredField("operation").apply { isAccessible = true }
        val operationId = (operationField.get(fragment) as java.util.concurrent.atomic.AtomicInteger).get()
        method.invoke(fragment, operationId, a.applicationContext, "speak regression", IllegalStateException("The engine returned no audio. Try Speak again."))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertTrue(dialog.isShowing)
        assertTrue(CrashLog.read(a)!!.contains("The engine returned no audio"))
        assertTrue(a.findViewById<android.widget.Button>(R.id.speak_button).isEnabled)
        val root = dialog.window!!.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val bitmap = Bitmap.createBitmap(root.measuredWidth, root.measuredHeight, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File("/downloads/voice-reps-0.1.3-error.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        dialog.dismiss()
        CrashLog.clear(a)
        controller.pause().stop().destroy()
    }

    @Test fun coldRestorationKeepsSavedVoiceAndEmbedding() {
        val first = Robolectric.buildActivity(MainActivity::class.java).setup()
        val ctx = first.get()
        val store = VoiceStore(ctx)
        val reference = File(ctx.cacheDir, "reference.wav").apply { writeBytes(ByteArray(44)) }
        val voice = store.addPending("Saved voice", "record", reference)
        val values = FloatArray(192) { (it - 96) / 96f }
        store.complete(voice, values)
        ctx.getSharedPreferences("speak", 0).edit().putString("voice_id", voice.id).commit()
        ctx.findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.nav_speak
        ctx.supportFragmentManager.executePendingTransactions()
        (ctx.supportFragmentManager.findFragmentByTag("speak") as SpeakFragment).refreshVoices()
        val state = Bundle()
        first.pause().saveInstanceState(state).stop().destroy()
        val restored = Robolectric.buildActivity(MainActivity::class.java).create(state).start().restoreInstanceState(state).resume().visible()
        val a = restored.get()
        assertEquals(3, a.supportFragmentManager.fragments.size)
        assertEquals(1, a.findViewById<Spinner>(R.id.voice_spinner).selectedItemPosition)
        val loaded = VoiceStore(a).list().single { it.id == voice.id }
        assertArrayEquals(values, VoiceStore(a).embeddingOf(loaded), 0f)
        restored.pause().stop().destroy()
    }
}
