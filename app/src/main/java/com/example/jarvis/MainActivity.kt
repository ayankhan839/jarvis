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
        status = TextView(this).apply {
            text = "Jarvis is ready. Tap Speak and say a command."
            textSize = 16f
            gravity = Gravity.CENTER
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
        val talk = Button(this).apply { text = "Speak a command"; setOnClickListener { listener.start() } }
        val access = Button(this).apply {
            text = "Turn on Jarvis (Accessibility)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            addView(status)
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
