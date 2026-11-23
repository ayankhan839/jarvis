package com.example.jarvis

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tts: TextToSpeech
    private lateinit var status: TextView
    private lateinit var listener: VoiceListener
    private lateinit var engine: JarvisEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)

        status = TextView(this).apply {
            text = "HANDS-FREE: paste your free Picovoice key below, tap Start, then just say \"Jarvis\" and your command, e.g. \"Jarvis ... open YouTube\"."
            textSize = 15f
            gravity = Gravity.CENTER
        }
        val keyInput = EditText(this).apply {
            hint = "Picovoice AccessKey (free: console.picovoice.ai)"
            setSingleLine()
            setText(prefs.getString("pv_key", ""))
        }
        val startWake = Button(this).apply {
            text = "Start hands-free (say \"Jarvis\")"
            setOnClickListener {
                val k = keyInput.text.toString().trim()
                if (k.isEmpty()) { status.text = "Paste your Picovoice AccessKey first."; return@setOnClickListener }
                prefs.edit().putString("pv_key", k).apply()
                startForegroundService(Intent(this@MainActivity, WakeService::class.java))
                status.text = "Hands-free is ON. Say \"Jarvis\", wait for the beep, then say your command."
            }
        }
        val stopWake = Button(this).apply {
            text = "Stop hands-free"
            setOnClickListener {
                stopService(Intent(this@MainActivity, WakeService::class.java))
                status.text = "Hands-free is OFF."
            }
        }
        val input = EditText(this).apply {
            hint = "Or type a command here"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        val run = Button(this).apply {
            text = "Run typed command"
            setOnClickListener {
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) { engine.handle(t); input.setText("") }
            }
        }
        input.setOnEditorActionListener { _, _, _ -> run.performClick(); true }
        val talk = Button(this).apply { text = "Test voice here"; setOnClickListener { listener.start() } }
        val access = Button(this).apply {
            text = "Turn on Jarvis (Accessibility)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            addView(status); addView(keyInput); addView(startWake); addView(stopWake)
            addView(input); addView(run); addView(talk); addView(access)
        }
        setContentView(ScrollView(this).apply { addView(column) })

        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts.language = Locale.getDefault() }
        engine = JarvisEngine(this) { msg ->
            status.text = msg
            tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
        }
        listener = VoiceListener(this, onText = { engine.handle(it) }, onStatus = { status.text = it })

        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS,
                Manifest.permission.POST_NOTIFICATIONS
            ), 1
        )
    }

    override fun onDestroy() {
        listener.destroy()
        tts.shutdown()
        super.onDestroy()
    }
}
