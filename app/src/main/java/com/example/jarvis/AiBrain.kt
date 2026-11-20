package com.example.jarvis

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Config {
    // Free key from https://aistudio.google.com/apikey  (leave blank to disable the AI brain)
    // Never publish this file or the APK with your real key inside.
    const val GEMINI_API_KEY = ""
    const val GEMINI_MODEL = "gemini-2.5-flash"   // change if Google retires this name
}

/** Only YOUR SPOKEN SENTENCE is sent to Gemini, never your screen or contacts. */
object AiBrain {
    private const val PROMPT = """You are Jarvis, a phone voice assistant.
If the user wants a phone action, reply with ONLY one command line in exactly one of these forms:
send <message> to <name> on <whatsapp|telegram|instagram|sms>
call <name>
open <app>
click <button text>
type <text>
scroll down
scroll up
go back
go home
set alarm for <h:mm am/pm>
search <query>
navigate to <place>
play <song or video> on youtube
Otherwise answer in at most two short plain sentences."""

    fun ask(userText: String): String? = try {
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/${Config.GEMINI_MODEL}:generateContent")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-goog-api-key", Config.GEMINI_API_KEY)
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.doOutput = true
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", PROMPT))))
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", userText)))))
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val resp = conn.inputStream.bufferedReader().readText()
        JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim()
    } catch (e: Exception) { null }
}
