package com.voicereps.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
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
    private lateinit var autoEmotions: CheckBox
    private lateinit var autoStatus: TextView
    private lateinit var pauseBtn: Button
    private lateinit var stopBtn: Button
    private var paused = false
    private lateinit var progress: ProgressPanel
    private lateinit var bufferSpinner: Spinner
    @Volatile private var activeStream: AudioPlayer.Stream? = null
    @Volatile private var generating = false
    @Volatile private var generateStart = 0L
    private var tickerRunning = false
    private var autoResult: String? = null

    private var voices: List<Voice> = emptyList()
    private var selected: Int = 0 // 0 = default voice
    private var lastWav: File? = null
    private var lastSpokenText: String? = null
    private var lastVoiceName: String? = null
    private var lastControls: String = ""
    @Volatile private var busy = false
    @Volatile private var runningId = 0
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
        busy = runningId != 0
        store = VoiceStore(requireContext())
        spinner = view.findViewById(R.id.voice_spinner)
        input = view.findViewById(R.id.speak_input)
        langHint = view.findViewById(R.id.lang_hint)
        speakBtn = view.findViewById(R.id.speak_button)
        saveBtn = view.findViewById(R.id.save_button)
        status = view.findViewById(R.id.speak_status)
        progress = ProgressPanel(view.findViewById(R.id.speak_progress))
        bufferSpinner = view.findViewById(R.id.buffer_spinner)
        bufferSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, BufferMode.values().map { it.label })
        bufferSpinner.setSelection(bufferMode().ordinal)
        bufferSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.edit().putString("buffer_mode", BufferMode.values()[pos].name).apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        autoEmotions = view.findViewById(R.id.auto_emotions)
        autoStatus = view.findViewById(R.id.auto_emotion_status)
        pauseBtn = view.findViewById(R.id.pause_button)
        stopBtn = view.findViewById(R.id.stop_button)
        pauseBtn.setOnClickListener {
            paused = !paused
            if (paused) player.pause() else player.resume()
            pauseBtn.text = if (paused) "Resume" else "Pause"
            status.text = if (paused) "Paused. Resume or stop playback." else "Speaking..."
        }
        stopBtn.setOnClickListener {
            operation.incrementAndGet()
            player.stop()
            paused = false
            pauseBtn.isEnabled = false
            stopBtn.isEnabled = false
            status.text = "Stopped. Finishing current background step..."
            // Keep Speak disabled until the worker exits, avoiding overlapping model loads.
        }
        speakBtn.setOnClickListener { speak() }
        saveBtn.setOnClickListener { saveAndShare() }
        view.findViewById<Button>(R.id.report_button).setOnClickListener { reportAudio() }

        val chips: ChipGroup = view.findViewById(R.id.emotion_chips)
        chips.setOnCheckedStateChangeListener { group, ids ->
            val c = ids.firstOrNull()?.let { group.findViewById<Chip>(it) }
            emotion = c?.text?.toString() ?: "Neutral"
        }
        bindSlider(view, R.id.pitch_bar, R.id.pitch_val, 6, 12) { pitch = it }
        bindSlider(view, R.id.expression_bar, R.id.expression_val, 8, 24) { expressive = it }
        bindSlider(view, R.id.pace_bar, R.id.pace_val, 4, 8) { pace = it }

        autoEmotions.isChecked = prefs.getBoolean("auto_emotions", false)
        autoEmotions.setOnCheckedChangeListener { _, checked ->
            autoResult = null
            prefs.edit().putBoolean("auto_emotions", checked).apply()
            updateManualControls()
        }
        updateManualControls()

        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                autoResult = null
                if (!busy) updateManualControls()
                val t = s?.toString() ?: ""
                langHint.text = when {
                    t.any { it in '\u0590'..'\u05FF' } -> "Hebrew detected"
                    t.isBlank() -> ""
                    else -> "English (or Latin script)"
                }
            }
        })
        refreshVoices()
        setBusy(busy)
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

    private fun controlsString(preset: String = emotion, manual: Boolean = true): String {
        val m = linkedMapOf<String, Double>()
        when (preset) {
            "Happy" -> m["emotions.happy"] = 2.0
            "Sad" -> m["emotions.sad"] = 4.0
            "Angry" -> m["emotions.angry"] = 2.0
            "Calm" -> m["emotions.serene"] = 4.0
            "Excited" -> { m["emotions.ecstatic"] = 2.0; m["mood.arousal"] = 1.0 }
            "Whisper" -> m["delivery.whisper"] = 1.5
        }
        if (manual && pitch != 0.0) m["delivery.pitch"] = pitch
        if (manual && expressive != 0.0) m["expressive.expressive"] = expressive
        if (manual && pace != 0.0) m["delivery.tempo"] = pace
        return m.entries.joinToString(";") { "${it.key}=${it.value}" }
    }

    private fun updateManualControls() {
        val root = view ?: return
        val enabled = !autoEmotions.isChecked && !busy
        val chips = root.findViewById<ChipGroup>(R.id.emotion_chips)
        for (i in 0 until chips.childCount) chips.getChildAt(i).isEnabled = enabled
        listOf(R.id.pitch_bar, R.id.expression_bar, R.id.pace_bar).forEach {
            root.findViewById<SeekBar>(it).apply { isEnabled = enabled; alpha = if (enabled) 1f else 0.4f }
        }
        autoStatus.visibility = if (autoEmotions.isChecked) View.VISIBLE else View.GONE
        if (autoEmotions.isChecked && !busy) autoStatus.text = autoResult ?: if (EmotionModel.ready(requireContext()))
            "On-device detection. One emotion for the whole text; turn off to set it manually."
        else "First use downloads 268 MB on Speak or MP3 export. Wi-Fi recommended. Text stays on device."
    }

    private fun resolveControls(id: Int, ctx: android.content.Context, text: String, auto: Boolean, manual: String): String {
        if (!auto) return manual
        Zonos3Engine.releaseSynthesisEngine()
        val choice = EmotionModel.classify(ctx, text, { operation.get() == id }, { message ->
            update(id) { status.text = message }
        }, { p -> update(id) { progress.show(p) } })
        update(id) {
            autoResult = "Detected: ${choice.label} → ${choice.preset}" +
                (if (choice.preset == "Neutral" && choice.label != "neutral") " (uncertain or no matching preset)" else "") +
                if (choice.windows > 1) " (whole-text summary, ${choice.windows} sections)" else ""
            autoStatus.text = autoResult
        }
        return controlsString(choice.preset, manual = false)
    }

    fun refreshVoices() {
        if (!::store.isInitialized) return
        voices = store.list().filter { it.ready }
        // Recover voices whose setup was interrupted since this picker last refreshed;
        // when one becomes ready, refresh again so it appears.
        VoiceSetup.finishPending(requireContext().applicationContext, onReady = {
            if (isAdded) refreshVoices()
        })
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

    private fun bufferMode() = BufferMode.fromName(prefs.getString("buffer_mode", null))

    /** Live tqdm-style line for generation and the playback buffer; every number is measured. */
    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        val v = view ?: run { tickerRunning = false; return }
        v.postDelayed(object : Runnable {
            override fun run() {
                if (view == null || !busy) { tickerRunning = false; return }
                tick()
                view?.postDelayed(this, 250)
            }
        }, 250)
    }

    private fun tick() {
        val s = activeStream
        val elapsed = android.os.SystemClock.elapsedRealtime() - generateStart
        if (s != null && !paused) {
            val st = try { s.snapshot() } catch (_: Throwable) { return }
            val ahead = st.aheadMs / 1000.0
            val rate = st.realTimeFactor?.let { " · gen %.1fx".format(it) } ?: ""
            val stalls = " · refills ${st.underruns}"
            if (st.state == JitterBuffer.State.BUFFERING) {
                val target = if (st.targetMs < 0) null else st.targetMs / 1000.0
                val label = if (st.rebuffering) "Refilling buffer" else "Buffering"
                val frac = target?.let { if (it > 0) ahead / it else null }
                val line = if (target != null) "$label ${Tqdm.bar(frac)}\n%.1f/%.1f s%s%s".format(ahead, target, rate, stalls)
                    else "Generating whole text ${Tqdm.bar(null)}\n%.1f s so far%s".format(ahead, rate)
                progress.showFraction(frac, line)
                status.text = if (st.targetMs < 0) "Generating the whole text before playing..." else "Buffering so playback does not stutter..."
            } else if (st.state == JitterBuffer.State.PLAYING) {
                val cap = maxOf(st.targetMs, 2000L) / 1000.0
                progress.showFraction((ahead / cap).coerceIn(0.0, 1.0), "Playing ${Tqdm.bar((ahead / cap).coerceIn(0.0, 1.0))}\n%.1f s buffered ahead%s%s".format(ahead, rate, stalls))
                status.text = "Speaking..."
            }
        } else if (s == null && generating && Zonos3Engine.isEngineLoaded()) {
            progress.show(StepProgress("Generating first audio", elapsedMs = elapsed))
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
        input.isEnabled = !value
        autoEmotions.isEnabled = !value
        bufferSpinner.isEnabled = !value
        if (!value) { generating = false; activeStream = null; progress.hide() } else startTicker()
        view?.findViewById<View>(R.id.playback_controls)?.visibility = if (value) View.VISIBLE else View.GONE
        if (!value) { paused = false; pauseBtn.text = "Pause"; pauseBtn.isEnabled = false }
        stopBtn.isEnabled = value
        if (value) {
            pauseBtn.isEnabled = false
            pauseBtn.text = "Pause"
            autoResult = null
            view?.post {
                if (busy) view?.findViewById<View>(R.id.playback_controls)?.let {
                    it.requestRectangleOnScreen(android.graphics.Rect(0, 0, it.width, it.height), true)
                }
            }
        }
        updateManualControls()
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
        val manual = controlsString()
        val auto = autoEmotions.isChecked
        val emb = try { embedding() } catch (t: Throwable) {
            reportError(id, appCtx, "load saved voice", t); return
        }
        runningId = id
        Thread {
            var stream: AudioPlayer.Stream? = null
            try {
                val controls = resolveControls(id, appCtx, text, auto, manual)
                check(operation.get() == id) { "Stopped" }
                update(id) { rememberGeneration(text, controls); status.text = if (Zonos3Engine.isEngineLoaded()) "Generating..." else "Loading voice engine..." }
                val t0 = System.currentTimeMillis()
                generateStart = android.os.SystemClock.elapsedRealtime(); generating = true
                val mode = bufferMode()
                val learned = prefs.getInt("jitter_ms", BufferMode.AUTO.initialMs).coerceIn(BufferMode.AUTO.initialMs, 12000)
                val initialBuffer = if (mode == BufferMode.AUTO) learned else mode.initialMs
                // Strong expression steering can occasionally make the engine render a silent clip.
                // Retry with a new seed and gentler steering before telling the user it failed.
                val attempts = listOf(controls, scaleControls(controls, 0.5), "")
                    .filterIndexed { i, c -> i == 0 || c != controls }
                var lastFailure = "The engine returned silent audio."
                var played = false
                for ((n, ctl) in attempts.withIndex()) {
                    check(operation.get() == id) { "Playback interrupted" }
                    val stats = PcmStats()
                    val seed = Zonos3Engine.seed() + n * 7919
                    val ok = Zonos3Engine.synthesizeStreaming(appCtx, text, emb, ctl,
                        object : com.zyphra.zonos3.Zonos3.PcmListener {
                            override fun onChunk(pcm: FloatArray) {
                                check(operation.get() == id) { "Playback interrupted" }
                                stats.add(pcm)
                                if (pcm.isEmpty()) return
                                synchronized(player) {
                                    check(operation.get() == id) { "Playback interrupted" }
                                    if (stream == null) {
                                        stream = player.beginStream(appCtx, mode, initialBuffer)
                                        activeStream = stream
                                        val ms = System.currentTimeMillis() - t0
                                        Log.i(TAG, "first audio after ${ms}ms, buffer mode=$mode initial=${initialBuffer}ms")
                                        update(id) { pauseBtn.isEnabled = true }
                                    }
                                }
                                player.writeStream(stream!!, pcm)
                            }
                        }, seed, { p -> update(id) { progress.show(p) } })
                    check(ok) { Zonos3Engine.lastNativeError.ifEmpty { "Speech generation failed. Try Speak again." } }
                    val audible = stats.frames > 0 && stats.peak > 0.00001f
                    Log.i(TAG, "attempt ${n + 1}/${attempts.size} controls='$ctl' seed=$seed frames=${stats.frames} peak=${stats.peak} voice=${if (emb == null) "default" else "saved"}")
                    if (audible) { played = true; break }
                    lastFailure = "The engine returned silent audio (controls='$ctl', frames=${stats.frames})."
                    CrashLog.record(appCtx, "silent attempt ${n + 1}", IllegalStateException(lastFailure))
                    stream?.let { player.cancelStream(it) }; stream = null; activeStream = null
                    if (n < attempts.size - 1) update(id) { status.text = "Silent result, retrying (${n + 2}/${attempts.size})..." }
                }
                check(played) { "$lastFailure Tried ${attempts.size} times. Try Speak again." }
                val finished = stream ?: error("The engine returned no audio. Try Speak again.")
                val st = player.finishStream(finished)
                if (mode == BufferMode.AUTO) {
                    // Remember what this phone needed: grow after stalls, relax slowly when playback was smooth.
                    val next = if (st.underruns > 0) st.targetMs.toInt().coerceIn(BufferMode.AUTO.initialMs, 12000)
                        else (learned * 3 / 4).coerceAtLeast(BufferMode.AUTO.initialMs)
                    prefs.edit().putInt("jitter_ms", next).apply()
                }
                update(id) {
                    setBusy(false)
                    val rate = st.realTimeFactor?.let { " Generated at %.1fx real time.".format(it) } ?: ""
                    val stalls = if (st.underruns > 0) " Paused to refill the buffer ${st.underruns}x." else ""
                    status.text = "Done.$rate$stalls No sound? Check media volume and Bluetooth."
                }
            } catch (t: Throwable) {
                reportError(id, appCtx, "speak", t)
            } finally {
                // Only stop our own track, never a newer retry's stream.
                stream?.let { player.cancelStream(it) }
                finishCancelled(id)
            }
        }.start()
    }

    /** "id=dose;..." with every dose multiplied by factor. */
    private fun scaleControls(c: String, factor: Double): String =
        c.split(';').filter { it.contains('=') }.joinToString(";") {
            val (k, v) = it.split('=', limit = 2)
            "$k=${(v.toDoubleOrNull() ?: 0.0) * factor}"
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
        val manual = controlsString()
        val auto = autoEmotions.isChecked
        setBusy(true)
        status.text = "Rendering MP3. Please keep the app open..."
        runningId = id
        Thread {
            try {
                val controls = resolveControls(id, appCtx, text, auto, manual)
                check(operation.get() == id) { "Stopped" }
                update(id) { rememberGeneration(text, controls); status.text = "Rendering MP3..." }
                val wav = File(appCtx.cacheDir, "voice-reps-${System.currentTimeMillis()}.wav")
                val ok = Zonos3Engine.synthesizeToWav(appCtx, text, emb, wav, controls,
                    report = { p -> update(id) { progress.show(p) } }, stepIndex = 1, stepCount = 2)
                check(operation.get() == id) { wav.delete(); "Stopped" }
                check(ok && wav.length() > 44) { Zonos3Engine.lastNativeError.ifEmpty { "No audio was saved." } }
                val mp3 = File(appCtx.cacheDir, wav.nameWithoutExtension + ".mp3")
                val encoded = Mp3Encoder.wavToMp3(wav, mp3)
                wav.delete()
                check(encoded && mp3.length() > 0) { "MP3 encoding failed." }
                update(id) {
                    setBusy(false)
                    lastWav = mp3
                    status.text = "Saved ${mp3.name}"
                    share(mp3)
                }
            } catch (t: Throwable) { reportError(id, appCtx, "saveAndShare", t) }
            finally { finishCancelled(id) }
        }.start()
    }

    private fun share(clip: File) {
        try {
            val uri = FileProvider.getUriForFile(requireContext(),
                requireContext().packageName + ".files", clip)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share voice clip"))
        } catch (e: Exception) {
            toast("File saved: ${clip.absolutePath}")
        }
    }

    private fun rememberGeneration(text: String, controls: String) {
        lastSpokenText = text
        lastControls = controls
        lastVoiceName = if (selected == 0) "default" else voices.getOrNull(selected - 1)?.name
    }

    /** In-app report for offensive generated audio: opens the user's mail app with context prefilled. */
    private fun reportAudio() {
        val ctx = requireContext()
        val versionName = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (_: Exception) { "unknown" }
        val body = buildString {
            appendLine("Please describe what was offensive or harmful about the generated audio:")
            appendLine()
            appendLine()
            appendLine("--- Details (added by voice-reps, edit or remove as you like) ---")
            appendLine("App: voice-reps $versionName")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Voice: ${lastVoiceName ?: "none generated yet"}")
            appendLine("Controls: ${lastControls.ifEmpty { "none" }}")
            appendLine("Text that was spoken: ${lastSpokenText ?: "none generated yet"}")
            CrashLog.read(ctx)?.let { appendLine(); appendLine("Error log:"); appendLine(it) }
        }
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = android.net.Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(getString(R.string.report_email)))
            putExtra(Intent.EXTRA_SUBJECT, "voice-reps report: generated audio")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.report_audio)))
        } catch (e: Exception) {
            toast("No email app found. Please write to ${getString(R.string.report_email)}")
        }
    }

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()

    private fun finishCancelled(id: Int) {
        if (runningId == id) runningId = 0
        activity?.runOnUiThread {
            if (operation.get() != id && view != null && runningId == 0) {
                setBusy(false)
                status.text = "Stopped."
            }
        }
    }

    private fun interruptPlayback() {
        synchronized(player) {
            operation.incrementAndGet()
            player.stop()
        }
        if (view != null && busy) {
            pauseBtn.isEnabled = false
            stopBtn.isEnabled = false
            status.text = "Stopped when leaving the screen. Finishing current background step..."
        }
    }

    override fun onStop() { interruptPlayback(); super.onStop() }
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) interruptPlayback()
    }
    override fun onDestroyView() { interruptPlayback(); super.onDestroyView() }
}
