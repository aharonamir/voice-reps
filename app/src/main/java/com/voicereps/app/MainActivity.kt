package com.voicereps.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private val voices = VoicesFragment()
    private val record = RecordFragment()
    private val speak = SpeakFragment()
    private var active: Fragment = voices

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        supportFragmentManager.beginTransaction()
            .add(R.id.fragment_container, speak, "speak").hide(speak)
            .add(R.id.fragment_container, record, "record").hide(record)
            .add(R.id.fragment_container, voices, "voices")
            .commit()

        findViewById<BottomNavigationView>(R.id.bottom_nav).setOnItemSelectedListener { item ->
            val next = when (item.itemId) {
                R.id.nav_record -> record
                R.id.nav_speak -> speak
                else -> voices
            }
            if (next != active) {
                supportFragmentManager.beginTransaction().hide(active).show(next).commit()
                active = next
                if (next is SpeakFragment) next.refreshVoices()
                if (next is VoicesFragment) next.refreshVoices()
            }
            true
        }
    }
}
