package com.voicereps.app

import android.app.Activity
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * The activity root is the single owner of system-bar, cutout and IME insets.
 * Consume them after applying padding: Material BottomNavigationView 1.12 otherwise
 * reads the legacy system-window bottom (which can be the full keyboard height)
 * and adds that height as its own padding a second time.
 */
fun Activity.applyEdgeToEdge(root: View) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val left = root.paddingLeft
    val top = root.paddingTop
    val right = root.paddingRight
    val bottom = root.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, windowInsets ->
        val bars: Insets = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
        val bottomInset = maxOf(bars.bottom, ime.bottom)
        v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bottomInset)
        WindowInsetsCompat.CONSUMED
    }
    ViewCompat.requestApplyInsets(root)
}
