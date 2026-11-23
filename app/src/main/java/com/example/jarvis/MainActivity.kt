package com.example.jarvis

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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

    private val navy = Color.rgb(8, 17, 32)
    private val panel = Color.rgb(19, 34, 55)
    private val cyan = Color.rgb(0, 210, 230)
    private val white = Color.rgb(238, 246, 255)
    private val muted = Color.rgb(155, 176, 199)

    private fun rounded(color: Int, radius: Float = 18f) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius * resources.displayMetrics.density
    }

    private fun actionButton(label: String, color: Int, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            textSize = 15f
            isAllCaps = false
            setTextColor(if (color == cyan) navy else white)
            background = rounded(color, 14f)
            setPadding(18, 12, 18, 12)
            setOnClickListener { onClick() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            text = "Jarvis is ready. Tap Speak and say a command."
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(white)
            setPadding(18, 22, 18, 22)
            background = rounded(panel)
        }

        val input = EditText(this).apply {
            hint = "Try: Call Rehan / Open Instagram"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_GO
            textSize = 16f
            setTextColor(white)
            setHintTextColor(muted)
            setPadding(16, 14, 16, 14)
            background = rounded(Color.rgb(28, 45, 68), 14f)
        }
        val run = actionButton("Run command", cyan) {
            val command = input.text.toString().trim()
            if (command.isNotEmpty()) { engine.handle(command); input.setText("") }
        }
        input.setOnEditorActionListener { _, _, _ -> run.performClick(); true }

        val talk = actionButton("🎙  Speak to Jarvis", Color.rgb(0, 112, 145)) {
            listener.start()
        }
        val access = actionButton("Enable Jarvis accessibility", Color.rgb(42, 62, 91)) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val title = TextView(this).apply {
            text = "JARVIS"
            textSize = 30f
            letterSpacing = 0.18f
            gravity = Gravity.CENTER
            setTextColor(cyan)
        }
        val subtitle = TextView(this).apply {
            text = "Your voice-controlled phone assistant"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(muted)
        }
        val hint = TextView(this).apply {
            text = "CONTACT SAFETY  •  APP CONTROL  •  VOICE COMMANDS"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(muted)
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 42, 24, 32)
            setBackgroundColor(navy)
            addView(title, LinearLayout.LayoutParams(-1, -2))
            addView(subtitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6 })
            addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 24; bottomMargin = 18 })
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 20 })
            addView(input, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 })
            addView(run, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 })
            addView(talk, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 })
            addView(access, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(navy)
            isFillViewport = true
            addView(column)
        })

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
