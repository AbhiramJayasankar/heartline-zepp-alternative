package dev.heartline.service

import dev.heartline.app.FetchedWeather
import dev.heartline.protocol.Alexa
import dev.heartline.protocol.wmoCondition
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** An alarm, timer or reminder the Alexa button set on the watch; kept so it can be cancelled by voice later. */
data class AlexaAlert(val id: Int, val kind: Int, val token: String, val at: Long, val label: String = "") {
    fun describe(now: ZonedDateTime): String {
        val time = Instant.ofEpochSecond(at).atZone(now.zone)
        val clock = time.format(DateTimeFormatter.ofPattern(if (time.toLocalDate() == now.toLocalDate()) "HH:mm" else "EEE HH:mm", Locale.ENGLISH))
        return when (kind) {
            Alexa.ALERT_TIMER -> "timer ringing at $clock"
            Alexa.ALERT_ALARM -> "alarm at $clock"
            else -> "reminder at $clock to $label"
        }
    }
    fun json(): JSONObject = JSONObject().put("id", id).put("kind", kind).put("token", token).put("at", at).put("label", label)
}

/** Weather for the Alexa button: Open-Meteo for the place chosen in Heartline's weather settings. */
interface AssistantWeather {
    /** Throws with a message the watch can show (no place chosen, no internet). */
    suspend fun latest(): FetchedWeather
    val fahrenheit: Boolean
}

/** Small persistent values (Heartline's meta table). */
interface AssistantStore {
    suspend fun get(key: String): String?
    suspend fun set(key: String, value: String)
}

/**
 * The Alexa button's answers: the words Gemini heard go to the OpenRouter brain ([AssistantAi]) with the clock,
 * Heartline's latest numbers, the weather and the alerts it set, and the [Ask] it returns is carried out on the
 * watch. Anything that can't be done becomes a plain message saying why; there are no built-in answers.
 */
