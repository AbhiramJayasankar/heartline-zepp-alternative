package dev.heartline.service

import dev.heartline.app.FetchedWeather
import dev.heartline.protocol.Alexa
import dev.heartline.protocol.CalendarReminder
import dev.heartline.protocol.reminderTitle
import dev.heartline.protocol.wmoCondition
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
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

/** Weather for the Alexa button: Open-Meteo for the phone's location or the place picked in Heartline. */
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

/** Read-only lookups the brain can call (Heartline's health data, see HealthTools). */
interface DataTools {
    val tools: List<Tool>
    /** Null when [name] isn't one of [tools]. */
    suspend fun run(name: String, args: JSONObject): String?

    object None : DataTools {
        override val tools = emptyList<Tool>()
        override suspend fun run(name: String, args: JSONObject): String? = null
    }
}

/** What the Alexa button can make Heartline do. Each throws with a message when it can't. */
interface AssistantActions {
    fun startWorkout(label: String, minutes: Int)
    /** The capture that was stopped, as words, or null if none was running. */
    fun stopWorkout(): String?
    fun sync()

    /** For the app's test buttons, which never act. */
    object None : AssistantActions {
        override fun startWorkout(label: String, minutes: Int) = error("only the watch can start a capture")
        override fun stopWorkout(): String? = error("only the watch can stop a capture")
        override fun sync() = error("only the watch can sync")
    }
}

/**
 * The Alexa button's answers: the words Gemini heard go to the OpenRouter brain ([AssistantAi]) with the clock,
 * Heartline's latest numbers, the weather and the alerts it set; the brain calls [data] tools for detail and this
 * class's action tools to act on the watch, and its final words are shown. Anything that fails becomes a plain
 * message saying why; there are no built-in answers.
 */
