package com.voicereps.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import java.io.File

class SpeakFragment : Fragment() {

    private lateinit var store: VoiceStore
    private lateinit var spinner: Spinner
    private lateinit var input: EditText
    private lateinit var langHint: TextView
    private lateinit var speakBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var status: TextView
    private val player = AudioPlayer()

    private var voices: List<Voice> = emptyList()
    private var selected: Int = 0 // 0 = default voice
    private var lastWav: File? = null
    @Volatile private var busy = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View =
        inflater.inflate(R.layout.fragment_speak, container, false)

    override fun onViewCreated(view: View, saved: Bundle?) {
        store = VoiceStore(requireContext())
        spinner = view.findViewById(R.id.voice_spinner)
        input = view.findViewById(R.id.speak_input)
        langHint = view.findViewById(R.id.lang_hint)
        speakBtn = view.findViewById(R.id.speak_button)
        saveBtn = view.findViewById(R.id.save_button)
        status = view.findViewById(R.id.speak_status)

        speakBtn.setOnClickListener { speak() }
        saveBtn.setOnClickListener { saveAndShare() }

        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val t = s?.toString() ?: ""
                langHint.text = when {
                    t.any { it in '\u0590'..'\u05FF' } -> "Hebrew detected"
                    t.isBlank() -> ""
                    else -> "English (or Latin script)"
                }
            }
        })
        refreshVoices()
    }

    fun refreshVoices() {
        if (!::store.isInitialized) return
        voices = store.list()
        val labels = listOf("Default voice") + voices.map { it.name }
        spinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
        spinner.setSelection(selected.coerceAtMost(labels.size - 1))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { selected = pos }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun speak() {
        if (busy) return
        val text = input.text.toString().trim()
        if (text.isEmpty()) { toast("Type something to speak"); return }
        busy = true
        speakBtn.isEnabled = false
        saveBtn.isEnabled = false
        status.text = "Generating..."
        val emb = if (selected > 0) store.embeddingOf(voices[selected - 1]) else null

        Thread {
            var firstChunkAt = 0L
            val t0 = System.currentTimeMillis()
            val track = player.beginStream()
            val ok = Zonos3Engine.synthesizeStreaming(requireContext(), text, emb, object : com.zyphra.zonos3.Zonos3.PcmListener {
                override fun onChunk(pcm: FloatArray) {
                    if (firstChunkAt == 0L) {
                        firstChunkAt = System.currentTimeMillis()
                        val ms = firstChunkAt - t0
                        activity?.runOnUiThread { status.text = "Speaking... (first audio in ${ms}ms)" }
                    }
                    track.write(pcm, 0, pcm.size, android.media.AudioTrack.WRITE_BLOCKING)
                }
            })
            Thread.sleep(300) // let the last chunk drain
            player.stop()
            activity?.runOnUiThread {
                busy = false
                speakBtn.isEnabled = true
                if (ok) {
                    status.text = "Done. Save to share it as a WAV."
                    // render once more to a file for sharing (keeps playback and export identical)
                    saveBtn.isEnabled = true
                } else {
                    status.text = "Generation failed: ${Zonos3Engine.lastError(0)}"
                }
            }
        }.start()
    }

    private fun saveAndShare() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        status.text = "Rendering WAV..."
        saveBtn.isEnabled = false
        val emb = if (selected > 0) store.embeddingOf(voices[selected - 1]) else null
        Thread {
            val wav = File(requireContext().cacheDir, "voice-reps-${System.currentTimeMillis()}.wav")
            val ok = Zonos3Engine.synthesizeToWav(requireContext(), text, emb, wav)
            activity?.runOnUiThread {
                saveBtn.isEnabled = true
                if (ok) {
                    lastWav = wav
                    status.text = "Saved ${wav.name}"
                    share(wav)
                } else status.text = "Render failed"
            }
        }.start()
    }

    private fun share(wav: File) {
        try {
            val uri = FileProvider.getUriForFile(requireContext(),
                requireContext().packageName + ".files", wav)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share voice clip"))
        } catch (e: Exception) {
            toast("File saved: ${wav.absolutePath}")
        }
    }

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() { player.stop(); super.onDestroyView() }
}
