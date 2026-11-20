package com.example.jarvis

sealed class Command {
    data class SendMessage(val app: String, val name: String, val message: String) : Command()
    data class Call(val name: String) : Command()
    data class OpenApp(val appName: String) : Command()
    data class Click(val label: String) : Command()
    data class TypeText(val text: String) : Command()
    data class Scroll(val down: Boolean) : Command()
    data class Alarm(val hour: Int, val minute: Int) : Command()
    data class WebSearch(val query: String) : Command()
    data class Navigate(val place: String) : Command()
    data class PlayYoutube(val query: String) : Command()
    object Back : Command()
    object Home : Command()
    object Unknown : Command()
}

object CommandParser {
    private val I = RegexOption.IGNORE_CASE
    private const val APPS = "whatsapp|telegram|instagram|signal|messenger|sms|messages"
    private val appSuffix = Regex("""\s+(?:on|via|using|in|through)\s+($APPS)\s*$""", I)

    private fun r(p: String) = Regex(p, I)

    fun parse(spoken: String): Command {
        var t = spoken.trim()
            .replace(Regex("""\b([ap])\.m\.?""", I), "$1m")
            .replace(Regex("""[.!?]+$"""), "")
            .replace(Regex("""^\s*(?:hey\s+|ok\s+)?jarvis[,\s]*""", I), "")
            .trim()

        // ---- explicit SMS ----
        r("""^(?:send\s+)?(?:an?\s+)?(?:sms|text message)\s+to\s+(.+?)\s+(?:saying|that|says)\s+(.+)""").find(t)?.let {
            return Command.SendMessage("sms", it.groupValues[1].trim(), it.groupValues[2].trim())
        }

        // ---- messaging: which app? (default WhatsApp) ----
        var app = "whatsapp"
        appSuffix.find(t)?.let {
            app = it.groupValues[1].lowercase()
            t = t.removeRange(it.range).trim()
        }
        if (app == "messages") app = "sms"
        fun msg(name: String, m: String) = Command.SendMessage(app, name.trim(), m.trim())

        r("""^(?:send\s+|text\s+)?(?:an?\s+)?(?:whatsapp\s+)?message\s+to\s+(.+?)\s+(?:saying|that|says)\s+(.+)""").find(t)?.let { return msg(it.groupValues[1], it.groupValues[2]) }
        r("""^(?:whatsapp|message|text)\s+(.+?)\s+(?:saying|that|says)\s+(.+)""").find(t)?.let { return msg(it.groupValues[1], it.groupValues[2]) }
        r("""^tell\s+(.+?)\s+(?:that\s+)?(.+)""").find(t)?.let { return msg(it.groupValues[1], it.groupValues[2]) }
        r("""^send\s+(.+?)\s+to\s+(.+)""").find(t)?.let { return msg(it.groupValues[2], it.groupValues[1]) }

        // ---- calls ----
        r("""^(?:call|phone|dial)\s+(.+)""").find(t)?.let { return Command.Call(it.groupValues[1].trim()) }

        // ---- screen control (works inside ANY app) ----
        r("""^(?:click|tap|press)\s+(?:on\s+)?(.+)""").find(t)?.let { return Command.Click(it.groupValues[1].trim()) }
        r("""^(?:type|write)\s+(.+)""").find(t)?.let { return Command.TypeText(it.groupValues[1].trim()) }
        r("""^scroll\s+(down|up)""").find(t)?.let { return Command.Scroll(it.groupValues[1].lowercase() == "down") }
        if (r("""^(?:go\s+)?back$""").matches(t)) return Command.Back
        if (r("""^(?:go\s+)?home$""").matches(t)) return Command.Home

        // ---- apps & utilities ----
        r("""^open\s+(.+)""").find(t)?.let { return Command.OpenApp(it.groupValues[1].trim()) }
        r("""^set\s+(?:an?\s+)?alarm\s+(?:for|at)\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""").find(t)?.let {
            var h = it.groupValues[1].toInt()
            val m = it.groupValues[2].ifEmpty { "0" }.toInt()
            when (it.groupValues[3].lowercase()) {
                "pm" -> if (h < 12) h += 12
                "am" -> if (h == 12) h = 0
            }
            return Command.Alarm(h, m)
        }
        r("""^(?:navigate|directions?|take\s+me)\s+(?:to\s+)?(.+)""").find(t)?.let { return Command.Navigate(it.groupValues[1].trim()) }
        r("""^(?:search|google|look\s+up)\s+(?:for\s+)?(.+)""").find(t)?.let { return Command.WebSearch(it.groupValues[1].trim()) }
        r("""^play\s+(.+?)(?:\s+on\s+youtube)?$""").find(t)?.let { return Command.PlayYoutube(it.groupValues[1].trim()) }

        return Command.Unknown
    }
}