class WatchAssistant(
    private val ai: AssistantAi,
    private val aiConfig: suspend () -> AssistantAiConfig,
    private val facts: suspend (ZonedDateTime) -> Facts,
    private val weather: AssistantWeather,
    private val store: AssistantStore,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    data class Facts(val heartRate: String? = null, val stepsToday: Int? = null)

    /**
     * What to send: [frames] in order (alerts, cards), then [text] as the visible reply, then 0b if [listenAgain].
     * [summary] says what it does, for the app's test buttons and the activity log.
     */
    class Answer(val text: String?, val frames: List<ByteArray> = emptyList(), val listenAgain: Boolean = false, val summary: String = text.orEmpty())

    /** This conversation's (question, reply) turns for the brain; a pause of over two minutes starts a new one. */
    private val history = ArrayList<Pair<String, String>>()
    private var lastTurn = 0L

    /** [dryRun] works out the answer without remembering the turn or changing the saved alerts (the app's test buttons). */
    suspend fun answer(transcript: String, dryRun: Boolean = false): Answer {
        val t = now()
        if (transcript.isBlank()) return Answer("I didn't hear any words. Try again.")
        val config = aiConfig()
        if (!config.enabled) return Answer("Add an OpenRouter key in Heartline (Watch, Alexa answers) so I can answer. I heard: \"$transcript\"")
        if (t.toEpochSecond() - lastTurn > 120) history.clear()
        val alerts = alerts(t)
        val f = facts(t)
        // The forecast is usually cached; don't hold the watch up for long if it has to be fetched.
        val forecast = withTimeoutOrNull(4_000) { runCatching { weather.latest() }.getOrNull() }
        val ask = try {
            ai.interpret(transcript, AiContext(t, f.heartRate, f.stepsToday, forecast?.let { describe(it) }, alerts), history.toList(), config)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            return Answer("OpenRouter isn't working: ${e.message}")
        }
        val answer = carryOut(ask, t, alerts, dryRun)
        if (!dryRun) {
            history += transcript to answer.summary; lastTurn = t.toEpochSecond()
            while (history.size > 6) history.removeAt(0)
        }
        return answer
    }

    private suspend fun carryOut(ask: Ask, t: ZonedDateTime, alerts: List<AlexaAlert>, dryRun: Boolean): Answer {
        suspend fun add(kind: Int, at: Long, label: String = ""): String {
            val token = "heartline-${listOf("", "alarm", "reminder", "timer")[kind]}-${t.toEpochSecond()}"
            if (!dryRun) saveAlerts(alerts + AlexaAlert((alerts.maxOfOrNull { it.id } ?: 0) + 1, kind, token, at, label))
            return token
        }
        return when (ask) {
            is Ask.Say -> Answer(ask.text, listenAgain = ask.listenAgain)
            is Ask.Timer -> {
                val s = ask.seconds
                val token = add(Alexa.ALERT_TIMER, t.toEpochSecond() + s)
                Answer("Timer set for ${spoken(s)}.", listOf(Alexa.timer(token, s / 3600, s / 60 % 60, s % 60)), summary = "Timer set for ${spoken(s)} (sets a watch timer).")
            }
            is Ask.Alarm -> {
                val at = next(t, ask.at)
                val token = add(Alexa.ALERT_ALARM, at.atZone(t.zone).toEpochSecond())
                val text = "Alarm set for ${at.format(DateTimeFormatter.ofPattern("EEE h:mm a", Locale.ENGLISH))}."
                Answer(text, listOf(Alexa.alarm(token, wallClock(at))), summary = "$text (sets a watch alarm)")
            }
            is Ask.Reminder -> {
                val at = ask.inSeconds?.let { t.toLocalDateTime().plusSeconds(it.toLong()) } ?: next(t, ask.at ?: LocalTime.of(9, 0))
                val token = add(Alexa.ALERT_REMINDER, at.atZone(t.zone).toEpochSecond(), ask.label)
                val text = "I'll remind you to ${ask.label} at ${at.format(DateTimeFormatter.ofPattern(if (at.toLocalDate() == t.toLocalDate()) "h:mm a" else "EEE h:mm a", Locale.ENGLISH))}."
                Answer(text, listOf(Alexa.reminder(token, wallClock(at), ask.label)), summary = "$text (sets a watch reminder)")
            }
            is Ask.Cancel -> {
                val gone = alerts.filter { it.id in ask.ids }
                if (gone.isEmpty()) return Answer("There's nothing like that to cancel.")
                if (!dryRun) saveAlerts(alerts - gone.toSet())
                val text = "Cancelled the " + gone.joinToString(" and the ") { it.describe(t) } + "."
                Answer(text, gone.map { Alexa.deleteAlert(it.kind, it.token) }, summary = "$text (removes it from the watch)")
            }
            Ask.Weather -> {
                val w = try { weather.latest() } catch (e: Exception) { return Answer("Couldn't get the weather: ${e.message}") }
                Answer(null, listOf(card(w, t)), summary = "Shows the weather card: ${describe(w)}")
            }
            Ask.Help -> Answer(null, listOf(Alexa.list("Heartline", "Things to ask", listOf(
                "What's my heart rate", "How many steps today", "Timer for 5 minutes", "Alarm at 7 am", "Remind me at 6 pm to call home",
                "Cancel my alarm", "What's the weather", "Ask me anything").mapIndexed { i, q -> "${i + 1}" to q })), summary = "Shows the list of things to ask")
        }
    }

    /** The saved alerts that haven't gone off yet. */
    private suspend fun alerts(t: ZonedDateTime): List<AlexaAlert> = runCatching {
        val a = JSONArray(store.get(ALERTS) ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map { AlexaAlert(it.getInt("id"), it.getInt("kind"), it.getString("token"), it.getLong("at"), it.optString("label")) }
    }.getOrDefault(emptyList()).filter { it.at > t.toEpochSecond() }

    private suspend fun saveAlerts(list: List<AlexaAlert>) = store.set(ALERTS, JSONArray().apply { list.forEach { put(it.json()) } }.toString())

    private fun temp(c: Int) = if (weather.fahrenheit) "${(c * 9 / 5.0 + 32).roundToInt()}°F" else "$c°C"

    /** One line for the brain: now, today and the next three days. */
    private fun describe(f: FetchedWeather): String {
        val w = f.weather
        val days = w.days.take(4).mapIndexed { i, d -> "${listOf("today", "tomorrow", "in 2 days", "in 3 days")[i]} ${wmoCondition(d.wmo).text.lowercase()}, ${temp(d.highC)} / ${temp(d.lowC)}" }
        return "${w.location}: ${temp(w.tempC)}, ${wmoCondition(w.wmo).text.lowercase()} now, humidity ${w.humidity}%; " + days.joinToString("; ")
    }

    private fun card(f: FetchedWeather, t: ZonedDateTime): ByteArray {
        val w = f.weather
        val start = t.toLocalDate().atStartOfDay(t.zone)
        fun deg(c: Int) = temp(c).dropLast(1)
        return Alexa.weather(t.toEpochSecond(), w.location, t.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH)),
            wmoCondition(w.wmo).text, icon(w.wmo), deg(w.tempC), deg(w.days.first().highC), deg(w.days.first().lowC),
            w.days.drop(1).take(3).mapIndexed { i, d -> Alexa.Forecast(start.plusDays(i + 1L).toEpochSecond(), deg(d.highC), deg(d.lowC), icon(d.wmo)) },
            t.offset.totalSeconds)
    }

    companion object {
        const val ALERTS = "alexa_alerts"

        /** Alexa's weather icon names for Open-Meteo's WMO codes. */
        fun icon(wmo: Int): Alexa.Icon = Alexa.Icon(when (wmo) {
            0, 1 -> "Sunny"; 2 -> "Partly Cloudy"; 3 -> "Cloudy"; 45, 48 -> "Fog"
            in 51..67, in 80..82 -> "Rain"; in 71..77, 85, 86 -> "Snow"; in 95..99 -> "Thunderstorm"
            else -> "Cloudy"
        })

        /** The next [time] after [t] (today or tomorrow). */
        fun next(t: ZonedDateTime, time: LocalTime): LocalDateTime =
            t.toLocalDate().atTime(time).let { if (it.isAfter(t.toLocalDateTime())) it else it.plusDays(1) }

        /** Zepp sends alert times as the wall-clock time counted in seconds, i.e. UTC plus the zone offset. */
        fun wallClock(at: LocalDateTime): Long = at.toEpochSecond(ZoneOffset.UTC)

        fun spoken(seconds: Int): String = listOfNotNull(
            (seconds / 3600).takeIf { it > 0 }?.let { "$it hour" + if (it > 1) "s" else "" },
            (seconds / 60 % 60).takeIf { it > 0 }?.let { "$it minute" + if (it > 1) "s" else "" },
            (seconds % 60).takeIf { it > 0 }?.let { "$it second" + if (it > 1) "s" else "" },
        ).joinToString(" ").ifEmpty { "0 seconds" }
    }
}
