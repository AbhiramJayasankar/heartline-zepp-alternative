package dev.heartline.service

import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The owner's OpenRouter key (typed into the app, stored in meta) and model; empty key = no answers. */
data class AssistantAiConfig(val apiKey: String = "", val model: String = DEFAULT_MODEL) {
    val enabled get() = apiKey.isNotBlank()
    fun json(): String = JSONObject().put("key", apiKey).put("model", model).toString()
    companion object {
        const val KEY = "assistant_ai"
        /** NVIDIA's newest free model on OpenRouter (2026-10-15), with JSON output. */
        const val DEFAULT_MODEL = "nvidia/nemotron-3.5-super-vl:free"
        /**
         * Tried in order when the chosen model fails (rate limits are common on free models). Picked by hand: the
         * openrouter/free router chose any free model, including a coding model that ignored the format (2026-11-06).
         */
        val FALLBACK_MODELS = listOf("nvidia/nemotron-3.5-lightning:free", "google/gemma-4-31b-it:free")
        /** Defaults saved by earlier versions, moved to [DEFAULT_MODEL]. */
        private val OLD_DEFAULTS = setOf("google/gemma-4-31b-it:free", "openrouter/free")
        fun parse(text: String?): AssistantAiConfig = runCatching {
            val o = JSONObject(text ?: "{}")
            AssistantAiConfig(o.optString("key").trim(), o.optString("model").trim().takeUnless { it.isEmpty() || it in OLD_DEFAULTS } ?: DEFAULT_MODEL)
        }.getOrDefault(AssistantAiConfig())
    }
}

/** What the brain is told besides the words: the clock, Heartline's latest numbers, the weather and what's set on the watch. */
data class AiContext(val now: ZonedDateTime, val heartRate: String? = null, val stepsToday: Int? = null, val weather: String? = null,
                     val alerts: List<AlexaAlert> = emptyList(), val dataAsOf: String? = null,
                     /** Heartline's health summary (readiness, sleep, stress, heart rate, trends), one fact per line. */
                     val health: String? = null,
                     /** The workout capture running in Heartline, if any. */
                     val workout: String? = null)

/** What the user asked for, as the brain understood it; [WatchAssistant] carries it out on the watch. */
sealed interface Ask {
    /** Words to show; [listenAgain] when they ask the user something back. */
    data class Say(val text: String, val listenAgain: Boolean = false) : Ask
    data class Timer(val seconds: Int) : Ask
    /** At the next [at], or in [inSeconds]. */
    data class Alarm(val at: LocalTime? = null, val inSeconds: Int? = null) : Ask
    /** In [inSeconds], or at the next [at]. */
    data class Reminder(val label: String, val inSeconds: Int? = null, val at: LocalTime? = null) : Ask
    /** Ids from the alerts listed in [AiContext.alerts]. */
    data class Cancel(val ids: List<Int>) : Ask
    data object Weather : Ask
    data object Help : Ask
    /** Heartline's workout capture (sets, reps, heart rate from the watch's raw sensors). */
    data class StartWorkout(val label: String, val minutes: Int) : Ask
    data object StopWorkout : Ask
    data object Sync : Ask
}

/**
 * The Alexa button's brain: an OpenRouter model turns what Gemini heard into one [Ask]. It throws with a readable
 * message when it can't, and the watch shows that message; there is no fallback. The key is never logged.
 */
class AssistantAi(private val post: suspend (url: String, headers: Map<String, String>, body: String) -> String = ::httpPost) {

