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
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import java.util.Locale

/** Listens to the mic once and returns the recognised text. */
class VoiceListener(
    private val ctx: Context,
    private val onText: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onFinished: () -> Unit = {}
) {
    private var rec: SpeechRecognizer? = null

    private fun errorText(e: Int) = when (e) {
        1 -> "Network timeout. Check your internet and try again."
        2 -> "Network error. Check your internet and try again."
        3 -> "Microphone/audio problem. Close other apps using the mic."
        4 -> "Speech server error. Try again in a moment."
        5 -> "Speech client error. Try again."
        6 -> "I heard nothing. Speak right after you see Listening."
        7 -> "I couldn't understand that. Speak clearly and try again."
        8 -> "Speech service is busy. Wait a moment and try again."
        9 -> "Microphone permission is off. Settings > Apps > Jarvis > Permissions > Microphone > Allow."
        10 -> "Too many requests. Wait a moment and try again."
        11 -> "Speech service disconnected. Try again."
        12 -> "Language not supported. In the Google app: Settings > Voice > Languages, add English."
        13 -> "Language not available. Turn on internet, or in the Google app: Settings > Voice > Offline speech recognition, download English."
        else -> "Voice error $e. Try again."
    }

    /** lang = null uses the phone's own language. If that is unavailable we retry once with en-US. */
    fun start(lang: String? = null, retried: Boolean = false) {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onStatus("Open the Jarvis app once and allow microphone access.")
            onFinished()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            onStatus("No speech service on this phone. Install or enable the Google app.")
            onFinished()
            return
        }
        rec?.destroy()
        rec = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val choices = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    val confidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    // Prefer the recognizer's highest-confidence alternative, not any lower-ranked
                    // alternative that happens to match a broad command pattern.
                    val bestIndex = if (confidence != null && confidence.size == choices.size) {
                        confidence.indices.maxByOrNull { confidence[it] } ?: 0
                    } else 0
                    val best = choices.getOrNull(bestIndex)
                    if (best != null) onText(best) else onStatus("I didn't catch that.")
                    onFinished()
                }
                override fun onError(error: Int) {
                    if ((error == 12 || error == 13) && !retried) { start("en-US", true); return }
                    onStatus(errorText(error))
                    onFinished()
                }
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
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang ?: Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            })
        }
    }

    fun destroy() { rec?.destroy() }
}

/** Understands a command and makes the phone do it. */
class JarvisEngine(private val ctx: Context, private val say: (String) -> Unit) {

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
    private val whatsappPackages = listOf("com.whatsapp", "com.whatsapp.w4b")

    private fun sendMessage(c: Command.SendMessage) {
        if (c.message.isBlank()) { say("What should the message say?"); return }
        when (c.app) {
            "whatsapp" -> sendWhatsApp(c.name, c.message)
            "sms" -> sendSms(c.name, c.message)
            else -> sendViaShare(c)
        }
    }

