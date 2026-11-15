package com.voicereps.app
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

class EmotionTokenizerTest {
    @Test fun matchesPinnedHuggingFaceTokenizerIncludingHebrewUnicodeAndLongText() {
        val tokenizer = File("src/main/assets/emotion-tokenizer.bin").inputStream().use { EmotionTokenizer(it) }
        javaClass.getResourceAsStream("/tokenizer-golden.tsv")!!.bufferedReader().useLines { lines ->
            lines.forEachIndexed { n, line ->
                val fields = line.split('\t', limit = 2)
                val text = String(Base64.getDecoder().decode(fields[0]), Charsets.UTF_8)
                val expected = fields[1].split(',').filter { it.isNotEmpty() }.map { it.toInt() }
                assertEquals("fixture $n: $text", expected, tokenizer.encode(text))
            }
        }
    }
    @Test fun windowingNeverDropsOrDuplicatesTokens() {
        val ids = (0..5000).toList()
        val windows = EmotionTokenizer.windows(ids)
        assertTrue(windows.all { it.size <= 256 && it.first() == 2L && it.last() == 1L })
        assertEquals(ids.map(Int::toLong), windows.flatMap { it.drop(1).dropLast(1) })
        assertArrayEquals(longArrayOf(2, 1), EmotionTokenizer.windows(emptyList()).single())
    }
    @Test fun conservativeMappingIsNotAConfidenceToStrengthConversion() {
        val scores = DoubleArray(28)
        scores[17] = 0.9
        assertEquals("Happy", EmotionModel.choose(scores).preset)
        scores[17] = 0.4
        assertEquals("Neutral", EmotionModel.choose(scores).preset)
        scores[14] = 0.95
        assertEquals("Neutral", EmotionModel.choose(scores).preset) // no Fear steering preset
        scores.fill(0.0); scores[2] = 0.8
        assertEquals("Angry", EmotionModel.choose(scores).preset)
    }
}
