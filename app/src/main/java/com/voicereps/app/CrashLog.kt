package com.voicereps.app

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records uncaught exceptions (and explicit error reports) to filesDir/crash-log.txt,
 * so after a crash the next launch can show exactly what failed.
 */
object CrashLog {
    private const val FILE = "crash-log.txt"
    private const val TAG = "CrashLog"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, err ->
            record(app, "uncaught on thread '${thread.name}'", err)
            previous?.uncaughtException(thread, err)
        }
    }

    @Synchronized
    fun record(ctx: Context, where: String, err: Throwable) {
        try {
            val sw = StringWriter()
            err.printStackTrace(PrintWriter(sw))
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            File(ctx.filesDir, FILE).appendText("\n===== $ts  $where =====\n$sw\n")
            Log.e(TAG, "recorded crash at $where", err)
        } catch (_: Throwable) {}
    }

    fun read(ctx: Context): String? {
        val f = File(ctx.filesDir, FILE)
        return if (f.exists() && f.length() > 0) f.readText().takeLast(4000) else null
    }

    fun clear(ctx: Context) {
        File(ctx.filesDir, FILE).delete()
    }
}
