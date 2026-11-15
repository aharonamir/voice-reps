package com.voicereps.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
@Config(sdk = [28, 34], qualifiers = "w393dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyboardInsetsTest {
    @Test fun dispatchedInsetsDoNotInflateNavigationAndEditorCanScrollIntoView() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = controller.get()
        val root = a.findViewById<android.view.ViewGroup>(R.id.main_root)
        val nav = a.findViewById<BottomNavigationView>(R.id.bottom_nav)
        nav.selectedItemId = R.id.nav_speak
        a.supportFragmentManager.executePendingTransactions()
        val editor = a.findViewById<EditText>(R.id.speak_input)
        editor.setText("Hello keyboard. This text must remain editable.")
        val scroll = editor.parent.parent as ScrollView
        fun layout() {
            repeat(2) {
                root.measure(View.MeasureSpec.makeMeasureSpec(786, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 786, 1600)
            }
        }
        fun dispatch(ime: Int, bars: Int) {
            // Include the legacy system-window bottom too: Material 1.12 reads that
            // field, which becomes the IME height on a real device with IME open.
            if (android.os.Build.VERSION.SDK_INT < 30) {
                val platform = android.view.WindowInsets::class.java.getConstructor(Rect::class.java)
                    .newInstance(Rect(0, 48, 0, maxOf(ime, bars)))
                root.dispatchApplyWindowInsets(platform)
            } else {
                val insets = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 48, 0, bars))
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
                    .setVisible(WindowInsetsCompat.Type.ime(), ime > 0)
                    .build()
                ViewCompat.dispatchApplyWindowInsets(root, insets)
            }
            layout()
        }
        fun snapshot(name: String) {
            val bitmap = Bitmap.createBitmap(786, 1600, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            File("/downloads/api${android.os.Build.VERSION.SDK_INT}-$name").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        dispatch(0, 48)
        val closedHeight = nav.height
        snapshot("voice-reps-0.2.2-keyboard-closed.png")
        dispatch(640, 48)
        snapshot("voice-reps-keyboard-before-editor-scroll.png")
        assertEquals("keyboard must not become padding inside bottom navigation", 0, nav.paddingBottom)
        assertEquals("navigation stays compact", closedHeight, nav.height)
        assertEquals("root alone owns keyboard inset", 640, root.paddingBottom)
        assertTrue("editor keeps its minimum height", editor.height >= 360)
        editor.requestFocus()
        scroll.requestChildRectangleOnScreen(editor, Rect(0, 0, editor.width, editor.height), true)
        layout()
        val rect = Rect(0, 0, editor.width, editor.height)
        root.offsetDescendantRectToMyCoords(editor, rect)
        assertTrue("editor bottom ${rect.bottom} is above navigation ${nav.top}", rect.bottom <= nav.top)
        assertTrue("editor top ${rect.top} clears status inset", rect.top >= root.paddingTop)
        editor.append(" Typed with IME visible.")
        assertTrue(editor.text.toString().endsWith("IME visible."))
        snapshot("voice-reps-0.2.2-keyboard-open.png")
        repeat(3) { dispatch(0, 48); dispatch(640, 48) }
        assertEquals(closedHeight, nav.height)
        assertEquals(640, root.paddingBottom)
        dispatch(640, 0) // gesture navigation
        assertEquals(closedHeight, nav.height)
        dispatch(0, 0)
        assertEquals(0, root.paddingBottom)
        assertEquals(closedHeight, nav.height)
        controller.pause().stop().destroy()
    }
}
