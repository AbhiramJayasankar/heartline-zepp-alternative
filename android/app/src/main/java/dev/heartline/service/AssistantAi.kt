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
        const val DEFAULT_MODEL = "google/gemma-4-31b-it:free"
        /** OpenRouter's router over whichever free models are up, tried when the chosen one fails. */
        const val FALLBACK_MODEL = "openrouter/free"
        fun parse(text: String?): AssistantAiConfig = runCatching {
            val o = JSONObject(text ?: "{}")
            AssistantAiConfig(o.optString("key").trim(), o.optString("model").trim().ifEmpty { DEFAULT_MODEL })
        }.getOrDefault(AssistantAiConfig())
    }
}

/** What the brain is told besides the words: the clock, Heartline's latest numbers, the weather and what's set on the watch. */
data class AiContext(val now: ZonedDateTime, val heartRate: String? = null, val stepsToday: Int? = null, val weather: String? = null,
                     val alerts: List<AlexaAlert> = emptyList())

/** What the user asked for, as the brain understood it; [WatchAssistant] carries it out on the watch. */
sealed interface Ask {
    /** Words to show; [listenAgain] when they ask the user something back. */
    data class Say(val text: String, val listenAgain: Boolean = false) : Ask
    data class Timer(val seconds: Int) : Ask
    data class Alarm(val at: LocalTime) : Ask
    /** In [inSeconds], or at the next [at]. */
    data class Reminder(val label: String, val inSeconds: Int? = null, val at: LocalTime? = null) : Ask
    /** Ids from the alerts listed in [AiContext.alerts]. */
    data class Cancel(val ids: List<Int>) : Ask
    data object Weather : Ask
    data object Help : Ask
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
            .put("models", JSONArray(listOf(config.model, AssistantAiConfig.FALLBACK_MODEL).distinct()))
            .put("messages", messages).put("temperature", 0.2).put("max_tokens", 1500)
            .put("response_format", JSONObject().put("type", "json_object"))
        val headers = mapOf("Authorization" to "Bearer ${config.apiKey}", "Content-Type" to "application/json",
            "HTTP-Referer" to "https://github.com/AbhiramJayasankar/heartline-zepp-alternative", "X-Title" to "Heartline")
        // Thinking is slow and can eat the token budget, so ask for none; models that must reason refuse that
        // ("Reasoning is mandatory for this endpoint", 2026-11-06), so ask them again with reasoning left on.
        val reply = try {
            post(URL, headers, JSONObject(body.toString()).put("reasoning", JSONObject().put("enabled", false)).toString())
        } catch (e: OpenRouterError) {
            if (!e.message.orEmpty().contains("reasoning", ignoreCase = true)) throw e
            post(URL, headers, body.toString())
        }
        val choice = runCatching { JSONObject(reply).getJSONArray("choices").getJSONObject(0) }.getOrNull()
            ?: error("no answer in OpenRouter's reply: ${reply.take(120)}")
        val message = choice.getJSONObject("message")
        // The answer belongs in content; some models leave it empty and put the JSON at the end of their reasoning.
        val texts = listOf("content", "reasoning").mapNotNull { k -> message.optString(k).takeIf { !message.isNull(k) && it.isNotBlank() } }
        return texts.firstNotNullOfOrNull { toAsk(it) }
            ?: error("${JSONObject(reply).optString("model").ifEmpty { config.model }} gave an unusable answer" +
                " (${choice.optString("finish_reason").ifEmpty { "?" }}): ${texts.firstOrNull()?.take(100) ?: "nothing"}")
    }

    /** OpenRouter answered with an error: [message] is OpenRouter's own words, with the HTTP code. */
    class OpenRouterError(message: String) : Exception(message)

    companion object {
        const val URL = "https://openrouter.ai/api/v1/chat/completions"
        private val CLOCK = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)

        fun systemPrompt(c: AiContext): String = """
            You are the voice assistant on the user's Amazfit smartwatch, answering what they say to its Alexa button.
            Now: ${c.now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH))} (${c.now.zone.id}).
            Latest heart rate: ${c.heartRate ?: "unknown"}. Steps today: ${c.stepsToday?.toString() ?: "unknown"}.
            Weather: ${c.weather ?: "unknown"}.
            Alarms, timers and reminders set from the watch: ${if (c.alerts.isEmpty()) "none" else c.alerts.joinToString("; ") { "id ${it.id}: ${it.describe(c.now)}" }}.
            Reply with ONLY one JSON object, no other text:
            {"action": "answer" | "timer" | "alarm" | "reminder" | "cancel" | "weather" | "help",
             "reply": string (for "answer": what the watch shows, plain text, at most 300 characters, no markdown),
             "follow_up": boolean (true when your reply asks the user something they should answer),
             "seconds": integer (timer length, or how long until a reminder),
             "time": "HH:MM" in 24-hour time (an alarm, or a reminder at a time of day),
             "label": string (what to remind them about),
             "ids": [integers] (for "cancel": which of the listed alarms, timers and reminders)}
            Use "answer" for anything you can say in words, including the time, date, heart rate, steps, questions about the
            weather and general questions. Use "weather" only to show the weather card when they ask what the weather is like.
            Use "help" when they ask what you can do. For an alarm or reminder time without am/pm, pick the next occurrence
            after now. If the words make no sense, use "answer" and ask them to say it again.
        """.trimIndent()

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
                "alarm" -> time?.let { Ask.Alarm(it) }
                "reminder" -> if (seconds == null && time == null) null else Ask.Reminder(o.optString("label").trim().ifEmpty { "check in" }, seconds, if (seconds == null) time else null)
                "cancel" -> o.optJSONArray("ids")?.let { ids -> Ask.Cancel((0 until ids.length()).mapNotNull { ids.optInt(it, -1).takeIf { id -> id >= 0 } }) }
                "weather" -> Ask.Weather
                "help" -> Ask.Help
                else -> null
            }
        }

        suspend fun httpPost(url: String, headers: Map<String, String>, body: String): String = withContext(Dispatchers.IO) {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.connectTimeout = 5_000; c.readTimeout = 15_000; c.doOutput = true
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
