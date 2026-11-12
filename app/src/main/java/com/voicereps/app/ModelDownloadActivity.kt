package com.voicereps.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** First-launch gate: downloads the three GGUF model files from Hugging Face. */
class ModelDownloadActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var progress: ProgressBar
    private lateinit var action: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        setContentView(R.layout.activity_download)

        CrashLog.read(this)?.let { log ->
            android.app.AlertDialog.Builder(this)
                .setTitle("voice-reps crashed")
                .setMessage("If the app keeps crashing, share this log:\n$log")
                .setPositiveButton("Dismiss & clear") { _, _ -> CrashLog.clear(this) }
                .setNegativeButton("Keep", null)
                .show()
        }
        status = findViewById(R.id.download_status)
        detail = findViewById(R.id.download_detail)
        progress = findViewById(R.id.download_progress)
        action = findViewById(R.id.download_action)

        if (Zonos3Engine.modelsReady(this)) { enter(); return }

        Zonos3Engine.nativeLoadError?.let {
            detail.text = "Warning: the native engine failed to load on this device ($it). Voice features will not work."
        }

        val missingBytes = Zonos3Engine.requiredFiles
            .filter { Zonos3Engine.modelFile(this, it.name).length() < it.minBytes }
            .sumOf { it.minBytes }
        if (missingBytes < 100_000_000L) {
            // Small follow-up file (e.g. steer.gguf for expression controls added in 0.1.2) on top of an
            // existing install - do not scare the user with the full 1.2 GB message.
            status.text = "Quick model update"
            detail.text = "A small new model file (under 1 MB) enables expression and emotion controls. Your existing models stay as they are."
        } else {
            status.text = "Voice models needed"
            detail.text = "voice-reps runs entirely on your device. One-time download: ~1.2 GB of ZONOS3-Mini model files (Apache 2.0, from Zyphra on Hugging Face). Wi-Fi recommended."
        }
        action.text = "Download"
        action.setOnClickListener { startDownload() }
    }

    private fun startDownload() {
        action.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.max = 100
        Thread {
            val files = Zonos3Engine.requiredFiles
            val totalBytes = files.sumOf { it.minBytes }
            var doneBytes = 0L
            try {
                for (f in files) {
                    val out = Zonos3Engine.modelFile(this, f.name)
                    if (out.length() >= f.minBytes) { doneBytes += f.minBytes; continue }
                    val tmp = File(out.absolutePath + ".part")
                    update(status, "Downloading ${f.name}")
                    val conn = URL(f.url).openConnection() as HttpURLConnection
                    conn.connect()
                    if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode} for ${f.name}")
                    val len = conn.contentLengthLong.coerceAtLeast(f.minBytes)
                    conn.inputStream.use { input ->
                        FileOutputStream(tmp).use { fos ->
                            val buf = ByteArray(256 * 1024)
                            var got = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                fos.write(buf, 0, n)
                                got += n
                                val pct = ((doneBytes + got * f.minBytes / len) * 100 / totalBytes).toInt()
                                update(progress, pct)
                                update(detail, "${f.name}: ${got / 1_000_000} / ${len / 1_000_000} MB")
                            }
                        }
                    }
                    conn.disconnect()
                    if (tmp.length() < f.minBytes) throw Exception("Incomplete download of ${f.name}")
                    tmp.renameTo(out)
                    doneBytes += f.minBytes
                }
                update(status, "Models ready")
                runOnUiThread { enter() }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Download failed"
                    detail.text = e.message
                    action.text = "Retry"
                    action.isEnabled = true
                    action.setOnClickListener { startDownload() }
                }
            }
        }.start()
    }

    private fun update(tv: TextView, s: String) = runOnUiThread { tv.text = s }
    private fun update(pb: ProgressBar, v: Int) = runOnUiThread { pb.progress = v }

    private fun enter() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