    /** The chat is opened by the contact's PHONE NUMBER, so it can't open the wrong person's chat. */
    private fun sendWhatsApp(name: String, message: String) {
        val (fullName, number) = contactOrNull(name) ?: return
        val digits = toE164(number)?.removePrefix("+")
        if (digits == null) { say("$fullName's number doesn't look valid."); return }
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$digits&text=${Uri.encode(message)}")
        for (pkg in whatsappPackages) {
            try {
                // the tap is only allowed inside WhatsApp itself, never in another app
                if (svc() != null) JarvisAccessibilityService.run(
                    listOf(Step(listOf("send"), exact = true, viewIdSuffix = "send", requiredText = message, requiredRecipient = fullName)),
                    setOf(pkg)
                )
                launch(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg))
                say(if (svc() != null) "Opening the chat with $fullName. I'll send only after checking the chat and message on screen." else "Opened chat with $fullName. Turn on Jarvis in Accessibility to send automatically.")
                return
            } catch (e: ActivityNotFoundException) {
                JarvisAccessibilityService.cancel()
            }
        }
        say("WhatsApp is not installed.")
    }

    private fun sendSms(name: String, message: String) {
        if (!has(Manifest.permission.SEND_SMS)) { say("SMS permission is off. Allow it in the Jarvis app to send texts automatically."); return }
        val (fullName, number) = contactOrNull(name) ?: return
        val e164 = toE164(number) ?: run { say("$fullName's number doesn't look valid."); return }
        try {
            val sms = ctx.getSystemService(SmsManager::class.java)
            sms.sendMultipartTextMessage(e164, null, sms.divideMessage(message), null, null)
            say("SMS handed to the network for $fullName. I can't confirm delivery.")
        } catch (e: Exception) {
            say("Couldn't send the SMS: ${e.message}")
        }
    }

    /**
     * Telegram / Instagram / Signal / Messenger have no reliable way to pick one person and press Send,
     * so Jarvis opens the app with your message ready and YOU choose the person and tap Send.
     */
    private fun sendViaShare(c: Command.SendMessage) {
        val pkg = sharePackages[c.app] ?: run { say("I don't support ${c.app} yet."); return }
        try {
            launch(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, c.message).setPackage(pkg))
            say("Opened ${c.app} with your message. Choose ${c.name} and tap Send yourself; I won't guess the recipient.")
        } catch (e: ActivityNotFoundException) {
            say("${c.app} is not installed.")
        }
    }

    // ---------- calls, apps, utilities ----------
    private fun call(name: String) {
        val (fullName, number) = contactOrNull(name) ?: return
        val uri = Uri.parse("tel:" + Uri.encode(number))
        if (!has(Manifest.permission.CALL_PHONE)) {
            say("Phone permission is required to call automatically. Allow it in Android Settings > Apps > Jarvis > Permissions.")
            return
        }
        try {
            launch(Intent(Intent.ACTION_CALL, uri))
            say("Calling $fullName.")
        } catch (e: SecurityException) {
            say("Android blocked the call. Check Jarvis Phone permission and try again.")
        } catch (e: ActivityNotFoundException) {
            say("No phone app is available to place this call.")
        }
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private val appAliases = mapOf(
        "insta" to "instagram", "yt" to "youtube", "you tube" to "youtube",
        "fb" to "facebook", "gpay" to "googlepay", "maps" to "maps",
        "mail" to "gmail", "camera" to "camera", "playstore" to "googleplay"
    )

    private val knownAppPackages = mapOf(
        "youtube" to listOf("com.google.android.youtube"),
        "googleplay" to listOf("com.android.vending"),
        "chrome" to listOf("com.android.chrome", "com.chrome.beta"),
        "gmail" to listOf("com.google.android.gm"),
        "maps" to listOf("com.google.android.apps.maps"),
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "instagram" to listOf("com.instagram.android"),
        "facebook" to listOf("com.facebook.katana"),
        "spotify" to listOf("com.spotify.music")
    )

    private fun openApp(appName: String) {
        val pm = ctx.packageManager
        val wanted = norm(appName).let { appAliases[it] ?: it }
        val known = knownAppPackages[wanted]?.firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
        if (known != null) {
            launch(known)
            say("Opening $appName.")
            return
        }
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = pm.queryIntentActivities(main, PackageManager.MATCH_DEFAULT_ONLY)
        val match = activities.firstOrNull { norm(it.loadLabel(pm).toString()) == wanted }
            ?: activities.firstOrNull { norm(it.loadLabel(pm).toString()).contains(wanted) }
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
    /**
     * Finds ONE contact. If several different people match, Jarvis asks you to say the full name
     * instead of guessing. Prefers an exact name match and a mobile number.
     */
    private fun contactOrNull(name: String): Pair<String, String>? {
        if (!has(Manifest.permission.READ_CONTACTS)) {
            say("Allow contacts permission in the Jarvis app first.")
            return null
        }

        data class Row(val id: Long, val name: String, val number: String, val mobile: Boolean)
        val rows = mutableListOf<Row>()
        ctx.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(Row(cursor.getLong(0), cursor.getString(1).orEmpty(),
                    cursor.getString(2).orEmpty(), cursor.getInt(3) == Phone.TYPE_MOBILE))
            }
        }

        val wanted = normalizeContactName(name)
        if (wanted.isBlank()) {
            say("Please say the contact name again.")
            return null
        }

        data class Match(val rows: List<Row>, val score: Int)
        val wantedTokens = wanted.split(" ").filter { it.isNotBlank() }
        val matches = rows.groupBy { it.id }.values.mapNotNull { person ->
            val actual = normalizeContactName(person.first().name)
            val actualTokens = actual.split(" ").filter { it.isNotBlank() }
            val score = when {
                actual == wanted -> 0
                actualTokens.containsAll(wantedTokens) -> 1
                else -> {
                    val maxDistance = if (wantedTokens.any { it.length >= 7 }) 2 else 1
                    val distances = wantedTokens.map { spokenToken ->
                        actualTokens.map { editDistance(spokenToken, it) }.minOrNull() ?: 99
                    }
                    if (distances.isNotEmpty() && distances.all { it <= maxDistance }) {
                        5 + distances.sum() + (actualTokens.size - wantedTokens.size).coerceAtLeast(0)
                    } else if (wantedTokens.size == 1) {
                        val close = actualTokens.map { editDistance(wantedTokens[0], it) }.minOrNull() ?: 99
                        if (close <= maxDistance) 10 + close else null
                    } else null
                }
            }
            score?.let { Match(person, it) }
        }.sortedBy { it.score }

        if (matches.isEmpty()) {
            say("I couldn't find $name in your contacts. Please say the name again.")
            return null
        }

        val bestScore = matches.first().score
        val best = matches.filter { it.score == bestScore }
        if (best.size != 1) {
            val names = best.map { it.rows.first().name }.distinct().take(5)
            say("I found multiple matching contacts: ${names.joinToString(", ")}. Please say the full name.")
            return null
        }

        val person = best.first().rows
        val number = person.firstOrNull { it.mobile && it.number.isNotBlank() }
            ?: person.firstOrNull { it.number.isNotBlank() }
        if (number == null) {
            say("${person.first().name} has no phone number saved.")
            return null
        }
        return number.name to number.number
    }

    private fun normalizeContactName(value: String): String =
        value.lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() || it.isWhitespace() }
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }

    /** International format (+country code). Numbers saved with a + keep their own country; others use this phone's country. */
    private fun toE164(raw: String): String? {
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val region = (tm?.simCountryIso?.takeIf { it.isNotBlank() }
            ?: tm?.networkCountryIso?.takeIf { it.isNotBlank() }
            ?: Locale.getDefault().country.takeIf { it.isNotBlank() }
            ?: "IN").uppercase()
        return PhoneNumberUtils.formatNumberToE164(raw, region)
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
