package com.voicereps.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import java.io.File

class SpeakFragment : Fragment() {

    companion object { private const val TAG = "SpeakFragment" }

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

    // Expression controls: an emotion preset plus pitch/expression/pace sliders. Doses follow the
    // safe ranges of the engine's studio steering bundle (steer.gguf, ids as in /v2/controls).
    private var emotion = "Neutral"
    private var pitch = 0.0      // delivery.pitch, -3..3 (safe +/-2)
    private var expressive = 0.0 // expressive.expressive, -4..8 (safe +/-8)
    private var pace = 0.0       // delivery.tempo, -2..2 (safe +/-2)

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

        val chips: ChipGroup = view.findViewById(R.id.emotion_chips)
        chips.setOnCheckedStateChangeListener { group, ids ->
            val c = ids.firstOrNull()?.let { group.findViewById<Chip>(it) }
            emotion = c?.text?.toString() ?: "Neutral"
        }
        bindSlider(view, R.id.pitch_bar, R.id.pitch_val, 6, 12) { pitch = it }
        bindSlider(view, R.id.expression_bar, R.id.expression_val, 8, 24) { expressive = it }
        bindSlider(view, R.id.pace_bar, R.id.pace_val, 4, 8) { pace = it }

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

    /** SeekBar of half steps centered at `zero` (value = (progress - zero) * 0.5). */
    private fun bindSlider(view: View, barId: Int, valId: Int, zero: Int, max: Int, set: (Double) -> Unit) {
        val bar = view.findViewById<SeekBar>(barId)
        val label = view.findViewById<TextView>(valId)
        bar.max = max
        bar.progress = zero
        label.text = "0"
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val v = (p - zero) * 0.5
                set(v)
                label.text = if (v == 0.0) "0" else String.format("%+.1f", v)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun controlsString(): String {
        val m = linkedMapOf<String, Double>()
        when (emotion) {
            "Happy" -> m["emotions.happy"] = 2.0
            "Sad" -> m["emotions.sad"] = 4.0
            "Angry" -> m["emotions.angry"] = 2.0
            "Calm" -> m["emotions.serene"] = 4.0
            "Excited" -> { m["emotions.ecstatic"] = 2.0; m["mood.arousal"] = 1.0 }
            "Whisper" -> m["delivery.whisper"] = 1.5
        }
        if (pitch != 0.0) m["delivery.pitch"] = pitch
        if (expressive != 0.0) m["expressive.expressive"] = expressive
        if (pace != 0.0) m["delivery.tempo"] = pace
        return m.entries.joinToString(";") { "${it.key}=${it.value}" }
    }

    fun refreshVoices() {
        if (!::store.isInitialized) return
        voices = store.list().filter { it.ready }
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
        Zonos3Engine.nativeLoadError?.let {
            status.text = "Native engine failed to load: $it"
            return
        }
        busy = true
        speakBtn.isEnabled = false
        saveBtn.isEnabled = false
        status.text = "Loading engine (first run loads ~1.1 GB of models)..."
        val emb = if (selected > 0) store.embeddingOf(voices[selected - 1]) else null
        val appCtx = requireContext().applicationContext

        val controls = controlsString()
        Thread {
            try {
                var firstChunkAt = 0L
                val t0 = System.currentTimeMillis()
                val track = player.beginStream()
                val ok = Zonos3Engine.synthesizeStreaming(appCtx, text, emb, controls, object : com.zyphra.zonos3.Zonos3.PcmListener {
                    override fun onChunk(pcm: FloatArray) {
                        if (firstChunkAt == 0L) {
                            firstChunkAt = System.currentTimeMillis()
                            val ms = firstChunkAt - t0
                            activity?.runOnUiThread { status.text = "Speaking... (first audio in ${ms}ms)" }
                        }
                        player.writeStream(track, pcm)
                    }
                })
                player.finishStream() // play out the buffered tail before releasing (was cutting the last word)
                activity?.runOnUiThread {
                    busy = false
                    speakBtn.isEnabled = true
                    saveBtn.isEnabled = true
                    if (ok) {
                        status.text = "Done. Save to share it as a WAV."
                    } else {
                        status.text = "Generation failed: ${Zonos3Engine.lastNativeError.ifEmpty { "unknown error" }}"
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "speak failed", t)
                CrashLog.record(appCtx, "speak", t)
                player.stop()
                activity?.runOnUiThread {
                    busy = false
                    speakBtn.isEnabled = true
                    saveBtn.isEnabled = true
                    status.text = "Error: ${t.message ?: t.javaClass.simpleName}"
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
        val appCtx = requireContext().applicationContext
        val controls = controlsString()
        Thread {
            try {
                val wav = File(appCtx.cacheDir, "voice-reps-${System.currentTimeMillis()}.wav")
                val ok = Zonos3Engine.synthesizeToWav(appCtx, text, emb, wav, controls)
                activity?.runOnUiThread {
                    saveBtn.isEnabled = true
                    if (ok) {
                        lastWav = wav
                        status.text = "Saved ${wav.name}"
                        share(wav)
                    } else status.text = "Render failed: ${Zonos3Engine.lastNativeError.ifEmpty { "unknown error" }}"
                }
            } catch (t: Throwable) {
                Log.e(TAG, "saveAndShare failed", t)
                CrashLog.record(appCtx, "saveAndShare", t)
                activity?.runOnUiThread {
                    saveBtn.isEnabled = true
                    status.text = "Error: ${t.message ?: t.javaClass.simpleName}"
                }
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
