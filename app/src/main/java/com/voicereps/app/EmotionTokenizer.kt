package com.voicereps.app

import java.io.DataInputStream
import java.io.InputStream

/** Exact metaspace/BPE vocabulary and ranked merges compiled from the pinned HF tokenizer.
 * No Android native tokenizer dependency. Long inputs are windowed, never truncated. */
class EmotionTokenizer(input: InputStream) {
    private val vocab: HashMap<String, Int>
    private val tokens: Array<String>
    private val keys: LongArray
    private val ranks: IntArray
    private val merged: IntArray
    private data class Added(val text: String, val id: Int, val left: Boolean, val right: Boolean)
    private val added: Map<Char, List<Added>>
    init {
        val d = DataInputStream(input.buffered())
        fun str(): String = ByteArray(d.readInt()).also { d.readFully(it) }.toString(Charsets.UTF_8)
        tokens = Array(d.readInt()) { str() }
        vocab = HashMap(tokens.size * 4 / 3 + 1)
        tokens.forEachIndexed { i, t -> vocab[t] = i }
        val n = d.readInt(); keys = LongArray(n); ranks = IntArray(n); merged = IntArray(n)
        repeat(n) { keys[it] = d.readLong(); ranks[it] = d.readInt(); merged[it] = d.readInt() }
        added = List(d.readInt()) { Added(str(), d.readInt(), d.readInt() != 0, d.readInt() != 0) }.sortedByDescending { it.text.length }.groupBy { it.text[0] }
    }
    fun encode(text: String): List<Int> {
        val out = ArrayList<Int>(); var p = 0; var start = 0
        // Added tokens are extracted before normalization, like Hugging Face tokenizers.
        while (p < text.length) {
            val a = added[text[p]]?.firstOrNull { text.startsWith(it.text, p) }
            if (a == null) { p++; continue }
            val raw = text.substring(start, p).let { if (a.left) it.trimEnd() else it }
            ordinary(raw, out); out.add(a.id); p += a.text.length
            if (a.right) while (p < text.length && text[p].isWhitespace()) p++
            start = p
        }
        ordinary(text.substring(start), out)
        return out
    }
    private fun ordinary(raw: String, out: MutableList<Int>) {
        if (raw.isEmpty()) return
        var t = raw.replace(' ', '\u2581')
        if (!t.startsWith('\u2581')) t = "\u2581$t"
        // Metaspace splits at each marker, with marker kept at the start of the next piece.
        var start = 0
        for (i in 1 until t.length) if (t[i] == '\u2581') { bpe(t.substring(start, i), out); start = i }
        bpe(t.substring(start), out)
    }
    private fun bpe(word: String, out: MutableList<Int>) {
        val ids = ArrayList<Int>()
        word.codePoints().forEach { cp ->
            val ch = String(Character.toChars(cp))
            val id = vocab[ch]
            if (id != null) ids.add(id) else ch.toByteArray(Charsets.UTF_8).forEach {
                ids.add(vocab["<0x%02X>".format(it.toInt() and 255)] ?: 3)
            }
        }
        if (ids.isEmpty()) return
        val values = ids.toIntArray()
        val next = IntArray(ids.size) { if (it + 1 < ids.size) it + 1 else -1 }
        val prev = IntArray(ids.size) { it - 1 }
        val live = BooleanArray(ids.size) { true }
        data class Edge(val left: Int, val right: Int, val a: Int, val b: Int, val rank: Int, val target: Int)
        val queue = java.util.PriorityQueue<Edge>(compareBy<Edge> { it.rank }.thenBy { it.left })
        fun add(left: Int) {
            if (left < 0) return
            val right = next[left]; if (right < 0) return
            val key = (values[left].toLong() shl 32) or (values[right].toLong() and 0xffffffffL)
            val ix = keys.binarySearch(key)
            if (ix >= 0) queue.add(Edge(left, right, values[left], values[right], ranks[ix], merged[ix]))
        }
        for (i in values.indices) add(i)
        while (queue.isNotEmpty()) {
            val e = queue.remove(); val l = e.left; val r = e.right
            if (!live[l] || !live[r] || next[l] != r || values[l] != e.a || values[r] != e.b) continue
            values[l] = e.target; live[r] = false; next[l] = next[r]
            if (next[r] >= 0) prev[next[r]] = l
            add(prev[l]); add(l)
        }
        var i = 0
        while (i >= 0) { out.add(values[i]); i = next[i] }
    }
    companion object {
        fun windows(ids: List<Int>): List<LongArray> = ids.chunked(254).ifEmpty { listOf(emptyList()) }
            .map { (listOf(2L) + it.map(Int::toLong) + 1L).toLongArray() }
    }
}
