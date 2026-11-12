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
import java.util.concurrent.atomic.AtomicInteger

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
    private val operation = AtomicInteger()
    private val prefs by lazy { requireContext().getSharedPreferences("speak", 0) }

    // Expression controls: an emotion preset plus pitch/expression/pace sliders. Doses follow the
    // safe ranges of the engine's studio steering bundle (steer.gguf, ids as in /v2/controls).
    private var emotion = "Neutral"
    private var pitch = 0.0      // delivery.pitch, -3..3 (safe +/-2)
    private var expressive = 0.0 // expressive.expressive, -4..8 (safe +/-8)
    private var pace = 0.0       // delivery.tempo, -2..2 (safe +/-2)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View =
        inflater.inflate(R.layout.fragment_speak, container, false)

    override fun onViewCreated(view: View, saved: Bundle?) {
        busy = false
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
        spinner.onItemSelectedListener = null
        spinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
        val savedId = prefs.getString("voice_id", null)
        selected = voices.indexOfFirst { it.id == savedId }.let { if (it < 0) 0 else it + 1 }
        spinner.setSelection(selected)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selected = pos
                prefs.edit().putString("voice_id", voices.getOrNull(pos - 1)?.id).apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun embedding(): FloatArray? {
        if (selected == 0) return null
        val voice = voices.getOrNull(selected - 1) ?: error("Choose the saved voice again.")
        return store.embeddingOf(voice).also { values ->
            check(values.size == Zonos3Engine.EMBEDDING_DIM && values.all { it.isFinite() }) {
                "The saved voice profile is invalid. Create the voice again."
            }
        }
    }

    private fun update(id: Int, action: () -> Unit) {
        activity?.runOnUiThread { if (operation.get() == id && view != null) action() }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        speakBtn.isEnabled = !value
        saveBtn.isEnabled = !value
        spinner.isEnabled = !value
    }

    private fun reportError(id: Int, appCtx: android.content.Context, where: String, t: Throwable) {
        if (operation.get() != id) return // expected interruption, not an unexplained failure
        Log.e(TAG, where, t)
        CrashLog.record(appCtx, where, t)
        update(id) {
            setBusy(false)
            val detail = t.message ?: t.javaClass.simpleName
            status.text = "Error: $detail"
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Could not play the voice").setMessage(detail)
                .setPositiveButton("OK", null).show()
        }
    }

    private fun speak() {
        if (busy) return
        val text = input.text.toString().trim()
        if (text.isEmpty()) { toast("Type something to speak"); return }
        val id = operation.incrementAndGet()
        val appCtx = requireContext().applicationContext
        setBusy(true)
        status.text = "Loading engine. Please keep the app open..."
        val controls = controlsString()
        val emb = try { embedding() } catch (t: Throwable) {
            reportError(id, appCtx, "load saved voice", t); return
        }
        Thread {
            var stream: AudioPlayer.Stream? = null
            try {
                val t0 = System.currentTimeMillis()
                val stats = PcmStats()
                val ok = Zonos3Engine.synthesizeStreaming(appCtx, text, emb, controls,
                    object : com.zyphra.zonos3.Zonos3.PcmListener {
                        override fun onChunk(pcm: FloatArray) {
                            check(operation.get() == id) { "Playback interrupted" }
                            stats.add(pcm)
                            if (pcm.isEmpty()) return
                            synchronized(player) {
                                check(operation.get() == id) { "Playback interrupted" }
                                if (stream == null) {
                                    stream = player.beginStream(appCtx)
                                    val ms = System.currentTimeMillis() - t0
                                    update(id) { status.text = "Speaking... (first audio in ${ms}ms)" }
                                }
                            }
                            player.writeStream(stream!!, pcm)
                        }
                    })
                check(ok) { Zonos3Engine.lastNativeError.ifEmpty { "Speech generation failed. Try Speak again." } }
                stats.requireAudible()
                val finished = stream ?: error("The engine returned no audio. Try Speak again.")
                player.finishStream(finished)
                update(id) {
                    setBusy(false)
                    status.text = "Done. No sound? Check media volume and Bluetooth."
                }
            } catch (t: Throwable) {
                reportError(id, appCtx, "speak", t)
            } finally {
                // Only stop our own track, never a newer retry's stream.
                stream?.let { player.cancelStream(it) }
            }
        }.start()
    }

    private fun saveAndShare() {
        if (busy) return
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val id = operation.incrementAndGet()
        val appCtx = requireContext().applicationContext
        val emb = try { embedding() } catch (t: Throwable) {
            reportError(id, appCtx, "load saved voice", t); return
        }
        val controls = controlsString()
        setBusy(true)
        status.text = "Rendering WAV. Please keep the app open..."
        Thread {
            try {
                val wav = File(appCtx.cacheDir, "voice-reps-${System.currentTimeMillis()}.wav")
                val ok = Zonos3Engine.synthesizeToWav(appCtx, text, emb, wav, controls)
                check(ok && wav.length() > 44) { Zonos3Engine.lastNativeError.ifEmpty { "No audio was saved." } }
                update(id) {
                    setBusy(false)
                    lastWav = wav
                    status.text = "Saved ${wav.name}"
                    share(wav)
                }
            } catch (t: Throwable) { reportError(id, appCtx, "saveAndShare", t) }
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

    private fun interruptPlayback() {
        synchronized(player) {
            operation.incrementAndGet()
            player.stop()
        }
        if (view != null && busy) {
            setBusy(false)
            status.text = "Interrupted when leaving the screen. Tap Speak to try again."
        }
    }

    override fun onStop() { interruptPlayback(); super.onStop() }
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) interruptPlayback()
    }
    override fun onDestroyView() { interruptPlayback(); super.onDestroyView() }
}