    suspend fun interpret(transcript: String, context: AiContext, history: List<Pair<String, String>>, config: AssistantAiConfig): Ask {
        check(config.enabled) { "no OpenRouter key saved" }
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt(context)))
        history.takeLast(6).forEach { (asked, said) ->
            messages.put(JSONObject().put("role", "user").put("content", asked)).put(JSONObject().put("role", "assistant").put("content", said))
        }
        messages.put(JSONObject().put("role", "user").put("content", transcript))
        val body = JSONObject().put("model", config.model)
            .put("models", JSONArray((listOf(config.model) + AssistantAiConfig.FALLBACK_MODELS).distinct().take(3)))
            .put("messages", messages).put("temperature", 0.2).put("max_tokens", 1500)
            .put("response_format", JSONObject().put("type", "json_object"))
        val headers = mapOf("Authorization" to "Bearer ${config.apiKey}", "Content-Type" to "application/json",
            "HTTP-Referer" to "https://github.com/AbhiramJayasankar/heartline-zepp-alternative", "X-Title" to "Heartline")
        // Thinking is slow and can eat the token budget, so ask for none. Models that must reason refuse that
        // ("Reasoning is mandatory for this endpoint", 2026-11-06): ask them again with a little reasoning and room
        // for it, since nemotron-3-nano reasoned through 1500 tokens without answering "ChatGPT or Claude?".
        val reply = try {
            post(URL, headers, JSONObject(body.toString()).put("reasoning", JSONObject().put("enabled", false)).toString())
        } catch (e: OpenRouterError) {
            if (!e.message.orEmpty().contains("reasoning", ignoreCase = true)) throw e
            post(URL, headers, JSONObject(body.toString()).put("max_tokens", REASONING_MAX_TOKENS)
                .put("reasoning", JSONObject().put("effort", "low").put("exclude", true)).toString())
        }
        val choice = runCatching { JSONObject(reply).getJSONArray("choices").getJSONObject(0) }.getOrNull()
            ?: error("no answer in OpenRouter's reply: ${reply.take(120)}")
        val message = choice.getJSONObject("message")
        val model = JSONObject(reply).optString("model").ifEmpty { config.model }
        lastModel = model
        val finish = choice.optString("finish_reason")
        fun text(k: String) = message.optString(k).takeIf { !message.isNull(k) && it.isNotBlank() }
        val content = text("content")
        // The answer belongs in content. Some models leave it empty and end their reasoning with it, but reasoning
        // cut off by the token limit is only thinking, never the answer. A plain-text answer (no JSON) is shown as it is.
        return content?.let { toAsk(it) }
            ?: text("reasoning")?.takeIf { finish != "length" }?.let { toAsk(it) }
            ?: content?.takeIf { finish != "length" && '{' !in it }?.let { Ask.Say(it.trim().take(300)) }
            ?: error(if (finish == "length") "$model ran out of tokens before it answered"
                else "$model's answer wasn't in the form Heartline asked for" + (content?.let { ": ${it.take(100)}" } ?: ""))
    }

    /** The model that gave the last answer (OpenRouter may have used a fallback). */
    var lastModel: String? = null
        private set

    /** OpenRouter answered with an error: [message] is OpenRouter's own words, with the HTTP code. */
    class OpenRouterError(message: String) : Exception(message)

    companion object {
        const val URL = "https://openrouter.ai/api/v1/chat/completions"
        const val REASONING_MAX_TOKENS = 4000
        /**
         * Thinking models can be slow. The watch gives up after about 40 s on "Thinking", so a slower answer is sent
         * as a watch notification instead (see AlexaBridge); this is the limit for that.
         */
        const val TIMEOUT_MS = 90_000L
        private val CLOCK = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)

        fun systemPrompt(c: AiContext): String = """
            You are the voice assistant on the user's Amazfit smartwatch, answering what they say to its Alexa button.
            Now: ${c.now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH))} (${c.now.zone.id}).
            Latest heart rate: ${c.heartRate ?: "unknown"}. Steps today: ${c.stepsToday?.toString() ?: "unknown"}${c.dataAsOf?.let { " (watch data synced up to $it)" } ?: ""}.
            Weather: ${c.weather ?: "unknown"}.
            Alarms, timers and reminders set from the watch: ${if (c.alerts.isEmpty()) "none" else c.alerts.joinToString("; ") { "id ${it.id}: ${it.describe(c.now)}" }}.
            Workout capture in Heartline: ${c.workout ?: "not running"}.
            Health data from the watch, as Heartline computed it:
            {HEALTH}
            Reply with ONLY one JSON object, no other text:
            {"action": "answer" | "timer" | "alarm" | "reminder" | "cancel" | "weather" | "help" | "start_workout" | "stop_workout" | "sync",
             "reply": string (for "answer": what the watch shows, plain text, at most 300 characters, no markdown),
             "follow_up": boolean (true when your reply asks the user something they should answer),
             "seconds": integer (timer length, or how long until a reminder),
             "time": "HH:MM" in 24-hour time (an alarm, or a reminder at a time of day),
             "label": string (what to remind them about),
             "ids": [integers] (for "cancel": which of the listed alarms, timers and reminders),
             "minutes": integer (for "start_workout": how long to capture; 60 if they don't say)}
            Examples: {"action":"answer","reply":"Your resting heart rate is 61 bpm."} {"action":"timer","seconds":300}
            {"action":"alarm","time":"07:00"} {"action":"reminder","seconds":120,"label":"drink water"}
            Use "answer" for anything you can say in words, including the time, date, heart rate, steps, questions about the
            weather and general questions. Use "weather" only to show the weather card when they ask what the weather is like.
            Use "help" when they ask what you can do. For an alarm or reminder time without am/pm, pick the next occurrence
            after now; for one "in N minutes", give "seconds" instead of "time". Write replies for a small watch screen:
            short plain sentences, no emoji. For a game or joke where they answer back (like a knock-knock joke), say only
            your next line and set "follow_up" to true. Answer questions about their sleep, stress, readiness, heart rate, activity and trends from the health
            data above, briefly and in plain words, and say so when something isn't there. "start_workout" starts Heartline's
            workout capture (it records sets, reps and heart rate; always set "label" to the workout they named, such as "Gym" or "Run"),
            "stop_workout" ends it, "sync" fetches the latest data from the watch. If the words make no sense, use "answer"
            and ask them to say it again.
        """.trimIndent().replace("{HEALTH}", c.health?.lines()?.filter { it.isNotBlank() }?.joinToString("\n") { "- $it" } ?: "- not available")

        /** [ask] as the JSON the model should have replied with: the conversation history the next question sees. */
        fun json(ask: Ask): String = when (ask) {
            is Ask.Say -> JSONObject().put("action", "answer").put("reply", ask.text).put("follow_up", ask.listenAgain)
            is Ask.Timer -> JSONObject().put("action", "timer").put("seconds", ask.seconds)
            is Ask.Alarm -> JSONObject().put("action", "alarm").apply { ask.at?.let { put("time", it.toString()) }; ask.inSeconds?.let { put("seconds", it) } }
            is Ask.Reminder -> JSONObject().put("action", "reminder").put("label", ask.label).apply { ask.at?.let { put("time", it.toString()) }; ask.inSeconds?.let { put("seconds", it) } }
            is Ask.Cancel -> JSONObject().put("action", "cancel").put("ids", JSONArray(ask.ids))
            is Ask.StartWorkout -> JSONObject().put("action", "start_workout").put("label", ask.label).put("minutes", ask.minutes)
            Ask.Weather -> JSONObject().put("action", "weather")
            Ask.Help -> JSONObject().put("action", "help")
            Ask.StopWorkout -> JSONObject().put("action", "stop_workout")
            Ask.Sync -> JSONObject().put("action", "sync")
        }.toString()

        /** The first JSON object in [content] (models sometimes add fences or words around it) as an [Ask]. */
        fun toAsk(content: String): Ask? {
            val a = content.indexOf('{'); val b = content.lastIndexOf('}')
            if (a == -1 || b <= a) return null
            val o = runCatching { JSONObject(content.substring(a, b + 1)) }.getOrNull() ?: return null
            val seconds = o.optInt("seconds", 0).takeIf { it > 0 }
            val time = (o.optString("time").ifBlank { o.optString("alarm_time") }).trim().takeIf { it.isNotEmpty() }
                ?.let { runCatching { LocalTime.parse(it.padStart(5, '0')) }.getOrNull() }
            val reply = o.optString("reply").trim()
            return when (o.optString("action").lowercase(Locale.ENGLISH)) {
                "answer", "chat" -> reply.takeIf { it.isNotEmpty() }?.let { Ask.Say(it.take(300), o.optBoolean("follow_up")) }
                "timer" -> seconds?.let { Ask.Timer(it) }
                "alarm" -> time?.let { Ask.Alarm(it) } ?: seconds?.let { Ask.Alarm(inSeconds = it) }
                "reminder" -> if (seconds == null && time == null) null else Ask.Reminder(o.optString("label").trim().ifEmpty { "check in" }, seconds, if (seconds == null) time else null)
                "cancel" -> o.optJSONArray("ids")?.let { ids -> Ask.Cancel((0 until ids.length()).mapNotNull { ids.optInt(it, -1).takeIf { id -> id >= 0 } }) }
                "weather" -> Ask.Weather
                "help" -> Ask.Help
                "start_workout" -> Ask.StartWorkout(o.optString("label").trim().ifEmpty { "Workout" }, o.optInt("minutes", 60).coerceIn(1, 720))
                "stop_workout" -> Ask.StopWorkout
                "sync" -> Ask.Sync
                // A reply with no action, or one Heartline doesn't know, is still an answer.
                else -> reply.takeIf { it.isNotEmpty() }?.let { Ask.Say(it.take(300), o.optBoolean("follow_up")) }
            }
        }

        suspend fun httpPost(url: String, headers: Map<String, String>, body: String): String = withContext(Dispatchers.IO) {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.connectTimeout = 10_000; c.readTimeout = TIMEOUT_MS.toInt(); c.doOutput = true
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                c.outputStream.use { it.write(body.toByteArray()) }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw OpenRouterError("OpenRouter $code: ${errorMessage(text)}")
                // A routed request can also fail inside a 200 reply.
                JSONObject(text).optJSONObject("error")?.let { throw OpenRouterError("OpenRouter: ${errorMessage(text)}") }
                text
            } finally { c.disconnect() }
        }

        /** OpenRouter's error message, with the provider's own one when it passed one on. */
        fun errorMessage(body: String): String = runCatching {
            val e = JSONObject(body).getJSONObject("error")
            val raw = e.optJSONObject("metadata")?.optString("raw")?.takeIf { it.isNotBlank() }
                ?.let { r -> runCatching { JSONObject(r).optJSONObject("error")?.optString("message") }.getOrNull() ?: r }
            listOfNotNull(e.optString("message").takeIf { it.isNotBlank() }, raw?.take(160)).distinct().joinToString(": ")
        }.getOrNull()?.ifBlank { null } ?: body.take(160)
    }
}
