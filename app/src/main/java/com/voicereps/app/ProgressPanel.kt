package com.voicereps.app

import android.view.View
import android.widget.ProgressBar
import android.widget.TextView

/** A progress bar plus a tqdm-style line. Call from the UI thread. */
class ProgressPanel(private val root: View) {
    private val bar = root.findViewById<ProgressBar>(R.id.step_bar)
    private val text = root.findViewById<TextView>(R.id.step_text)

    fun show(p: StepProgress) {
        root.visibility = View.VISIBLE
        val f = p.fraction
        // No measured total: show an indeterminate (animated) bar rather than an invented percentage.
        if (f == null) { if (!bar.isIndeterminate) bar.isIndeterminate = true }
        else { if (bar.isIndeterminate) bar.isIndeterminate = false; bar.progress = (f * 1000).toInt() }
        text.text = p.text()
    }

    /** Buffer fill: measured audio seconds queued against the current target. */
    fun showFraction(fraction: Double?, line: String) {
        root.visibility = View.VISIBLE
        if (fraction == null) { if (!bar.isIndeterminate) bar.isIndeterminate = true }
        else { if (bar.isIndeterminate) bar.isIndeterminate = false; bar.progress = (fraction.coerceIn(0.0, 1.0) * 1000).toInt() }
        text.text = line
    }

    fun hide() { root.visibility = View.GONE; if (bar.isIndeterminate) bar.isIndeterminate = false }
}
