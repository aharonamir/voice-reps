package com.voicereps.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        setContentView(R.layout.activity_main)

        // FragmentManager restores the existing screens in super.onCreate(). Adding a
        // second set here leaves overlapping views and independent playback state.
        val fm = supportFragmentManager
        val voices = fm.findFragmentByTag("voices") ?: VoicesFragment()
        val record = fm.findFragmentByTag("record") ?: RecordFragment()
        val speak = fm.findFragmentByTag("speak") ?: SpeakFragment()
        val screens = listOf(voices to "voices", record to "record", speak to "speak")
        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        val selectedId = savedInstanceState?.getInt("selected_tab", R.id.nav_voices) ?: R.id.nav_voices
        fun screen(id: Int): Fragment = when (id) {
            R.id.nav_record -> record
            R.id.nav_speak -> speak
            else -> voices
        }
        var active = screen(selectedId)
        fm.beginTransaction().apply {
            for ((fragment, tag) in screens) {
                if (!fragment.isAdded) add(R.id.fragment_container, fragment, tag)
                if (fragment === active) show(fragment) else hide(fragment)
            }
        }.commitNow()
        nav.selectedItemId = selectedId
        nav.setOnItemSelectedListener { item ->
            val next = screen(item.itemId)
            if (next !== active) {
                fm.beginTransaction().hide(active).show(next).commit()
                active = next
                if (next is SpeakFragment) next.refreshVoices()
                if (next is VoicesFragment) next.refreshVoices()
            }
            true
        }

        // Finish any voice whose setup was interrupted last session (process killed
        // mid-embedding), so it appears in the Speak picker without manual retry.
        VoiceSetup.finishPending(applicationContext, onReady = {
            (fm.findFragmentByTag("speak") as? SpeakFragment)?.refreshVoices()
            (fm.findFragmentByTag("voices") as? VoicesFragment)?.refreshVoices()
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("selected_tab", findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId)
        super.onSaveInstanceState(outState)
    }
}
