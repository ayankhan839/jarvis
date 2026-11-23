package com.example.jarvis

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.widget.Toast
import java.util.Locale

/**
 * Hands-free mode: always listens (offline, on-device) for the word "Jarvis".
 * When it hears it, it beeps, listens for ONE command, runs it, then goes back to waiting.
 */
class WakeService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var porcupine: PorcupineManager? = null
    private var tts: TextToSpeech? = null
    private var voice: VoiceListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channelId = "jarvis_wake"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(channelId, "Jarvis hands-free", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Jarvis is listening")
            .setContentText("Say \"Jarvis\", then your command")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }

        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }
        voice = VoiceListener(
            this,
            onText = { engine().handle(it) },
            onStatus = { toast(it) },
            onFinished = { resumeWake() }
        )
        startWake()
    }

    // When the Jarvis Accessibility service is on, use it to open apps (Android allows that from the background).
    private fun engine() = JarvisEngine(JarvisAccessibilityService.instance ?: this) { say(it) }

    private fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun say(msg: String) {
        main.post {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
        }
    }

    private fun startWake() {
        val key = getSharedPreferences("jarvis", MODE_PRIVATE).getString("pv_key", "").orEmpty()
        if (key.isBlank()) { toast("Paste your Picovoice AccessKey in the Jarvis app first."); stopSelf(); return }
        try {
            porcupine = PorcupineManager.Builder()
                .setAccessKey(key)
                .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                .setSensitivity(0.7f)
                .build(applicationContext, PorcupineManagerCallback { main.post { onWake() } })
            porcupine?.start()
            toast("Hands-free is on. Say \"Jarvis\".")
        } catch (e: Exception) {
            toast("Wake word error: ${e.message}")
            stopSelf()
        }
    }

    private fun onWake() {
        try { porcupine?.stop() } catch (e: Exception) {}
        try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).startTone(ToneGenerator.TONE_PROP_BEEP, 150) } catch (e: Exception) {}
        main.postDelayed({ voice?.start() }, 250)   // listen for the command
    }

    private fun resumeWake() {
        main.postDelayed({ try { porcupine?.start() } catch (e: Exception) {} }, 800)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        try { porcupine?.stop(); porcupine?.delete() } catch (e: Exception) {}
        voice?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
