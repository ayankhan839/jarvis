package com.example.jarvis

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tts: TextToSpeech
    private lateinit var status: TextView
    private lateinit var listener: VoiceListener

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            text = "1) Allow permissions\n2) Tap \"Turn on Jarvis\" and enable it\n3) A blue \"J\" button appears over every app.\nTap it and speak:\n\"Send hey to Rahul on Telegram\"\n\"Call Mom\" • \"Open Instagram\"\n\"Scroll down\" • \"Click Like\""
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val talk = Button(this).apply { text = "Test voice here"; setOnClickListener { listener.start() } }
        val access = Button(this).apply {
            text = "Turn on Jarvis (Accessibility)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            addView(status); addView(talk); addView(access)
        })

        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts.language = Locale.getDefault() }
        val engine = JarvisEngine(this) { msg ->
            status.text = msg
            tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
        }
        listener = VoiceListener(this, onText = { engine.handle(it) }, onStatus = { status.text = it })

        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS
            ), 1
        )
    }

    override fun onDestroy() {
        listener.destroy()
        tts.shutdown()
        super.onDestroy()
    }
}
