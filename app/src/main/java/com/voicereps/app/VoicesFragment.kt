package com.voicereps.app

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class VoicesFragment : Fragment() {

    companion object { private const val TAG = "VoicesFragment" }

    private lateinit var store: VoiceStore
    private lateinit var adapter: VoiceAdapter
    private lateinit var empty: TextView
    private val player = AudioPlayer()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View =
        inflater.inflate(R.layout.fragment_voices, container, false)

    override fun onViewCreated(view: View, saved: Bundle?) {
        store = VoiceStore(requireContext())
        empty = view.findViewById(R.id.voices_empty)
        val rv = view.findViewById<RecyclerView>(R.id.voices_list)
        rv.layoutManager = LinearLayoutManager(requireContext())
        adapter = VoiceAdapter(
            onPlay = { v ->
                if (v.ready) {
                    val pcm = AudioDecoder.decodeWavFile(v.referenceWav)
                    if (pcm != null) player.play(pcm) else toast("Cannot play reference")
                } else {
                    finishSetup(v)
                }
            },
            onRename = { v -> promptRename(v) },
            onDelete = { v ->
                AlertDialog.Builder(requireContext())
                    .setTitle("Delete ${v.name}?")
                    .setMessage("The voice profile and its reference audio will be removed.")
                    .setPositiveButton("Delete") { _, _ -> store.delete(v); refreshVoices() }
                    .setNegativeButton("Cancel", null).show()
            })
        rv.adapter = adapter
        refreshVoices()
    }

    /** Rebuild the embedding for a voice whose profile was interrupted (e.g. by a crash). */
    private fun finishSetup(v: Voice) {
        toast("Finishing \"${v.name}\"...")
        val appCtx = requireContext().applicationContext
        Thread {
            try {
                val pcm = AudioDecoder.decodeWavFile(v.referenceWav)
                val emb = pcm?.let { Zonos3Engine.embedVoice(appCtx, it, Zonos3Engine.SAMPLE_RATE) }
                activity?.runOnUiThread {
                    if (emb != null) {
                        store.complete(v, emb)
                        toast("\"${v.name}\" is ready")
                        refreshVoices()
                    } else {
                        toast("Still failing: ${Zonos3Engine.lastNativeError.ifEmpty { "unknown error" }}")
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "finishSetup failed", t)
                CrashLog.record(appCtx, "finishSetup", t)
                activity?.runOnUiThread { toast("Error: ${t.message ?: t.javaClass.simpleName}") }
            }
        }.start()
    }

    fun refreshVoices() {
        if (!::store.isInitialized) return
        val list = store.list()
        adapter.submit(list)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun promptRename(v: Voice) {
        val input = EditText(requireContext()).apply { setText(v.name) }
        AlertDialog.Builder(requireContext())
            .setTitle("Rename voice")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) { store.rename(v, name); refreshVoices() }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() { player.stop(); super.onDestroyView() }

    inner class VoiceAdapter(
        val onPlay: (Voice) -> Unit,
        val onRename: (Voice) -> Unit,
        val onDelete: (Voice) -> Unit,
    ) : RecyclerView.Adapter<VoiceAdapter.Holder>() {

        private var items: List<Voice> = emptyList()
        fun submit(l: List<Voice>) { items = l; notifyDataSetChanged() }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.voice_name)
            val meta: TextView = v.findViewById(R.id.voice_meta)
            val play: ImageButton = v.findViewById(R.id.voice_play)
            val rename: ImageButton = v.findViewById(R.id.voice_rename)
            val delete: ImageButton = v.findViewById(R.id.voice_delete)
        }

        override fun onCreateViewHolder(p: ViewGroup, vt: Int) =
            Holder(LayoutInflater.from(p.context).inflate(R.layout.item_voice, p, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: Holder, pos: Int) {
            val v = items[pos]
            h.name.text = v.name
            h.meta.text = if (v.ready)
                "${if (v.source == "record") "Recorded" else "Uploaded"} · ${store.createdLabel(v)}"
            else
                "Setup incomplete - tap play to finish"
            h.play.setOnClickListener { onPlay(v) }
            h.rename.setOnClickListener { onRename(v) }
            h.delete.setOnClickListener { onDelete(v) }
        }
    }
}
