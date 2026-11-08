package dev.heartline.service

import dev.heartline.app.FetchedWeather
import dev.heartline.protocol.Alexa
import dev.heartline.protocol.CalendarReminder
import dev.heartline.protocol.reminderTitle
import dev.heartline.protocol.wmoCondition
import java.time.DayOfWeek
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

/**
 * An alarm on the watch: one Heartline set ([hour], [minute], repeat [days], none = once) or one made on the watch
 * ([madeOnWatch]), whose time the watch doesn't share.
 */
data class WatchAlarm(val slot: Int, val on: Boolean, val hour: Int?, val minute: Int?, val days: Set<DayOfWeek>, val madeOnWatch: Boolean) {
    fun describe(): String {
        val time = if (hour != null && minute != null) "%02d:%02d".format(hour, minute) else "made on the watch or by Alexa (time unknown; switch or delete it on the watch: long press in the Alarm app)"
        return "alarm $slot: $time, ${repeatWords(days)}, ${if (on) "on" else "off"}"
    }
    companion object {
        fun repeatWords(days: Set<DayOfWeek>): String = when {
            days.isEmpty() -> "once"
            days.size == 7 -> "every day"
            days == WEEKDAYS -> "weekdays"
            days == setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "weekends"
            else -> days.sorted().joinToString(" ") { it.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH) }
        }
        private val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

        /** Repeat days from words such as "weekdays", "weekends", "daily", "once" or day names; null when one isn't understood. */
        fun parseDays(words: List<String>): Set<DayOfWeek>? {
            val out = HashSet<DayOfWeek>()
            for (w in words.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }) when (w) {
                "once", "none", "never" -> {}
                "daily", "every day", "everyday", "all" -> out += DayOfWeek.entries
                "weekdays", "weekday" -> out += WEEKDAYS
                "weekends", "weekend" -> out += setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
                else -> out += DayOfWeek.entries.firstOrNull { d -> w.length >= 2 && d.name.lowercase(Locale.ROOT).startsWith(w.take(3)) } ?: return null
            }
            return out
        }
    }
}

