package com.voicereps.app

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.io.File

class RecordFragment : Fragment() {

    private lateinit var store: VoiceStore
    private var recorder: AudioRecorder? = null
    private var lastWav: File? = null
    private var currentWav: File? = null

    private lateinit var consentCard: View
    private lateinit var recordCard: View
    private lateinit var levelBar: ProgressBar
    private lateinit var timer: TextView
    private lateinit var recordBtn: Button
    private lateinit var createBtn: Button
    private lateinit var status: TextView

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) buildVoiceFromUpload(uri)
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else toast("Microphone permission is needed to record a voice")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View =
        inflater.inflate(R.layout.fragment_record, container, false)

    override fun onViewCreated(view: View, saved: Bundle?) {
        store = VoiceStore(requireContext())
        consentCard = view.findViewById(R.id.consent_card)
        recordCard = view.findViewById(R.id.record_card)
        levelBar = view.findViewById(R.id.record_level)
        timer = view.findViewById(R.id.record_timer)
        recordBtn = view.findViewById(R.id.record_button)
        createBtn = view.findViewById(R.id.create_voice_button)
        status = view.findViewById(R.id.record_status)

        view.findViewById<Button>(R.id.consent_agree).setOnClickListener {
            consentCard.visibility = View.GONE
            recordCard.visibility = View.VISIBLE
        }
        view.findViewById<Button>(R.id.upload_button).setOnClickListener {
            pickAudio.launch(arrayOf("audio/*"))
        }
        recordBtn.setOnClickListener {
            if (recorder?.isRecording == true) stopRecording() else requestMic()
        }
        createBtn.setOnClickListener { promptNameAndCreate() }
    }

    private fun requestMic() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) startRecording()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startRecording() {
        val wav = File(requireContext().cacheDir, "record_${System.currentTimeMillis()}.wav")
        currentWav = wav
        val r = AudioRecorder(wav)
        recorder = r
        val t0 = System.currentTimeMillis()
        r.start(
            onLevel = { level -> activity?.runOnUiThread {
                levelBar.progress = (level * 100).toInt().coerceAtMost(100)
                timer.text = "${(System.currentTimeMillis() - t0) / 1000}s / 60s"
            } },
            onMaxReached = { activity?.runOnUiThread { stopRecording() } })
        recordBtn.text = "Stop"
        status.text = "Recording... read the phrases above naturally"
        createBtn.isEnabled = false
    }

    private fun stopRecording() {
        val r = recorder ?: return
        r.stop()
        lastWav = currentWav
        recordBtn.text = "Record"
        status.text = "Recording saved. Name it to create the voice."
        createBtn.isEnabled = true
    }

    private fun promptNameAndCreate() {
        val wav = lastWav ?: return
        val input = EditText(requireContext()).apply { hint = "e.g. Amir, Mom, Narrator" }
        AlertDialog.Builder(requireContext())
            .setTitle("Name this voice")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Voice ${System.currentTimeMillis() % 1000}" }
                createVoice(name, wav, "record")
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun buildVoiceFromUpload(uri: android.net.Uri) {
        status.text = "Decoding audio..."
        Thread {
            val pcm = AudioDecoder.decodeToFloat(requireContext(), uri)
            if (pcm == null || pcm.size < 44100) {
                activity?.runOnUiThread { status.text = "Could not decode that file (need at least 1s of audio)" }
                return@Thread
            }
            // persist a wav copy for playback
            val wav = File(requireContext().cacheDir, "upload_${System.currentTimeMillis()}.wav")
            writeWav(wav, pcm)
            activity?.runOnUiThread {
                val input = EditText(requireContext()).apply { hint = "e.g. Amir, Mom, Narrator" }
                AlertDialog.Builder(requireContext())
                    .setTitle("Name this voice")
                    .setView(input)
                    .setPositiveButton("Create") { _, _ ->
                        val name = input.text.toString().trim().ifEmpty { "Voice ${System.currentTimeMillis() % 1000}" }
                        createVoice(name, wav, "upload")
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        }.start()
    }

    private fun createVoice(name: String, wav: File, source: String) {
        status.text = "Building voice profile for \"$name\"..."
        createBtn.isEnabled = false
        Thread {
            val pcm = AudioDecoder.decodeWavFile(wav)
            val emb = pcm?.let { Zonos3Engine.embedVoice(requireContext(), it, 44100) }
            activity?.runOnUiThread {
                if (emb == null) {
                    status.text = "Failed to build the voice profile. Try a clearer recording."
                    createBtn.isEnabled = true
                } else {
                    store.add(name, source, wav, emb)
                    status.text = "Voice \"$name\" is ready. Try it in the Speak tab."
                    createBtn.isEnabled = true
                    toast("Voice \"$name\" created")
                }
            }
        }.start()
    }

    private fun writeWav(file: File, pcm: FloatArray) {
        java.io.RandomAccessFile(file, "rw").use { raf ->
            val dataLen = pcm.size * 2
            raf.writeBytes("RIFF"); raf.writeInt(java.lang.Integer.reverseBytes(36 + dataLen))
            raf.writeBytes("WAVEfmt "); raf.writeInt(java.lang.Integer.reverseBytes(16))
            raf.writeShort(java.lang.Integer.reverseBytes(1) ushr 16)
            raf.writeShort(java.lang.Integer.reverseBytes(1) ushr 16)
            raf.writeInt(java.lang.Integer.reverseBytes(44100)); raf.writeInt(java.lang.Integer.reverseBytes(88200))
            raf.writeShort(java.lang.Integer.reverseBytes(2) ushr 16)
            raf.writeShort(java.lang.Integer.reverseBytes(16) ushr 16)
            raf.writeBytes("data"); raf.writeInt(java.lang.Integer.reverseBytes(dataLen))
            val b = ByteArray(pcm.size * 2)
            for (i in pcm.indices) {
                val v = (pcm[i].coerceIn(-1f, 1f) * 32767).toInt()
                b[i * 2] = (v and 0xFF).toByte(); b[i * 2 + 1] = (v shr 8 and 0xFF).toByte()
            }
            raf.write(b)
        }
    }

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() { recorder?.stop(); super.onDestroyView() }
}
