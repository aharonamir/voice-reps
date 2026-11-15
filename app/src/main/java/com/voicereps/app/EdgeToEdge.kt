package com.voicereps.app

import android.app.Activity
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Android 15+ (targetSdk 35+) always draws edge-to-edge. Keep content clear of the status bar,
 * cutouts and keyboard by turning those insets into padding on [root]. The bottom navigation
 * bar view pads itself for the gesture/nav bar, so Main passes includeBottomBars = false.
 */
fun Activity.applyEdgeToEdge(root: View, includeBottomBars: Boolean) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val left = root.paddingLeft
    val top = root.paddingTop
    val right = root.paddingRight
    val bottom = root.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, windowInsets ->
        val bars: Insets = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
        val bottomInset = if (includeBottomBars) maxOf(bars.bottom, ime.bottom)
        else if (ime.bottom > 0) ime.bottom else 0
        v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bottomInset)
        windowInsets
    }
    ViewCompat.requestApplyInsets(root)
}
