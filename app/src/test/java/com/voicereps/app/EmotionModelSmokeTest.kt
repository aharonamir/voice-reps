package com.voicereps.app

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/** Optional real-weight host test. Does not download weights or use private text.
 * Set VOICE_REPS_EMOTION_TEST_MODEL and VOICE_REPS_ORT_HOST_LIBS to run. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EmotionModelSmokeTest {
    @Test fun actualAppClassifierRunsEnglishHebrewAndAllLongTextWindows() {
        val source = System.getenv("VOICE_REPS_EMOTION_TEST_MODEL")?.let(::File)
        val libs = System.getenv("VOICE_REPS_ORT_HOST_LIBS")
        assumeTrue(source?.isFile == true && libs != null)
        System.setProperty("onnxruntime.native.path", libs!!)
        val digest = MessageDigest.getInstance("SHA-256")
        source!!.inputStream().use { input -> val b = ByteArray(1024 * 1024); while (true) { val n = input.read(b); if (n < 0) break; digest.update(b, 0, n) } }
        assertEquals("7fed4f82f2e1c4039452876e87ef948f5eedfdcf45f351470010e0ed65f1e9ee", digest.digest().joinToString("") { "%02x".format(it) })
        val ctx = RuntimeEnvironment.getApplication()
        val target = File(ctx.filesDir, "emotions-${EmotionModel.REVISION}.onnx")
        source.copyTo(target, overwrite = true)
        try {
            listOf("I am so happy today!", "אני כל כך שמח היום!", "I feel very sad and lonely.", "אני עצוב ובודד היום.", "I am not angry with you.", "אני לא כועס עליך.", "I am happy today! ".repeat(150)).forEach { text ->
                val c = EmotionModel.classify(ctx, text, { true }) {}
                println("EMOTION_SMOKE chars=${text.length} text=${text.take(50)} label=${c.label} preset=${c.preset} windows=${c.windows}")
                assertTrue(c.preset in listOf("Neutral", "Happy", "Sad", "Angry", "Excited"))
                if (text.length > 1000) assertTrue(c.windows > 1)
            }
        } finally { target.delete() }
    }
}
