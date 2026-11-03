package dev.heartline.service

import dev.heartline.data.HeartlineDatabase
import dev.heartline.protocol.Alexa
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Placeholder answers for the watch's Alexa button until an AI is wired in: a few fixed questions answered from the
 * clock and Heartline's own data, plus one of each reply form the GTR 2e understands (text, list, weather, timer,
 * alarm, reminder, listen-again). Anything else is read back.
 */
class SampleAssistant(private val db: HeartlineDatabase, private val now: () -> ZonedDateTime = { ZonedDateTime.now() }) {
    /** What to send: [frames] in order (alerts, cards), then [text] as the visible reply, then 0b if [listenAgain]. */
    class Answer(val text: String?, val frames: List<ByteArray> = emptyList(), val listenAgain: Boolean = false)

    sealed interface Ask {
        data object Time : Ask
        data object Date : Ask
        data object HeartRate : Ask
        data object Steps : Ask
        data object Weather : Ask
        data object Help : Ask
        data object Chat : Ask
        data object CancelTimer : Ask
        data object ShowWords : Ask
        data class Timer(val seconds: Int) : Ask
        data class Alarm(val at: LocalTime) : Ask
        data class Reminder(val inSeconds: Int, val label: String) : Ask
        data class Echo(val text: String) : Ask
    }

    private var lastTimer: String? = null

