package com.example.jarvis

import android.Manifest
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.SmsManager

/** Listens to the mic once and returns the recognised text. */
class VoiceListener(
    private val ctx: Context,
    private val onText: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private var rec: SpeechRecognizer? = null

    fun start() {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onStatus("Open the Jarvis app once and allow microphone access.")
            return
        }
        rec?.destroy()
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { onStatus("No speech service on this phone. Install or enable the Google app."); return }
        rec = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (t != null) onText(t) else onStatus("I didn't catch that.")
                }
                override fun onError(error: Int) { onStatus("Voice error $error. 9=mic permission, 7=no speech heard, 2/5=internet, 12/13=speech service missing") }
                override fun onReadyForSpeech(params: Bundle?) { onStatus("Listening...") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            })
        }
    }

    fun destroy() { rec?.destroy() }
}

/** Understands a command and makes the phone do it. */
class JarvisEngine(private val ctx: Context, private val say: (String) -> Unit) {

    private val defaultCountryCode = "91"   // change if you're not in India

    private val sharePackages = mapOf(
        "telegram" to "org.telegram.messenger",
        "instagram" to "com.instagram.android",
        "signal" to "org.thoughtcrime.securesms",
        "messenger" to "com.facebook.orca"
    )

    private fun svc() = JarvisAccessibilityService.instance
    private fun launch(i: Intent) = ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    private fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun handle(spoken: String) {
        say("You said: $spoken")
        when (val c = CommandParser.parse(spoken)) {
            is Command.SendMessage -> sendMessage(c)
            is Command.Call -> call(c.name)
            is Command.OpenApp -> openApp(c.appName)
            is Command.Click -> needService { say(if (it.clickLabel(c.label)) "Tapped ${c.label}." else "I can't see \"${c.label}\" on screen.") }
            is Command.TypeText -> needService { say(if (it.typeText(c.text)) "Typed it." else "I couldn't find a text box.") }
            is Command.Scroll -> needService { it.scroll(c.down) }
            Command.Back -> needService { it.back() }
            Command.Home -> needService { it.home() }
            is Command.Alarm -> setAlarm(c.hour, c.minute)
            is Command.WebSearch -> launch(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, c.query))
            is Command.Navigate -> navigate(c.place)
            is Command.PlayYoutube -> playYoutube(c.query)
            Command.Unknown -> askAi(spoken)
        }
    }

    private fun needService(block: (JarvisAccessibilityService) -> Unit) {
        val s = svc()
        if (s == null) say("Turn on Jarvis in Accessibility settings first.") else block(s)
    }

    // ---------- messaging ----------
    private fun sendMessage(c: Command.SendMessage) {
        when (c.app) {
            "whatsapp" -> sendWhatsApp(c.name, c.message)
            "sms" -> sendSms(c.name, c.message)
            else -> sendViaShare(c)
        }
    }

    private fun sendWhatsApp(name: String, message: String) {
        val (fullName, number) = contactOrNull(name) ?: return
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=${normalize(number)}&text=${Uri.encode(message)}")
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp") // com.whatsapp.w4b for Business
        try {
            if (svc() != null) JarvisAccessibilityService.run(listOf(Step(listOf("send"), exact = true, viewIdSuffix = "send")))
            launch(intent)
            say(if (svc() != null) "Sending to $fullName." else "Opened chat with $fullName. Enable Jarvis in Accessibility to auto-send.")
        } catch (e: ActivityNotFoundException) { say("WhatsApp is not installed.") }
    }

    private fun sendSms(name: String, message: String) {
        if (!has(Manifest.permission.SEND_SMS)) { say("Allow SMS permission in the Jarvis app first."); return }
        val (fullName, number) = contactOrNull(name) ?: return
        val sms = ctx.getSystemService(SmsManager::class.java)
        sms.sendMultipartTextMessage(normalize(number).let { "+$it" }, null, sms.divideMessage(message), null, null)
        say("SMS sent to $fullName.")
    }

    /** Telegram / Instagram / Signal / Messenger: share the text, pick the person, tap Send. Best effort. */
    private fun sendViaShare(c: Command.SendMessage) {
        val pkg = sharePackages[c.app] ?: run { say("I don't support ${c.app} yet."); return }
        needService {
            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, c.message).setPackage(pkg)
            try {
                JarvisAccessibilityService.run(listOf(Step(listOf(c.name)), Step(listOf("send"), exact = true)))
                launch(i)
                say("Sending to ${c.name} on ${c.app}.")
            } catch (e: ActivityNotFoundException) {
                JarvisAccessibilityService.cancel()
                say("${c.app} is not installed.")
            }
        }
    }

    // ---------- calls, apps, utilities ----------
    private fun call(name: String) {
        val (fullName, number) = contactOrNull(name) ?: return
        val uri = Uri.parse("tel:$number")
        if (has(Manifest.permission.CALL_PHONE)) launch(Intent(Intent.ACTION_CALL, uri)) else launch(Intent(Intent.ACTION_DIAL, uri))
        say("Calling $fullName.")
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private val appAliases = mapOf(
        "insta" to "instagram", "yt" to "youtube", "fb" to "facebook",
        "gpay" to "googlepay", "maps" to "maps", "mail" to "gmail", "camera" to "camera"
    )

    private fun openApp(appName: String) {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val wanted = norm(appName).let { appAliases[it] ?: it }   // "playstore" matches "Play Store"
        val match = pm.queryIntentActivities(main, 0).firstOrNull { norm(it.loadLabel(pm).toString()).contains(wanted) }
        val intent = match?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
        if (intent != null) { launch(intent); say("Opening ${match.loadLabel(pm)}.") }
        else say("I couldn't find an app called $appName on this phone.")
    }

    private fun setAlarm(h: Int, m: Int) {
        launch(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        say("Alarm set for %d:%02d.".format(h, m))
    }

    private fun navigate(place: String) {
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(place)}")).setPackage("com.google.android.apps.maps"))
        say("Navigating to $place.")
    }

    private fun playYoutube(q: String) {
        val web = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(q)}")
        try { launch(Intent(Intent.ACTION_VIEW, web).setPackage("com.google.android.youtube")) }
        catch (e: ActivityNotFoundException) { launch(Intent(Intent.ACTION_VIEW, web)) }
        say("Searching YouTube for $q.")
    }

    // ---------- contacts ----------
    private fun contactOrNull(name: String): Pair<String, String>? {
        if (!has(Manifest.permission.READ_CONTACTS)) { say("Allow contacts permission in the Jarvis app first."); return null }
        val cur = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null
        )
        cur?.use { if (it.moveToFirst()) return it.getString(0) to it.getString(1) }
        say("I couldn't find $name in your contacts.")
        return null
    }

    private fun normalize(raw: String): String {
        var n = raw.filter { it.isDigit() }
        if (n.startsWith("0")) n = n.trimStart('0')
        if (n.length == 10) n = defaultCountryCode + n
        return n
    }

    // ---------- optional AI brain ----------
    private fun askAi(spoken: String) {
        if (Config.GEMINI_API_KEY.isBlank()) { say("Sorry, I don't understand that yet."); return }
        say("Thinking...")
        Thread {
            val reply = AiBrain.ask(spoken)
            Handler(Looper.getMainLooper()).post {
                when {
                    reply == null -> say("I couldn't reach the AI.")
                    CommandParser.parse(reply) != Command.Unknown -> handle(reply)
                    else -> say(reply)
                }
            }
        }.start()
    }
}