/** What the Alexa button can make Heartline do. Each throws with a message when it can't. */
interface AssistantActions {
    fun startWorkout(label: String, minutes: Int)
    /** The capture that was stopped, as words, or null if none was running. */
    fun stopWorkout(): String?
    fun sync()
    /** Every alarm on the watch, read from it now. */
    suspend fun watchAlarms(): List<WatchAlarm> = error("only the watch service can read the watch's alarms")
    /** Adds or changes an alarm ([slot] null for a new one) and returns its slot once the watch took it. */
    suspend fun setWatchAlarm(slot: Int?, hour: Int, minute: Int, days: Set<DayOfWeek>, on: Boolean): Int = error("only the watch service can set alarms")
    suspend fun switchWatchAlarm(slot: Int, on: Boolean): Unit = error("only the watch service can switch alarms")
    suspend fun deleteWatchAlarm(slot: Int): Unit = error("only the watch service can delete alarms")
    /** Starts a heart check of [seconds]; the result goes to the watch as a notification when it's done. */
    suspend fun startHeartCheck(seconds: Int): Unit = error("only the watch service can start a heart check")
    /** Adds [text] as a new line at the end of the app's quick notes. */
    suspend fun addNote(text: String): Unit = error("only the app can change notes")
    /** Sends [parts] as watch notifications titled [title], in order; returns how many will be sent. */
    suspend fun sendToWatch(title: String, parts: List<String>): Int = error("only the watch service can send notifications")
    /** Media on the phone: play, pause, play_pause, next, previous, stop, volume_up, volume_down, set_volume, mute, unmute. Returns the state after. */
    suspend fun media(action: String, percent: Int?): String = error("only the phone can control media")
    /** The phone's ringer: normal, vibrate or silent, and optionally its ring volume. Returns the state after. */
    suspend fun ringer(mode: String?, percent: Int?): String = error("only the phone can change the ringer")
    /** Ringer, volumes, what's playing and the phone's battery, as words. */
    suspend fun phoneStatus(): String = error("only the phone can say its status")

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
    /** Whether the watch assistant has a model with a key (AI models). */
    private val aiReady: suspend () -> Boolean,
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
        if (!aiReady()) return Answer("Choose a model with a key for the watch assistant in Heartline (AI models) so I can answer. I heard: \"$transcript\"",
            error = "no model with a key for the watch assistant")
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
            withTimeoutOrNull(s.answerTimeoutS * 1000L) {
                val first = ai.converse(transcript, context, history.toList(), runner, s, trace)
                // A model may say "Cancelled." without calling the tool (2026-11-06: the reminder still rang). It is
                // asked once more, told that nothing happened; for a reply that only sounded like an action it just answers again.
                if (first.toolCalls.any { it in ACTION_NAMES } || !CLAIMED_ACTION.containsMatchIn(first.text)) first
                else ai.converse(undone(first.text), context, history.toList() + (transcript to first.text), runner, s, trace)
                    .let { AssistantAi.Reply(it.text, first.toolCalls + it.toolCalls) }
            } ?: return turn.answer("The AI isn't working: no answer within ${s.answerTimeoutS} s.", emptyList(), "no answer within ${s.answerTimeoutS} s")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            return turn.answer("The AI isn't working: ${e.message}", emptyList(), e.message ?: e.toString())
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
            val what = (args.optString(if (name == "get_info") "topic" else "action")).trim().lowercase(Locale.ROOT)
            fun bad() = "Error: ${if (name == "get_info") "topic" else "action"} for $name is one of ${CHOICES[name]?.joinToString() ?: "nothing"}"
            return when (name) {
                "get_info" -> when (what) {
                    "weather" -> forecast?.let { describe(it) } ?: try { describe(weather.latest().also { forecast = it }) } catch (e: Exception) { "Error: ${e.message}" }
                    "phone" -> try { actions.phoneStatus() } catch (e: Exception) { "Error: ${e.message}" }
                    else -> bad()
                }
                "alarms" -> when (what) { "list" -> listAlarms(); "set" -> setAlarm(args); "delete" -> deleteAlarms(args); else -> bad() }
                "timers_and_reminders" -> when (what) { "timer" -> timer(args); "reminder" -> reminder(args); "cancel" -> cancel(args); else -> bad() }
                "show_on_watch" -> when (what) {
                    "table" -> table(args)
                    "weather" -> {
                        val w = forecast ?: try { weather.latest().also { forecast = it } } catch (e: Exception) { return "Error: couldn't get the weather: ${e.message}" }
                        frames += card(w, t); card = true
                        done("showed the weather card for ${w.weather.location}")
                    }
                    "things_to_ask" -> {
                        frames += Alexa.list("Heartline", "Things to ask", HELP.mapIndexed { i, q -> "${i + 1}" to q }); card = true
                        done("showed the list of things to ask")
                    }
                    "notifications" -> notifications(args)
                    else -> bad()
                }
                "recording" -> when (what) {
                    "heart_check" -> {
                        val minutes = args.optInt("minutes", 2).coerceIn(1, 5)
                        if (!dryRun) try { actions.startHeartCheck(minutes * 60) } catch (e: Exception) { return "Error: ${e.message}" }
                        done("started a ${spoken(minutes * 60)} heart check; they should keep the wrist still, and the result comes as a watch notification")
                    }
                    "start_workout" -> {
                        val label = args.optString("label").trim().takeIf { it.isNotEmpty() && it != "null" } ?: "Workout"
                        val minutes = args.optInt("minutes", 60).coerceIn(1, 720)
                        if (!dryRun) try { actions.startWorkout(label, minutes) } catch (e: Exception) { return "Error: ${e.message}" }
                        done("started the $label capture for ${spoken(minutes * 60)}")
                    }
                    "stop_workout" -> {
                        val stopped = if (dryRun) "the workout capture" else try { actions.stopWorkout() } catch (e: Exception) { return "Error: ${e.message}" }
                        stopped?.let { done("stopped $it; Heartline is working out the sets and reps") } ?: "No workout capture is running."
                    }
                    else -> bad()
                }
                "phone" -> phone(what, args)
                "app" -> when (what) {
                    "sync_watch" -> {
                        if (!dryRun) try { actions.sync() } catch (e: Exception) { return "Error: ${e.message}" }
                        done("started a sync with the watch")
                    }
                    "add_note" -> {
                        val text = args.optString("text").trim().takeIf { it.isNotEmpty() && it != "null" } ?: return "Error: add_note needs text"
                        if (!dryRun) try { actions.addNote(text) } catch (e: Exception) { return "Error: ${e.message}" }
                        done("added to quick notes: $text")
                    }
                    else -> bad()
                }
                else -> "Error: there is no tool called $name"
            }
        }

        private fun done(what: String): String { did += what; return if (dryRun) "Done (test run, nothing was sent): $what" else "Done: $what" }
        private fun given(args: JSONObject, key: String) = args.has(key) && !args.isNull(key) && args.optString(key).trim().let { it.isNotEmpty() && it != "null" }

        private suspend fun listAlarms(): String = try {
            val list = actions.watchAlarms()
            val voice = alerts.filter { it.kind == Alexa.ALERT_ALARM }
            (if (list.isEmpty()) "No alarms on the watch." else "Alarms on the watch, by slot:\n" + list.joinToString("\n") { it.describe() }) +
                (if (voice.isEmpty()) "" else "\nOlder voice alarms (cancel with timers_and_reminders, action cancel): " + voice.joinToString { "${it.id}: ${it.describe(t)}" })
        } catch (e: Exception) { "Error: couldn't read the watch's alarms: ${e.message}" }

        /**
         * A new alarm (time or in_seconds, and days), a change to one (slot and what changes) or a switch (slot and on).
         * "in N minutes" becomes a one-off alarm at that minute.
         */
        private suspend fun setAlarm(args: JSONObject): String {
            val slot = if (given(args, "slot")) args.optInt("slot", -1).takeIf { it in 0..15 } ?: return "Error: slot is 0 to 15, from action list" else null
            val on = if (given(args, "on")) args.optBoolean("on", true) else true
            val daysGiven = args.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.optString(it) } }
            val days = daysGiven?.let { WatchAlarm.parseDays(it) ?: return "Error: days are day names, weekdays, weekends, daily or once" }
            val soon = args.optInt("in_seconds", 0).takeIf { it > 0 }
            if (!given(args, "time") && soon == null && daysGiven == null) {
                slot ?: return "Error: give a time (or in_seconds) for a new alarm, or a slot to switch on or off"
                if (!dryRun) try { actions.switchWatchAlarm(slot, on) } catch (e: Exception) { return "Error: ${e.message}" }
                return done("switched alarm $slot ${if (on) "on" else "off"}")
            }
            val existing = if (slot != null && soon == null && (!given(args, "time") || daysGiven == null)) runCatching { actions.watchAlarms() }.getOrNull()?.firstOrNull { it.slot == slot } else null
            val at: LocalTime = when {
                soon != null -> whenOf(args, roundToMinute = true)!!.toLocalTime()
                given(args, "time") -> runCatching { LocalTime.parse(args.optString("time").trim().padStart(5, '0')) }.getOrNull() ?: return "Error: time is HH:MM, 24-hour"
                else -> existing?.hour?.let { LocalTime.of(it, existing.minute ?: 0) } ?: return "Error: give the time; alarm $slot's time isn't known"
            }
            val repeat = if (soon != null) emptySet() else days ?: existing?.days ?: emptySet()
            val used = if (dryRun) slot ?: 0 else try { actions.setWatchAlarm(slot, at.hour, at.minute, repeat, on) } catch (e: Exception) { return "Error: ${e.message}" }
            val ring = if (repeat.isEmpty()) " (rings ${clock(next(t, at))})" else ""
            return done("alarm $used set for %02d:%02d, %s, %s".format(at.hour, at.minute, WatchAlarm.repeatWords(repeat), if (on) "on" else "off") + ring)
        }

        private suspend fun deleteAlarms(args: JSONObject): String {
            val slots = args.optJSONArray("slots")?.let { a -> (0 until a.length()).map { a.optInt(it, -1) } }.orEmpty()
                .plus(if (given(args, "slot")) listOf(args.optInt("slot", -1)) else emptyList()).filter { it in 0..15 }.distinct()
            if (slots.isEmpty()) return "Error: give the slots to delete, from action list"
            val failed = ArrayList<String>()
            if (!dryRun) for (s in slots) try { actions.deleteWatchAlarm(s) } catch (e: Exception) { failed += "alarm $s: ${e.message}" }
            if (failed.size == slots.size) return "Error: " + failed.joinToString("; ")
            return done("deleted " + slots.filter { s -> failed.none { it.startsWith("alarm $s:") } }.joinToString(" and ") { "alarm $it" }) +
                if (failed.isEmpty()) "" else "; failed: " + failed.joinToString("; ")
        }

        private suspend fun timer(args: JSONObject): String {
            val s = args.optInt("seconds", 0).takeIf { it in 1..86_399 } ?: return "Error: a timer needs seconds, 1 to 86399"
            val token = addAlert(Alexa.ALERT_TIMER, t.toEpochSecond() + s)
            frames += Alexa.timer(token, s / 3600, s / 60 % 60, s % 60)
            return done("timer for ${spoken(s)}, ringing at ${clock(t.toLocalDateTime().plusSeconds(s.toLong()))}")
        }

        private suspend fun reminder(args: JSONObject): String {
            val label = args.optString("label").trim().takeIf { it.isNotEmpty() && it != "null" } ?: return "Error: a reminder needs a label"
            val at = whenOf(args, roundToMinute = true) ?: return "Error: give time as HH:MM or in_seconds"
            // A watch reminder (the calendar slots, which show their title), written by the agenda sync.
            addAlert(Alexa.ALERT_REMINDER, at.atZone(t.zone).toEpochSecond(), label)
            return done("reminder \"${reminderTitle(label)}\" at ${clock(at)} (the watch shows the first ${dev.heartline.protocol.REMINDER_TITLE_LENGTH} characters)")
        }

        private suspend fun cancel(args: JSONObject): String {
            val ids = args.optJSONArray("ids")?.let { a -> (0 until a.length()).map { a.optInt(it, -1) } }.orEmpty()
            val gone = alerts.filter { it.id in ids }
            if (gone.isEmpty()) return "Error: none of those ids are set; the list is ${if (alerts.isEmpty()) "empty" else alerts.joinToString { "${it.id}: ${it.describe(t)}" }}"
            alerts = alerts - gone.toSet()
            if (!dryRun) saveAlerts(alerts)
            gone.filter { it.kind != Alexa.ALERT_REMINDER }.forEach { frames += Alexa.deleteAlert(it.kind, it.token) }
            return done("cancelled " + gone.joinToString(" and ") { it.describe(t) })
        }

        private fun table(args: JSONObject): String {
            val given = args.optJSONArray("rows") ?: return "Error: a table needs rows, each with left and right"
            val rows = fitLeft((0 until given.length()).mapNotNull { given.optJSONObject(it) }
                .map { watchText(it.optString("left")) to watchText(it.optString("right")) }
                .filter { (l, r) -> l.isNotEmpty() || r.isNotEmpty() }).map { (l, r) -> l to r.take(LIST_RIGHT) }
            if (rows.isEmpty()) return "Error: give at least one row with left or right text"
            val title = watchText(args.optString("title")).take(LIST_TITLE)
            val subtitle = watchText(args.optString("subtitle")).take(LIST_TITLE)
            // Keep the card within what a reply carries; rows that don't fit are left out and the model is told.
            var shown = rows.take(LIST_ROWS)
            while (shown.size > 1 && Alexa.list(title, subtitle, shown).size > LIST_BYTES) shown = shown.dropLast(1)
            frames += Alexa.list(title, subtitle, shown); card = true
            return done("showed a list of ${shown.size} rows" + if (shown.size < rows.size) " (the last ${rows.size - shown.size} didn't fit)" else "")
        }

        private suspend fun notifications(args: JSONObject): String {
            val body = watchText(args.optString("text")).takeIf { it.isNotEmpty() } ?: return "Error: notifications need text"
            val title = watchText(args.optString("title")).ifEmpty { "Heartline" }.take(24)
            val parts = notificationChunks(body)
            val n = if (dryRun) parts.size else try { actions.sendToWatch(title, parts) } catch (e: Exception) { return "Error: ${e.message}" }
            return done("sending it to the watch as $n notification${if (n == 1) "" else "s"}" + if (n < parts.size) " (the last ${parts.size - n} are over the limit)" else "")
        }

        private suspend fun phone(action: String, args: JSONObject): String {
            val percent = if (given(args, "percent")) args.optInt("percent").coerceIn(0, 100) else null
            val state = when (action) {
                in MEDIA_ACTIONS -> {
                    if (action == "set_volume" && percent == null) return "Error: set_volume needs percent"
                    if (dryRun) "" else try { actions.media(action, percent) } catch (e: Exception) { return "Error: ${e.message}" }
                }
                "ringer_normal", "ringer_vibrate", "ringer_silent", "ring_volume" -> {
                    if (action == "ring_volume" && percent == null) return "Error: ring_volume needs percent"
                    if (dryRun) "" else try { actions.ringer(action.removePrefix("ringer_").takeIf { action != "ring_volume" }, percent) } catch (e: Exception) { return "Error: ${e.message}" }
                }
                else -> return "Error: action for phone is one of ${CHOICES.getValue("phone").joinToString()}"
            }
            return done(action.replace('_', ' ') + (percent?.let { " $it%" } ?: "")) + if (state.isNotEmpty()) ". Now: $state" else ""
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

        val HELP = listOf("How did I sleep", "How's my HRV", "What's my readiness", "Start a heart check", "Start a gym workout",
            "Timer for 5 minutes", "Alarm at 7 am on weekdays", "List my alarms", "Remind me at 6 pm to call home", "Pause the music",
            "Put my phone on vibrate", "Note: buy milk", "Send me a pasta recipe", "What's the weather", "Ask me anything")

        private fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()) =
            JSONObject().put("type", "object").put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } }).put("required", JSONArray(required))
        private fun prop(type: String, description: String) = JSONObject().put("type", type).put("description", description)
        private val WHEN = arrayOf("time" to prop("string", "Time of day as HH:MM, 24-hour; the next one after now"),
            "in_seconds" to prop("integer", "Or: how many seconds from now"))

        /**
         * show_list limits. The watch showed all 25 rows of a test list and wrapped long right columns, but cuts the
         * left column at its width: "Long left text here" showed as "Long left T", "3. Kangchenjunga" as "3. Kangch",
         * while "1. Everest" fitted (2026-11-06). The total is kept within the reply size.
         */
        const val LIST_ROWS = 30; const val LIST_LEFT = 10; const val LIST_RIGHT = 200; const val LIST_TITLE = 40; const val LIST_BYTES = 2000

        /**
         * Rows whose left column all fit as they are; otherwise, for the whole table alike, the left text moves into the
         * right column ("3. Kangchenjunga", "8,586 m" becomes "3.", "Kangchenjunga - 8,586 m") keeping a short first
         * word such as a rank or day on the left, so nothing is cut off.
         */
        fun fitLeft(rows: List<Pair<String, String>>): List<Pair<String, String>> {
            if (rows.all { it.first.length <= LIST_LEFT }) return rows
            return rows.map { (left, right) ->
                val first = left.substringBefore(' ')
                val label = first.length in 1..3 || (first.length <= 6 && first.any { it.isDigit() }) // "3.", "Mon", "10:30"
                val (keep, moved) = if (label && first.length < left.length) first to left.substringAfter(' ').trim() else "" to left
                keep to listOf(moved, right).filter { it.isNotEmpty() }.joinToString(" - ")
            }
        }

        private const val DID = "set|cancell?ed|deleted|removed|started|stopped|sent|sending|added|noted|saved|updated|changed|moved|" +
            "switched|turned|paused|resumed|skipped|muted|unmuted|repeats?|synced|syncing"
        private const val THING = "timers?|alarms?|reminders?|workout|capture|sync|notifications?|notes?|ringer|vibrate|silent|music|" +
            "song|track|volume|media|heart check|check"
        /**
         * A reply that says something was set, sent, changed, started or stopped (2026-11-09: "I've sent a short summary to
         * your watch notifications" and "Your 8:45 AM alarm now repeats every day" with no tool called).
         */
        private val CLAIMED_ACTION = Regex("(?i)^\\W*(done|cancell?ed|deleted|removed|stopped|started|sent|added|noted)\\b" +
            "|\\bI(?:'ve| have)\\s+(?:just\\s+)?($DID)\\b" +
            "|\\b($THING)\\b[^.?!\\n]{0,40}\\b($DID)\\b" +
            "|\\b($DID)\\b[^.?!\\n]{0,50}\\b($THING)\\b")

        /** Sent as the next message when [reply] claimed an action no tool did. */
        private fun undone(reply: String) = "(Heartline, not the user: your reply \"$reply\" says something was done, but you called " +
            "no tool, so nothing happened on the watch. If they asked for an action, call its tool now and then reply; if " +
            "they didn't, just give your answer again.)"

        /** get_info topics the assistant answers itself; the rest come from Heartline's data ([HealthTools]). */
        val OWN_TOPICS = listOf("weather" to "the weather where the phone is: now, today and the next three days",
            "phone" to "the phone's ringer mode, ring and media volume, what's playing and its battery")

        val MEDIA_ACTIONS = listOf("play", "pause", "play_pause", "next", "previous", "stop", "volume_up", "volume_down", "set_volume", "mute", "unmute")

        /** Each grouped tool's actions (get_info: the topics it answers itself). */
        val CHOICES: Map<String, List<String>> = mapOf(
            "get_info" to OWN_TOPICS.map { it.first },
            "alarms" to listOf("list", "set", "delete"),
            "timers_and_reminders" to listOf("timer", "reminder", "cancel"),
            "show_on_watch" to listOf("table", "weather", "things_to_ask", "notifications"),
            "recording" to listOf("heart_check", "start_workout", "stop_workout"),
            "phone" to MEDIA_ACTIONS + listOf("ringer_normal", "ringer_vibrate", "ringer_silent", "ring_volume"),
            "app" to listOf("sync_watch", "add_note"),
        )

        private fun action(tool: String, description: String) =
            "action" to JSONObject().put("type", "string").put("enum", JSONArray(CHOICES.getValue(tool))).put("description", description)
        private fun intList(description: String) = JSONObject().put("type", "array").put("items", JSONObject().put("type", "integer")).put("description", description)

        /**
         * The actions the brain can take, grouped by what they act on: a few tools, each with an action, keep the
         * choice simple for the model (about 30 single-purpose tools was too many to pick from reliably).
         */
        val ACTION_TOOLS = listOf(
            Tool("alarms", "The watch's alarms. list: every alarm by slot with time, repeat days and on or off (alarms made on the watch show " +
                "without a time and can only be changed or deleted on the watch itself)." +
                " set: a new alarm (time, or in_seconds for \"in N minutes\", and days to repeat; " +
                "no slot), a change to one (slot and what changes), or switching one on or off (slot and on only). delete: by slots. " +
                "List first when they refer to an existing alarm.",
                obj(action("alarms", "list, set or delete"),
                    "time" to prop("string", "set: time of day as HH:MM, 24-hour"),
                    "in_seconds" to prop("integer", "set: or how many seconds from now, for a one-off alarm"),
                    "days" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
                        .put("description", "set: repeat days: day names (mon, tue...), weekdays, weekends or daily; leave out or [\"once\"] for no repeat"),
                    "on" to prop("boolean", "set: switched on (true if left out) or off"),
                    "slot" to prop("integer", "set: the alarm to change or switch, from list"),
                    "slots" to intList("delete: the alarms to delete, from list"), required = listOf("action"))),
            Tool("timers_and_reminders", "timer: a countdown on the watch (seconds). reminder: the watch shows label at a time (time or " +
                "in_seconds). cancel: timers, reminders or older voice alarms by their ids from the list in the instructions.",
                obj(action("timers_and_reminders", "timer, reminder or cancel"),
                    "seconds" to prop("integer", "timer: length in seconds"),
                    "label" to prop("string", "reminder: what to remind them about, short (the watch shows 16 characters)"),
                    *WHEN, "ids" to intList("cancel: ids to cancel"), required = listOf("action"))),
            Tool("show_on_watch", "Show something on the watch. table: rows of a short left column (about 9 characters: a rank, day, time or " +
                "number) and a wrapping right column, under an optional title and subtitle; use it for anything in rows (a ranking, a " +
                "schedule, a forecast by day, prices, steps with timings) even if they say list; it replaces the text reply, so put " +
                "everything in it. weather: the weather card, whenever they ask about the weather in general. things_to_ask: what " +
                "they can ask, when they ask what you can do. notifications: long text (a story, recipe, explanation or summary) sent " +
                "as watch notifications to read later, split as needed; put the whole text in it and reply in a few words.",
                obj(action("show_on_watch", "table, weather, things_to_ask or notifications"),
                    "title" to prop("string", "table or notifications: short heading; optional"),
                    "subtitle" to prop("string", "table: second line under the title; optional"),
                    "rows" to JSONObject().put("type", "array").put("description", "table: up to $LIST_ROWS rows, in order").put("items",
                        obj("left" to prop("string", "Left column, 9 characters at most: a rank, day, time or number"), "right" to prop("string", "Right column"))),
                    "text" to prop("string", "notifications: the full text; plain text, new lines allowed"), required = listOf("action"))),
            Tool("recording", "heart_check: the watch records the pulse for some minutes while they sit still; heart rate, HRV and rhythm " +
                "come as a watch notification when done. start_workout: Heartline's workout capture (sets, reps and heart rate from the " +
                "watch's sensors). stop_workout: stop the capture.",
                obj(action("recording", "heart_check, start_workout or stop_workout"),
                    "minutes" to prop("integer", "heart_check: 1 to 5, 2 if they don't say; start_workout: how long to capture, 60 if they don't say"),
                    "label" to prop("string", "start_workout: the workout they named, such as Gym or Run"), required = listOf("action"))),
            Tool("phone", "Control the phone: media playing on it (play, pause, play_pause, next, previous, stop, volume_up, volume_down, " +
                "set_volume, mute, unmute) and its ringer (ringer_normal, ringer_vibrate, ringer_silent, ring_volume). Returns the state after.",
                obj(action("phone", "what to do"), "percent" to prop("integer", "set_volume or ring_volume: 0 to 100"), required = listOf("action"))),
            Tool("app", "Heartline itself. sync_watch: fetch the latest data from the watch now. add_note: add a line to their quick notes.",
                obj(action("app", "sync_watch or add_note"), "text" to prop("string", "add_note: the note, as they said it"), required = listOf("action"))),
        )

        /** The tools that do something (all but the lookups). */
        val ACTION_NAMES: Set<String> = ACTION_TOOLS.map { it.name }.toSet()

        fun parseAlerts(json: String?): List<AlexaAlert> = runCatching {
            val a = JSONArray(json ?: "[]")
            (0 until a.length()).map { a.getJSONObject(it) }.map { AlexaAlert(it.getInt("id"), it.getInt("kind"), it.getString("token"), it.getLong("at"), it.optString("label")) }
        }.getOrDefault(emptyList())

        /** The voice reminders still to come, for the watch's reminder slots (shared with calendar events). */
        fun voiceReminders(json: String?, nowEpoch: Long): List<CalendarReminder> =
            parseAlerts(json).filter { it.kind == Alexa.ALERT_REMINDER && it.at >= nowEpoch }.map { CalendarReminder(reminderTitle(it.label), it.at, it.at) }

        /**
         * [text] as the watch draws it. Its font has every script tried (Devanagari, Malayalam, Tamil, CJK, Arabic, Thai...)
         * and the usual symbols (° ± ½ × ÷ ≤ ² ‰ € £ ¥ © ™ arrows, ▲ ◆ ○ ■ ● ① Ⅱ, box drawing) and draws new lines and
         * blank lines, but no markdown and only some emoji; a character it lacks shows as a box or nothing (tested
         * 2026-11-06). So markdown becomes plain text (a heading its words, `*` bullets "-", a link its words, table
         * rows cells between " | "); emoji outside [EMOJI], the emoji-style symbols outside [SYMBOLS] (❤ ✔ ✗ ⏰ ⚡ ❄...)
         * and the parts of combined emoji (variation selectors, joiners, skin tones, flags, keycaps) are dropped; the
         * bullets it lacks (• ‣ ▸ ►) become "-" and ₹ "Rs"; typographic punctuation becomes ASCII and odd spaces (Java's
         * clock format puts U+202F before AM/PM) spaces.
         */
        fun watchText(text: String): String {
            val plain = plainMarkdown(text).replace(Regex("[\\u00A0\\u2000-\\u200B\\u202F\\u205F]"), " ")
                .replace(Regex("[\\u2018\\u2019\\u201B\\u2032]"), "'").replace(Regex("[\\u201C\\u201D\\u201F\\u2033]"), "\"")
                .replace(Regex("[\\u2010-\\u2015\\u2212]"), "-").replace("…", "...").replace(Regex("[•‣▸►]"), "-").replace(Regex("₹ ?"), "Rs ")
            val sb = StringBuilder()
            var i = 0
            while (i < plain.length) {
                val cp = plain.codePointAt(i); i += Character.charCount(cp)
                if (cp in 0xFE00..0xFE0F || cp == 0x200D || cp == 0x20E3 || cp in 0x1F3FB..0x1F3FF || cp in 0x1F1E6..0x1F1FF) continue
                if (Character.getType(cp) == Character.SURROGATE.toInt()) continue
                if ((cp > 0xFFFF || EMOJI_BLOCKS.any { cp in it }) && cp !in EMOJI && cp !in SYMBOLS) continue
                sb.appendCodePoint(cp)
            }
            return sb.toString().replace(Regex("[ \\t]{2,}"), " ").lines().joinToString("\n") { it.trimEnd() }
                .replace(Regex("\n{3,}"), "\n\n").trim()
        }

        private val TABLE_RULE = Regex("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$")
        private val TABLE_ROW = Regex("^\\s*\\|.*\\|\\s*$")

        /** Markdown as the plain text it stands for. */
        fun plainMarkdown(text: String): String = text.lines().filterNot { TABLE_RULE.matches(it) }.joinToString("\n") { line ->
            val l = line.replace(Regex("^\\s{0,3}#{1,6}\\s+"), "").replace(Regex("^(\\s*)[*+]\\s+"), "$1- ").replace(Regex("^\\s*>\\s?"), "")
            if (TABLE_ROW.matches(l)) l.trim().removePrefix("|").removeSuffix("|").split("|").joinToString(" | ") { it.trim() } else l
        }
            .replace(Regex("!?\\[([^\\]\\n]+)]\\([^)\\s]+\\)"), "$1")
            .replace(Regex("\\*\\*|__|~~|`"), "")
            .replace(Regex("(?<![\\w*])\\*(?=\\S)([^*\\n]+?)(?<=\\S)\\*(?![\\w*])"), "$1")

        /**
         * The emoji the watch draws (2026-11-06): the whole face block U+1F600 to U+1F64F, the hands, body and people
         * from U+1F440 to U+1F46F and the sports from U+1F3C0 to U+1F3CA were tried one by one, and about 60 others
         * (hearts, weather, food, objects, ✅ ❌ ⭐ 🔥 🎉, flags) of which only 💪 🚶 ☀ ☁ ⚠ ⚽ showed. Not a Unicode version
         * or a popularity list: 😊 😩 🙂 👎 👨 are missing.
         */
        private val EMOJI_RANGES = listOf(0x1F600..0x1F609, 0x1F60B..0x1F628, 0x1F62A..0x1F637, 0x1F646..0x1F647, 0x1F64C..0x1F64C,
            0x1F64F..0x1F64F, 0x1F440..0x1F440, 0x1F442..0x1F445, 0x1F447..0x1F44D, 0x1F44F..0x1F450, 0x1F466..0x1F467,
            0x1F469..0x1F46A, 0x1F46D..0x1F46F, 0x1F4AA..0x1F4AA, 0x1F3C3..0x1F3C3, 0x1F6B6..0x1F6B6)
        val EMOJI_SHOWN: String = EMOJI_RANGES.flatMap { it.toList() }.joinToString("") { String(Character.toChars(it)) } + "☀☁⚠⚽"
        val EMOJI: Set<Int> = EMOJI_SHOWN.codePoints().toArray().toSet()

        /** Blocks below U+FFFF where most characters are emoji: Misc Technical, Misc Symbols and Dingbats, Misc Symbols and Arrows. */
        private val EMOJI_BLOCKS = listOf(0x2300..0x23FF, 0x2600..0x27BF, 0x2B00..0x2BFF)

        /** Plain symbols in [EMOJI_BLOCKS] the watch draws (✔ ✗ ✘ ☑ ❤ ⏰ ⌚ ⚡ ❄ ✈ ⌛ ➜ beside them did not). */
        const val SYMBOLS_SHOWN = "✓★☆♪♫♥☂☎"
        val SYMBOLS: Set<Int> = SYMBOLS_SHOWN.codePoints().toArray().toSet()

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