class WatchAssistant(
    private val ai: AssistantAi,
    private val aiConfig: suspend () -> AssistantAiConfig,
    private val facts: suspend (ZonedDateTime) -> Facts,
    private val weather: AssistantWeather,
    private val store: AssistantStore,
    private val actions: AssistantActions = AssistantActions.None,
    private val data: DataTools = DataTools.None,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val settings: suspend () -> AlexaSettings = { AlexaSettings() },
) {
    /**
     * [dataAsOf]: the time of the newest activity minute synced from the watch; [health]: a short glance at today,
     * one fact per line; [workout]: the capture running now.
     */
    data class Facts(val heartRate: String? = null, val stepsToday: Int? = null, val dataAsOf: String? = null, val health: String? = null, val workout: String? = null)

    /**
     * What to send: [frames] in order (alerts, cards), then [text] as the visible reply, then 0b if [listenAgain].
     * [summary] says what it did, for the app's test buttons and the activity log.
     */
    class Answer(val text: String?, val frames: List<ByteArray> = emptyList(), val listenAgain: Boolean = false, val summary: String = text.orEmpty(),
                 /** What the brain did, for the conversation log; [error] says why there is no real answer. */
                 val trace: TurnTrace? = null, val error: String? = null)

    /** Every tool the brain can be given, before the owner's choices (Alexa settings) are applied. */
    val allTools: List<Tool> get() = data.tools + ACTION_TOOLS

    /** The instructions [template] filled in as the brain would get them now. */
    suspend fun promptPreview(template: String): String {
        val t = now()
        val f = facts(t)
        return AssistantAi.renderPrompt(template, AiContext(t, f.heartRate, f.stepsToday, null, alerts(t), f.dataAsOf, f.health, f.workout))
    }

    /** The model that gave the last answer. */
    val lastModel: String? get() = ai.lastModel

    /** This conversation's (question, reply) turns for the brain; a pause of over two minutes starts a new one. */
    private val history = ArrayList<Pair<String, String>>()
    private var lastTurn = 0L

    /** [dryRun] works out the answer without acting, remembering the turn or changing the saved alerts (the app's test buttons). */
    suspend fun answer(transcript: String, dryRun: Boolean = false): Answer {
        val t = now()
        if (transcript.isBlank()) return Answer("I didn't hear any words. Try again.", error = "no words heard")
        val config = aiConfig()
        if (!config.enabled) return Answer("Add an OpenRouter key in Heartline (Watch, Alexa) so I can answer. I heard: \"$transcript\"", error = "no OpenRouter key")
        val s = settings()
        if (t.toEpochSecond() - lastTurn > s.memoryMinutes * 60L) history.clear()
        val trace = TurnTrace()
        val turn = Turn(t, alerts(t), dryRun, trace)
        val f = facts(t)
        // The forecast is usually cached; don't hold the watch up for long if it has to be fetched.
        turn.forecast = withTimeoutOrNull(4_000) { runCatching { weather.latest() }.getOrNull() }
        val context = AiContext(t, f.heartRate, f.stepsToday, turn.forecast?.let { describe(it) }, turn.alerts, f.dataAsOf, f.health, f.workout)
        val runner = object : ToolRunner {
            override val tools = allTools.filter { s.enabled(it.name) }.map { s.describe(it) }
            override suspend fun run(name: String, args: JSONObject): String =
                if (!s.enabled(name)) "Error: the $name tool is turned off in Heartline" else data.run(name, args) ?: turn.act(name, args)
        }
        val reply = try {
            withTimeoutOrNull(s.answerTimeoutS * 1000L) { ai.converse(transcript, context, history.toList(), config, runner, s, trace) }
                ?: return turn.answer("OpenRouter isn't working: no answer within ${s.answerTimeoutS} s.", emptyList(), "no answer within ${s.answerTimeoutS} s")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            return turn.answer("OpenRouter isn't working: ${e.message}", emptyList(), e.message ?: e.toString())
        }
        if (!dryRun) {
            history += transcript to reply.text; lastTurn = t.toEpochSecond()
            while (history.size > 20) history.removeAt(0)
        }
        return turn.answer(reply.text.take(s.maxAnswerChars), reply.toolCalls)
    }

    /** One question's actions: what was sent to the watch and what to tell the user about it. */
    private inner class Turn(val t: ZonedDateTime, var alerts: List<AlexaAlert>, val dryRun: Boolean, val trace: TurnTrace) {
        var forecast: FetchedWeather? = null
        val frames = ArrayList<ByteArray>()
        val did = ArrayList<String>()
        var card = false

        fun answer(text: String, tools: List<String>, error: String? = null): Answer {
            val parts = listOfNotNull(text.takeIf { !card }, did.takeIf { it.isNotEmpty() }?.joinToString("; ", "[", "]"),
                tools.filter { it.startsWith("get_") }.distinct().takeIf { it.isNotEmpty() }?.joinToString(", ", "(looked up ", ")"))
            // A card is the answer; a text reply after it would replace it on the screen.
            return Answer(if (card) null else text, frames.toList(), !card && text.trimEnd().endsWith("?"), parts.joinToString(" "), trace, error)
        }

        private suspend fun addAlert(kind: Int, at: Long, label: String = ""): String {
            val token = "heartline-${listOf("", "alarm", "reminder", "timer")[kind]}-${t.toEpochSecond()}-${alerts.size}"
            val next = alerts + AlexaAlert((alerts.maxOfOrNull { it.id } ?: 0) + 1, kind, token, at, label)
            if (!dryRun) saveAlerts(next)
            alerts = next
            return token
        }

        private fun clock(at: LocalDateTime) = at.format(DateTimeFormatter.ofPattern(if (at.toLocalDate() == t.toLocalDate()) "HH:mm" else "EEE HH:mm", Locale.ENGLISH))

        /** When [args] says: "time" as HH:MM (the next one) or "in_seconds"; null if neither is usable. */
        private fun whenOf(args: JSONObject, roundToMinute: Boolean): LocalDateTime? {
            args.optInt("in_seconds", 0).takeIf { it > 0 }?.let { s ->
                val at = t.toLocalDateTime().plusSeconds(s.toLong())
                return if (roundToMinute) at.plusSeconds(59).truncatedTo(ChronoUnit.MINUTES) else at
            }
            val time = args.optString("time").trim().takeIf { it.isNotEmpty() && it != "null" } ?: return null
            return runCatching { next(t, LocalTime.parse(time.padStart(5, '0'))) }.getOrNull()
        }

        suspend fun act(name: String, args: JSONObject): String {
            fun done(what: String): String { did += what; return if (dryRun) "Done (test run, nothing was sent): $what" else "Done: $what" }
            return when (name) {
                "set_timer" -> {
                    val s = args.optInt("seconds", 0).takeIf { it in 1..86_399 } ?: return "Error: seconds must be 1 to 86399"
                    val token = addAlert(Alexa.ALERT_TIMER, t.toEpochSecond() + s)
                    frames += Alexa.timer(token, s / 3600, s / 60 % 60, s % 60)
                    done("timer for ${spoken(s)}, ringing at ${clock(t.toLocalDateTime().plusSeconds(s.toLong()))}")
                }
                "set_alarm" -> {
                    // Watch alarms ring on the minute: "in N minutes" rounds up to the next whole minute.
                    val at = whenOf(args, roundToMinute = true) ?: return "Error: give time as HH:MM or in_seconds"
                    val token = addAlert(Alexa.ALERT_ALARM, at.atZone(t.zone).toEpochSecond())
                    frames += Alexa.alarm(token, wallClock(at))
                    done("alarm at ${clock(at)}")
                }
                "set_reminder" -> {
                    val label = args.optString("label").trim().ifEmpty { return "Error: label is required" }
                    val at = whenOf(args, roundToMinute = true) ?: return "Error: give time as HH:MM or in_seconds"
                    // A watch reminder (the calendar slots, which show their title), written by the agenda sync.
                    addAlert(Alexa.ALERT_REMINDER, at.atZone(t.zone).toEpochSecond(), label)
                    done("reminder \"${reminderTitle(label)}\" at ${clock(at)} (the watch shows the first ${dev.heartline.protocol.REMINDER_TITLE_LENGTH} characters)")
                }
                "cancel_alerts" -> {
                    val ids = args.optJSONArray("ids")?.let { a -> (0 until a.length()).map { a.optInt(it, -1) } }.orEmpty()
                    val gone = alerts.filter { it.id in ids }
                    if (gone.isEmpty()) return "Error: none of those ids are set; the list is ${if (alerts.isEmpty()) "empty" else alerts.joinToString { "${it.id}: ${it.describe(t)}" }}"
                    alerts = alerts - gone.toSet()
                    if (!dryRun) saveAlerts(alerts)
                    gone.filter { it.kind != Alexa.ALERT_REMINDER }.forEach { frames += Alexa.deleteAlert(it.kind, it.token) }
                    done("cancelled " + gone.joinToString(" and ") { it.describe(t) })
                }
                "get_weather" -> forecast?.let { describe(it) } ?: try { describe(weather.latest().also { forecast = it }) } catch (e: Exception) { "Error: ${e.message}" }
                "show_weather_card" -> {
                    val w = forecast ?: try { weather.latest().also { forecast = it } } catch (e: Exception) { return "Error: couldn't get the weather: ${e.message}" }
                    frames += card(w, t); card = true
                    done("showed the weather card for ${w.weather.location}")
                }
                "show_help" -> {
                    frames += Alexa.list("Heartline", "Things to ask", HELP.mapIndexed { i, q -> "${i + 1}" to q }); card = true
                    done("showed the list of things to ask")
                }
                "start_workout" -> {
                    val label = args.optString("label").trim().ifEmpty { "Workout" }
                    val minutes = args.optInt("minutes", 60).coerceIn(1, 720)
                    if (!dryRun) try { actions.startWorkout(label, minutes) } catch (e: Exception) { return "Error: ${e.message}" }
                    done("started the $label capture for ${spoken(minutes * 60)}")
                }
                "stop_workout" -> {
                    val stopped = if (dryRun) "the workout capture" else try { actions.stopWorkout() } catch (e: Exception) { return "Error: ${e.message}" }
                    stopped?.let { done("stopped $it; Heartline is working out the sets and reps") } ?: "No workout capture is running."
                }
                "sync_watch" -> {
                    if (!dryRun) try { actions.sync() } catch (e: Exception) { return "Error: ${e.message}" }
                    done("started a sync with the watch")
                }
                else -> "Error: there is no tool called $name"
            }
        }
    }

    /** The saved alerts that haven't gone off yet. */
    private suspend fun alerts(t: ZonedDateTime): List<AlexaAlert> = parseAlerts(store.get(ALERTS)).filter { it.at > t.toEpochSecond() }

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
        val night = w.sunrise != null && w.sunset != null && t.toLocalTime().let { it.isBefore(w.sunrise) || it.isAfter(w.sunset) }
        return Alexa.weather(t.toEpochSecond(), w.location, t.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH)),
            wmoCondition(w.wmo).text, icon(w.wmo, current = true, night = night, windKmh = w.windKmh), deg(w.tempC), deg(w.days.first().highC), deg(w.days.first().lowC),
            w.days.drop(1).take(3).mapIndexed { i, d -> Alexa.Forecast(start.plusDays(i + 1L).toEpochSecond(), deg(d.highC), deg(d.lowC), icon(d.wmo)) },
            t.offset.totalSeconds)
    }

    companion object {
        const val ALERTS = "alexa_alerts"

        val HELP = listOf("How did I sleep", "Analyse my heart rate today", "How's my stress today", "What's my readiness", "Start a gym workout",
            "Timer for 5 minutes", "Alarm at 7 am", "Remind me at 6 pm to call home", "Cancel my alarm", "What's the weather", "Ask me anything")

        private fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()) =
            JSONObject().put("type", "object").put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } }).put("required", JSONArray(required))
        private fun prop(type: String, description: String) = JSONObject().put("type", type).put("description", description)
        private val WHEN = arrayOf("time" to prop("string", "Time of day as HH:MM, 24-hour; the next one after now"),
            "in_seconds" to prop("integer", "Or: how many seconds from now"))

        /** The actions the brain can take on the watch and in Heartline. */
        val ACTION_TOOLS = listOf(
            Tool("set_timer", "Start a countdown timer on the watch.", obj("seconds" to prop("integer", "Length in seconds"), required = listOf("seconds"))),
            Tool("set_alarm", "Set an alarm on the watch, at a time of day or in some time from now.", obj(*WHEN)),
            Tool("set_reminder", "Set a reminder on the watch; it shows the label at that time.",
                obj("label" to prop("string", "What to remind them about, short (the watch shows 16 characters)"), *WHEN, required = listOf("label"))),
            Tool("cancel_alerts", "Cancel alarms, timers or reminders set by voice, by their ids from the list in the instructions.",
                obj("ids" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "integer")), required = listOf("ids"))),
            Tool("get_weather", "The weather where the phone is: now, today and the next three days.", obj()),
            Tool("show_weather_card", "Show the weather card on the watch (now, today and the next three days). Use it whenever they ask about the weather in general.", obj()),
            Tool("show_help", "Show the list of things they can ask; use it when they ask what you can do.", obj()),
            Tool("start_workout", "Start Heartline's workout capture, which records sets, reps and heart rate from the watch's sensors.",
                obj("label" to prop("string", "The workout they named, such as Gym or Run"), "minutes" to prop("integer", "How long to capture; 60 if they don't say"), required = listOf("label"))),
            Tool("stop_workout", "Stop the running workout capture.", obj()),
            Tool("sync_watch", "Fetch the latest data from the watch now.", obj()),
        )

        fun parseAlerts(json: String?): List<AlexaAlert> = runCatching {
            val a = JSONArray(json ?: "[]")
            (0 until a.length()).map { a.getJSONObject(it) }.map { AlexaAlert(it.getInt("id"), it.getInt("kind"), it.getString("token"), it.getLong("at"), it.optString("label")) }
        }.getOrDefault(emptyList())

        /** The voice reminders still to come, for the watch's reminder slots (shared with calendar events). */
        fun voiceReminders(json: String?, nowEpoch: Long): List<CalendarReminder> =
            parseAlerts(json).filter { it.kind == Alexa.ALERT_REMINDER && it.at >= nowEpoch }.map { CalendarReminder(reminderTitle(it.label), it.at, it.at) }

        /**
         * [text] in characters the watch's font shows: typographic punctuation as plain ASCII, odd spaces (Java's
         * clock format puts U+202F before AM/PM) as spaces, and emoji and other pictographs dropped.
         */
        fun watchText(text: String): String {
            val plain = text.replace(Regex("[\\u00A0\\u2000-\\u200B\\u202F\\u205F]"), " ")
                .replace(Regex("[\\u2018\\u2019\\u201B\\u2032]"), "'").replace(Regex("[\\u201C\\u201D\\u201F\\u2033]"), "\"")
                .replace(Regex("[\\u2010-\\u2015\\u2212]"), "-").replace("…", "...").replace("•", "-")
                .replace(Regex("\\*\\*|__|`"), "")
            val sb = StringBuilder()
            var i = 0
            while (i < plain.length) {
                val cp = plain.codePointAt(i); i += Character.charCount(cp)
                val type = Character.getType(cp)
                if (cp > 0xFFFF || (type == Character.OTHER_SYMBOL.toInt() && cp >= 0x2100) || type == Character.SURROGATE.toInt() || cp in 0xFE00..0xFE0F || cp == 0x200D) continue
                sb.appendCodePoint(cp)
            }
            return sb.toString().replace(Regex(" {2,}"), " ").trim()
        }

        /**
         * Bytes of answer per watch notification: the watch takes 1,024 bytes per notification (NOTIFICATION_MAX), less
         * the "Alexa 12/20" title, the app name, framing and a "(cut short)" note.
         */
        const val NOTIFICATION_TEXT = 960

        /**
         * Bytes of text the Alexa reply screen shows: it showed about 2,045 bytes of a 3,000-byte reply and dropped the
         * rest (tested 2026-11-06), so the reply is cut a little below that and the rest goes out as notifications.
         */
        const val REPLY_TEXT = 2000

        /**
         * [text] as the part the reply screen shows (up to [limit] UTF-8 bytes, cut at a sentence or word) and the rest,
         * in pieces for watch notifications.
         */
        fun splitReply(text: String?, limit: Int = REPLY_TEXT): Pair<String?, List<String>> {
            if (text == null || text.toByteArray().size <= limit) return text to emptyList()
            val note = " (continued in notifications)"
            val cut = cutAt(text, limit - note.length, bytes = true)
            return (text.substring(0, cut).trimEnd() + note) to notificationChunks(text.substring(cut))
        }

        /** [text] in pieces that fit a watch notification, cut at sentences or words. */
        fun notificationChunks(text: String): List<String> {
            val out = ArrayList<String>()
            var rest = text.trim()
            while (rest.isNotEmpty()) {
                val cut = if (rest.toByteArray().size <= NOTIFICATION_TEXT) rest.length else cutAt(rest, NOTIFICATION_TEXT, bytes = true)
                out += rest.substring(0, cut).trim(); rest = rest.substring(cut).trim()
            }
            return out
        }

        /** Where to cut [text] to at most [max] characters (or UTF-8 bytes): after a sentence, else at a space. */
        private fun cutAt(text: String, max: Int, bytes: Boolean = false): Int {
            var end = minOf(text.length, max)
            if (bytes) while (end > 1 && text.substring(0, end).toByteArray().size > max) end--
            val head = text.substring(0, end)
            val sentence = maxOf(head.lastIndexOf(". "), head.lastIndexOf("! "), head.lastIndexOf("? "), head.lastIndexOf("\n"))
            val space = head.lastIndexOf(' ')
            return when { sentence > end / 2 -> sentence + 1; space > end / 2 -> space; else -> end }
        }

        /**
         * The weather icon as Alexa sends it, which the Zepp app passes on unchanged: a description and the icon's image
         * URL, whose file name names the icon ("Partly cloudy", .../weatherForecast/partly_cloudy.png in a real Alexa
         * payload, avs-device-sdk issue 477). Without the URL the watch showed a question mark. Alexa's image server has
         * eight names (checked 2026-11-06): sunny, partly_cloudy, partly_cloudy_night, cloudy, rainy, snow, windy and
         * lightning; everything else is 404, so each WMO code maps to the nearest of those.
         */
        fun icon(wmo: Int, current: Boolean = false, night: Boolean = false, windKmh: Double = 0.0): Alexa.Icon = alexaIcon(when (wmo) {
            0, 1 -> if (night) "partly_cloudy_night" else if (current && windKmh >= 40) "windy" else "sunny"
            2 -> if (night) "partly_cloudy_night" else "partly_cloudy"
            3, 45, 48 -> if (current && windKmh >= 40) "windy" else "cloudy"
            in 51..67, in 80..82 -> "rainy"
            in 71..77, 85, 86 -> "snow"
            in 95..99 -> "lightning"
            else -> "cloudy"
        }, current)

        /** [name] (one of Alexa's icon file names) as the icon Alexa would send. */
        fun alexaIcon(name: String, current: Boolean = false): Alexa.Icon =
            Alexa.Icon(name.replace('_', ' ').replaceFirstChar { it.uppercase() },
                "https://images-na.ssl-images-amazon.com/images/G/01/alexa/avs/gui/small/${if (current) "currentWeatherIcon" else "weatherForecast"}/$name.png")

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
