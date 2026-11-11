package com.voicereps.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Voice(
    val id: String,
    var name: String,
    val createdAt: Long,
    val source: String,        // "record" or "upload"
    val referenceWav: File,
    val embeddingBin: File,    // raw float32[192]
)

/** JSON-indexed store of cloned voices under filesDir/voices/. */
class VoiceStore(private val ctx: Context) {

    private val dir = File(ctx.filesDir, "voices").apply { mkdirs() }
    private val index = File(dir, "voices.json")

    fun list(): List<Voice> {
        if (!index.exists()) return emptyList()
        val arr = JSONArray(index.readText())
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Voice(
                id = o.getString("id"),
                name = o.getString("name"),
                createdAt = o.getLong("createdAt"),
                source = o.getString("source"),
                referenceWav = File(dir, o.getString("wav")),
                embeddingBin = File(dir, o.getString("emb")),
            )
        }.filter { it.embeddingBin.exists() }
    }

    fun add(name: String, source: String, referenceWav: File, embedding: FloatArray): Voice {
        val id = "v" + System.currentTimeMillis().toString(36)
        val wavFile = File(dir, "$id.wav")
        referenceWav.copyTo(wavFile, overwrite = true)
        val embFile = File(dir, "$id.emb")
        embFile.writeBytes(embedding.toBytes())
        val v = Voice(id, name, System.currentTimeMillis(), source, wavFile, embFile)
        val all = listOf(v) + list()
        save(all)
        return v
    }

    fun rename(v: Voice, newName: String) {
        v.name = newName
        save(list())
    }

    fun delete(v: Voice) {
        v.referenceWav.delete(); v.embeddingBin.delete()
        save(list().filter { it.id != v.id })
    }

    fun embeddingOf(v: Voice): FloatArray = v.embeddingBin.readBytes().toFloatArray()

    fun createdLabel(v: Voice): String =
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(v.createdAt))

    private fun save(voices: List<Voice>) {
        val arr = JSONArray()
        voices.forEach { v ->
            arr.put(JSONObject().apply {
                put("id", v.id); put("name", v.name); put("createdAt", v.createdAt)
                put("source", v.source); put("wav", v.referenceWav.name); put("emb", v.embeddingBin.name)
            })
        }
        index.writeText(arr.toString())
    }

    private fun FloatArray.toBytes(): ByteArray {
        val b = ByteArray(size * 4)
        for (i in indices) {
            val bits = this[i].toRawBits()
            b[i * 4] = (bits and 0xFF).toByte()
            b[i * 4 + 1] = (bits shr 8 and 0xFF).toByte()
            b[i * 4 + 2] = (bits shr 16 and 0xFF).toByte()
            b[i * 4 + 3] = (bits shr 24 and 0xFF).toByte()
        }
        return b
    }

    private fun ByteArray.toFloatArray(): FloatArray {
        val f = FloatArray(size / 4)
        for (i in f.indices) {
            val bits = (this[i * 4].toInt() and 0xFF) or
                (this[i * 4 + 1].toInt() and 0xFF shl 8) or
                (this[i * 4 + 2].toInt() and 0xFF shl 16) or
                (this[i * 4 + 3].toInt() and 0xFF shl 24)
            f[i] = Float.fromBits(bits)
        }
        return f
    }
}
