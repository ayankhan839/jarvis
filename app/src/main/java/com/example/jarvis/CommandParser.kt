
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

/** Parses direct commands before falling back to the optional AI brain. */
object CommandParser {

    private val I = RegexOption.IGNORE_CASE

    private const val APPS =
        "whatsapp|telegram|instagram|signal|messenger|sms|messages"

    private fun r(pattern: String) = Regex(pattern, I)

    fun parse(spoken: String): Command {

        var t = spoken.trim()
            .replace(Regex("""\b([ap])\.m\.?""", I), "$1m")
            .replace(Regex("""[.!?]+$"""), "")
            .replace(
                Regex("""^\s*(?:hey\s+|ok\s+)?jarvis[,\s]*""", I),
                ""
            )
            .trim()

        if (t.isBlank()) return Command.Unknown

        // -------------------------------------------------
        // NATURAL WHATSAPP MESSAGE COMMANDS
        // -------------------------------------------------

        // Examples:
        // Message hi to my friend Ayan Wairs
        // Message hello to my friends Rahul
        // Message hi to my frinds Ayan Wairs
        // Send message how are you to my friend Rahul
        // WhatsApp hello to Ayan
        //
        // Supports common speech recognition misspellings
        // of friend/friends.

        r(
            """^(?:please\s+)?(?:send\s+)?(?:a\s+)?(?:message|text|whatsapp)\s+(.+?)\s+to\s+(?:(?:my|the)\s+)?(?:(?:friends?|frinds?|contact)\s+)?(.+?)(?:\s+(?:on|via)\s+whatsapp)?$"""
        ).find(t)?.let {

            val message = it.groupValues[1].trim()
            val recipient = cleanName(it.groupValues[2])

            if (message.isNotBlank() && recipient.isNotBlank()) {
                return Command.SendMessage(
                    "whatsapp",
                    recipient,
                    message
                )
            }
        }

        // Send a message to Ayan saying hello.
        // Send a message to my friend Ayan saying hello.

        r(
            """^(?:please\s+)?send\s+(?:a\s+)?(?:message|text)\s+to\s+(?:(?:my|the)\s+)?(?:(?:friends?|frinds?|contact)\s+)?(.+?)\s+(?:saying|that|says)\s+(.+)$"""
        ).find(t)?.let {

            return Command.SendMessage(
                "whatsapp",
                cleanName(it.groupValues[1]),
                it.groupValues[2].trim()
            )
        }

        // Send Ayan a message saying hello.

        r(
            """^(?:please\s+)?send\s+(.+?)\s+(?:a\s+)?(?:whatsapp\s+)?(?:message|text)\s+(?:saying|that|says)\s+(.+)$"""
        ).find(t)?.let {

            return Command.SendMessage(
                "whatsapp",
                cleanName(it.groupValues[1]),
                it.groupValues[2].trim()
            )
        }

        // WhatsApp Ayan and say hello.

        r(
            """^(?:please\s+)?(?:whatsapp|message|text)\s+(.+?)\s+and\s+(?:say|send|tell)\s+(.+)$"""
        ).find(t)?.let {

            return Command.SendMessage(
                "whatsapp",
                cleanName(it.groupValues[1]),
                it.groupValues[2].trim()
            )
        }

        // -------------------------------------------------
        // COMBINED APP AND MESSAGE COMMANDS
        // -------------------------------------------------

        // Open WhatsApp and message Ayan: Hi Ayan.

        r(
            """^open\s+($APPS)\s+and\s+(?:send\s+)?(?:a\s+)?(?:message|text)\s+(?:to\s+)?(.+?)\s*[:,]\s*(.+)$"""
        ).find(t)?.let {

            return Command.SendMessage(
                normalizeApp(it.groupValues[1]),
                cleanName(it.groupValues[2]),
                it.groupValues[3].trim()
            )
        }

        // Open WhatsApp and message Ayan saying hello.

        r(
            """^open\s+($APPS)\s+and\s+(?:send\s+)?(?:a\s+)?(?:message|text)\s+(?:to\s+)?(.+?)\s+(?:saying|that|says)\s+(.+)$"""
        ).find(t)?.let {

            return Command.SendMessage(
                normalizeApp(it.groupValues[1]),
                cleanName(it.groupValues[2]),
                it.groupValues[3].trim()
            )
        }

        // Message Ayan on WhatsApp: hello.

        r(
            """^(?:send\s+)?(?:a\s+)?(?:whatsapp\s+)?(?:message|text)\s+(?:to\s+)?(.+?)\s+(?:on|via|using|in)\s+($APPS)\s*[:,]\s*(.+)$"""
        ).find(t)?.let {

            return Command.SendMessage(
                normalizeApp(it.groupValues[2]),
                cleanName(it.groupValues[1]),
                it.groupValues[3].trim()
            )
        }

        // Ayan WhatsApp message: hello.

        r(
            """^(?:send\s+)?(.+?)\s+(?:a\s+)?(?:whatsapp\s+)?message\s*[:,]\s*(.+)$"""
        ).find(t)?.let {

            return Command.SendMessage(
                "whatsapp",
                cleanName(it.groupValues[1]),
                it.groupValues[2].trim()
            )
        }

        // -------------------------------------------------
        // EXISTING MESSAGE COMMANDS
        // -------------------------------------------------

        // Explicit SMS.

        r(
            """^(?:send\s+)?(?:an?\s+)?(?:sms|text message)\s+to\s+(.+?)\s+(?:saying|that|says)\s+(.+)$"""
        ).find(t)?.let {

            return Command.SendMessage(
                "sms",
                cleanName(it.groupValues[1]),
                it.groupValues[2].trim()
            )
        }

        r(
            """^(?:sms|text)\s+(.+?)\s*[:,]\s*(.+)$"""
        ).find(t)?.let {

            return Command.SendMessage(
                "sms",
                cleanName(it.groupValues[1]),
                it.groupValues[2].trim()
            )
        }

        // WhatsApp Ayan saying hello.

        r(
            """^(whatsapp|telegram|instagram|signal|messenger)\s+(?:message\s+)?(.+?)\s+(?:saying|that|says)\s+(.+)$"""
        ).find(t)?.let {

            return Command.SendMessage(
                normalizeApp(it.groupValues[1]),
                cleanName(it.groupValues[2]),
                it.groupValues[3].trim()
            )
        }

        // Send a message to Ayan on WhatsApp saying hello.

        r(
            """^(?:send\s+|text\s+)?(?:an?\s+)?(?:message\s+)?to\s+(.+?)\s+(?:on\s+)?(whatsapp|telegram|instagram|signal|messenger)\s+(?:saying|that|says)\s+(.+)$"""
        ).find(t)?.let {

            return Command.SendMessage(
                normalizeApp(it.groupValues[2]),
                cleanName(it.groupValues[1]),
                it.groupValues[3].trim()
            )
        }

        r(
            """^(?:send\s+|text\s+)?(?:an?\s+)?(?:whatsapp\s+)?message\s+to\s+(.+?)\s+(?:saying|that|says)\s+(.+)$"""
        ).find(t)?.let {

            return Command.SendMessage(
                "whatsapp",
                cleanName(it.groupValues[1]),
                it.groupValues[2].trim()
            )
        }

        r(
            """^(?:whatsapp|message|text)\s+(.+?)\s+(?:saying|that|says)\s+(.+)$"""
        ).find(t)?.let {

            return Command.SendMessage(
                "whatsapp",
                cleanName(it.groupValues[1]),
                it.groupValues[2].trim()
            )
        }

        r(
            """^tell\s+(.+?)\s+(?:that\s+)?(.+)$"""
        ).find(t)?.let {

            return Command.SendMessage(
                "whatsapp",
                cleanName(it.groupValues[1]),
                it.groupValues[2].trim()
            )
        }

        // Send <message> to <person> [on app].

        r(
            """^send\s+(.+?)\s+to\s+(.+?)(?:\s+(?:on|via|using)\s+($APPS))?$"""
        ).find(t)?.let {

            val app = normalizeApp(
                it.groupValues[3].ifBlank { "whatsapp" }
            )

            return Command.SendMessage(
                app,
                cleanName(it.groupValues[2]),
                it.groupValues[1].trim()
            )
        }

        // -------------------------------------------------
        // CALL COMMANDS
        // -------------------------------------------------

        r(
            """^(?:call|phone|dial)\s+(.+)$"""
        ).find(t)?.let {

            return Command.Call(
                cleanName(it.groupValues[1])
            )
        }

        // -------------------------------------------------
        // OTHER EXISTING COMMANDS
        // -------------------------------------------------

        r(
            """^(?:click|tap|press)\s+(?:on\s+)?(.+)$"""
        ).find(t)?.let {

            return Command.Click(it.groupValues[1].trim())
        }

        r(
            """^(?:type|write)\s+(.+)$"""
        ).find(t)?.let {

            return Command.TypeText(it.groupValues[1].trim())
        }

        r(
            """^scroll\s+(down|up)$"""
        ).find(t)?.let {

            return Command.Scroll(
                it.groupValues[1].equals("down", true)
            )
        }

        if (r("""^(?:go\s+)?back$""").matches(t)) {
            return Command.Back
        }

        if (r("""^(?:go\s+)?home$""").matches(t)) {
            return Command.Home
        }

        r(
            """^open\s+(.+)$"""
        ).find(t)?.let {

            return Command.OpenApp(it.groupValues[1].trim())
        }

        // Alarm.

        r(
            """^set\s+(?:an?\s+)?alarm\s+(?:for|at)\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?$"""
        ).find(t)?.let {

            var hour = it.groupValues[1].toInt()
            val minute = it.groupValues[2].ifEmpty { "0" }.toInt()

            when (it.groupValues[3].lowercase()) {
                "pm" -> if (hour < 12) hour += 12
                "am" -> if (hour == 12) hour = 0
            }

            if (hour in 0..23 && minute in 0..59) {
                return Command.Alarm(hour, minute)
            }
        }

        r(
            """^(?:navigate|directions?|take\s+me)\s+(?:to\s+)?(.+)$"""
        ).find(t)?.let {

            return Command.Navigate(it.groupValues[1].trim())
        }

        r(
            """^(?:search|google|look\s+up)\s+(?:for\s+)?(.+)$"""
        ).find(t)?.let {

            return Command.WebSearch(it.groupValues[1].trim())
        }

        r(
            """^play\s+(.+?)(?:\s+on\s+youtube)?$"""
        ).find(t)?.let {

            return Command.PlayYoutube(it.groupValues[1].trim())
        }

        return Command.Unknown
    }

    private fun cleanName(value: String): String {
        return value.trim()
            .replace(
                Regex("""^(?:my|the)\s+""", I),
                ""
            )
            .replace(
                Regex("""^(?:friend|friends|frind|frinds|contact)\s+""", I),
                ""
            )
            .trim()
    }

    private fun normalizeApp(value: String): String {
        return when (value.trim().lowercase()) {
            "messages" -> "sms"
            else -> value.trim().lowercase()
        }
    }
}