    suspend fun answer(transcript: String): Answer {
        val t = now()
        return when (val ask = parse(transcript)) {
            Ask.Time -> Answer("It's ${t.format(CLOCK)}.")
            Ask.Date -> Answer("Today is ${t.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH))}.")
            Ask.HeartRate -> {
                val s = db.heartRate.since(t.toEpochSecond() - 6 * 3600.0).maxByOrNull { it.ts }
                Answer(if (s == null) "No heart rate in the last 6 hours." else
                    "Your heart rate was ${s.bpm} bpm at ${java.time.Instant.ofEpochSecond(s.ts.toLong()).atZone(t.zone).format(CLOCK)}.")
            }
            Ask.Steps -> {
                val from = t.toLocalDate().atStartOfDay(t.zone).toEpochSecond().toDouble()
                Answer("${db.activity.summary(from, t.toEpochSecond().toDouble()).steps} steps today, as of the last sync.")
            }
            Ask.Weather -> {
                val off = t.offset.totalSeconds
                val day = 86_400L
                val start = t.toLocalDate().atStartOfDay(t.zone).toEpochSecond()
                Answer(null, listOf(Alexa.weather(t.toEpochSecond(), "Sample town", t.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH)),
                    "Sample weather, not real yet", Alexa.Icon("Sunny"), "24°", "29°", "18°",
                    listOf(Alexa.Forecast(start + day, "28°", "19°", Alexa.Icon("Partly Cloudy")), Alexa.Forecast(start + 2 * day, "26°", "17°", Alexa.Icon("Rain")),
                        Alexa.Forecast(start + 3 * day, "30°", "20°", Alexa.Icon("Sunny"))), off)))
            }
            Ask.Help -> Answer(null, listOf(Alexa.list("Heartline", "Sample answers", listOf(
                "What time is it" to "Clock", "What's my heart rate" to "Heartline", "How many steps" to "Heartline",
                "Timer for 5 minutes" to "Timer", "Alarm at 7 am" to "Alarm", "Remind me in 2 minutes to ..." to "Reminder",
                "Weather" to "Sample", "Let's chat" to "Follow-up"))))
            Ask.Chat -> Answer("Sure. What's on your mind?", listenAgain = true)
            Ask.ShowWords -> Answer(null, listOf(Alexa.showText(transcript)))
            Ask.CancelTimer -> lastTimer?.let { token -> lastTimer = null; Answer("Timer cancelled.", listOf(Alexa.deleteAlert(Alexa.ALERT_TIMER, token))) }
                ?: Answer("There's no timer to cancel.")
            is Ask.Timer -> {
                val token = "heartline-timer-${t.toEpochSecond()}"; lastTimer = token
                val s = ask.seconds
                Answer("Timer set for ${spoken(s)}.", listOf(Alexa.timer(token, s / 3600, s / 60 % 60, s % 60)))
            }
            is Ask.Alarm -> {
                var at = t.toLocalDate().atTime(ask.at)
                if (!at.isAfter(t.toLocalDateTime())) at = at.plusDays(1)
                Answer("Alarm set for ${at.format(DateTimeFormatter.ofPattern("EEE h:mm a", Locale.ENGLISH))}.",
                    listOf(Alexa.alarm("heartline-alarm-${t.toEpochSecond()}", wallClock(at))))
            }
            is Ask.Reminder -> {
                val at = t.toLocalDateTime().plusSeconds(ask.inSeconds.toLong())
                Answer("I'll remind you to ${ask.label} at ${at.format(CLOCK)}.",
                    listOf(Alexa.reminder("heartline-reminder-${t.toEpochSecond()}", wallClock(at), ask.label)))
            }
            is Ask.Echo -> Answer(if (ask.text.isBlank()) "I didn't catch anything." else "You said: ${ask.text}")
        }
    }

    companion object {
        private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

        /** Zepp sends alert times as the wall-clock time counted in seconds, i.e. UTC plus the zone offset. */
        fun wallClock(at: LocalDateTime): Long = at.toEpochSecond(ZoneOffset.UTC)

        fun spoken(seconds: Int): String = listOfNotNull(
            (seconds / 3600).takeIf { it > 0 }?.let { "$it hour" + if (it > 1) "s" else "" },
            (seconds / 60 % 60).takeIf { it > 0 }?.let { "$it minute" + if (it > 1) "s" else "" },
            (seconds % 60).takeIf { it > 0 }?.let { "$it second" + if (it > 1) "s" else "" },
        ).joinToString(" ").ifEmpty { "0 seconds" }

        private val NUMBERS = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
            "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty").withIndex().associate { (i, w) -> w to i } +
            mapOf("thirty" to 30, "forty" to 40, "forty-five" to 45, "fifty" to 50, "sixty" to 60, "a" to 1, "an" to 1, "half an" to 30)

        private fun num(s: String): Int? = s.toIntOrNull() ?: NUMBERS[s]

        /** "5 minutes", "an hour and 30 seconds", "ninety seconds" -> seconds. */
        fun duration(text: String): Int? {
            val unit = Regex("""(\d+|[a-z-]+)\s+(hours?|minutes?|mins?|seconds?|secs?)""")
            var total = 0; var found = false
            val rest = text.replace("half an hour", " ")
            unit.findAll(rest).forEach { m ->
                val n = num(m.groupValues[1]) ?: return@forEach
                found = true
                total += n * when (m.groupValues[2].first()) { 'h' -> 3600; 'm' -> 60; else -> 1 }
            }
            if (Regex("""half an hour""").containsMatchIn(text)) { total += 1800; found = true }
            return total.takeIf { found && it > 0 }
        }

        /** "7", "7:30", "7 am", "7.30 p.m.", "seven pm" -> a time of day. */
        fun timeOfDay(text: String): LocalTime? {
            val m = Regex("""\b(\d{1,2}|[a-z]+)(?:[:.](\d{2}))?\s*(a\.?m\.?|p\.?m\.?)?(?=\W|$)""").findAll(text)
                .firstOrNull { num(it.groupValues[1])?.let { n -> n in 0..23 } == true && (it.groupValues[2].isNotEmpty() || it.groupValues[3].isNotEmpty() || it.groupValues[1].all(Char::isDigit)) }
                ?: return null
            var h = num(m.groupValues[1])!!; val min = m.groupValues[2].toIntOrNull() ?: 0
            val ampm = m.groupValues[3].replace(".", "")
            if (ampm == "pm" && h < 12) h += 12
            if (ampm == "am" && h == 12) h = 0
            return if (h in 0..23 && min in 0..59) LocalTime.of(h, min) else null
        }

        fun parse(transcript: String): Ask {
            val s = transcript.lowercase(Locale.ENGLISH).replace(Regex("""[?!,]"""), " ").replace(Regex("""\s+"""), " ").trim()
            return when {
                Regex("""\b(cancel|stop|delete)\b.*\btimer\b""").containsMatchIn(s) -> Ask.CancelTimer
                s.contains("timer") -> duration(s)?.let { Ask.Timer(it) } ?: Ask.Echo(transcript)
                s.contains("remind me") -> {
                    val secs = duration(s) ?: return Ask.Echo(transcript)
                    val label = Regex("""\bto (.+?)(?: in \S+ \S+)?$""").find(s.substringAfter("remind me"))?.groupValues?.get(1)?.trim()
                        ?.takeIf { it.isNotEmpty() } ?: "check in"
                    Ask.Reminder(secs, label)
                }
                s.contains("alarm") || s.contains("wake me") -> timeOfDay(s.substringAfter("alarm").substringAfter("wake me"))?.let { Ask.Alarm(it) } ?: Ask.Echo(transcript)
                s.contains("heart") || s.contains("pulse") || Regex("""\bbpm\b""").containsMatchIn(s) -> Ask.HeartRate
                s.contains("step") -> Ask.Steps
                s.contains("temperature outside") || s.split(' ').any(::soundsLikeWeather) -> Ask.Weather
                Regex("""\b(time is it|the time|what time)\b""").containsMatchIn(s) -> Ask.Time
                Regex("""\b(date|what day|which day)\b""").containsMatchIn(s) -> Ask.Date
                Regex("""\b(help|what can you do)\b""").containsMatchIn(s) -> Ask.Help
                Regex("""\b(chat|talk to me|conversation)\b""").containsMatchIn(s) -> Ask.Chat
                Regex("""\b(show my words|show what i said)\b""").containsMatchIn(s) -> Ask.ShowWords
                // Vosk's small model often hears "set a timer for ..." as something else ("said i am a for one minute").
                Regex("""\bfor\b""").containsMatchIn(s) && duration(s) != null -> Ask.Timer(duration(s)!!)
                else -> Ask.Echo(transcript.trim())
            }
        }

        /** "weather" alone is often heard as "whether", "wether", "weber" or "feather" by Vosk's small model. */
        fun soundsLikeWeather(word: String): Boolean = word in setOf("weather", "whether", "wether", "weber", "feather", "leather", "wetter", "weathers") ||
            (word.length in 5..8 && distance(word, "weather") <= 2)

        private fun distance(a: String, b: String): Int {
            val d = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = d[0]; d[0] = i
                for (j in 1..b.length) { val t = d[j]; d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1); prev = t }
            }
            return d[b.length]
        }
    }
}
