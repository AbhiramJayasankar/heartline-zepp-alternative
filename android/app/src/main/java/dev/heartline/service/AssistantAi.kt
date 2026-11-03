package dev.heartline.service

import dev.heartline.service.SampleAssistant.Ask
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

/** The owner's OpenRouter key (typed into the app, stored in meta) and model; empty key = AI off. */
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

data class AiContext(val now: ZonedDateTime, val heartRate: String?, val stepsToday: Int?)

/**
 * Turns a watch question into an [Ask] with a free OpenRouter model. The transcript comes from Vosk's small model, which
 * mishears numbers ("my and thirty" for "nine thirty"), so the model gets the clock and recent turns to repair it.
 * Any failure returns null and the rule parser answers instead; the key is never logged.
 */
class AssistantAi(private val post: suspend (url: String, headers: Map<String, String>, body: String) -> String = ::httpPost) {

    suspend fun interpret(transcript: String, context: AiContext, history: List<Pair<String, String>>, config: AssistantAiConfig): Ask? {
        lastError = null
        if (!config.enabled || transcript.isBlank()) return null
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt(context)))
        history.takeLast(6).forEach { (asked, said) ->
            messages.put(JSONObject().put("role", "user").put("content", asked)).put(JSONObject().put("role", "assistant").put("content", said))
        }
        messages.put(JSONObject().put("role", "user").put("content", transcript))
        val body = JSONObject().put("model", config.model)
            .put("models", JSONArray(listOf(config.model, AssistantAiConfig.FALLBACK_MODEL).distinct()))
            .put("messages", messages).put("temperature", 0.2).put("max_tokens", 300)
            .put("response_format", JSONObject().put("type", "json_object"))
        val reply = runCatching {
            post(URL, mapOf("Authorization" to "Bearer ${config.apiKey}", "Content-Type" to "application/json",
                "HTTP-Referer" to "https://heartline.local", "X-Title" to "Heartline"), body.toString())
        }.onFailure { lastError = it.message }.getOrNull() ?: return null
        val content = runCatching { JSONObject(reply).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content") }.getOrNull()
            ?: return null.also { lastError = "no answer in the reply" }
        return toAsk(content).also { if (it == null) lastError = "unusable answer: ${content.take(120)}" }
    }

    /** Why the last [interpret] returned null, for the activity log (never contains the key). */
    var lastError: String? = null
        private set

    companion object {
        const val URL = "https://openrouter.ai/api/v1/chat/completions"

        fun systemPrompt(c: AiContext): String = """
            You are the voice assistant on the user's smartwatch. Their words come from a weak offline speech recognizer that
            often mishears, especially numbers ("my" for "nine", "for" for "four", "to" or "too" for "two", "won" for "one",
            "ate" for "eight"). Work out what they most likely meant from the context and the conversation.
            Now: ${c.now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH))} (${c.now.zone.id}).
            Latest heart rate: ${c.heartRate ?: "unknown"}. Steps today: ${c.stepsToday?.toString() ?: "unknown"}.
            Reply with ONLY one JSON object, no other text:
            {"action": "answer" | "timer" | "cancel_timer" | "alarm" | "reminder" | "time" | "date" | "heart_rate" | "steps" | "weather" | "chat" | "help",
             "seconds": integer (timer length, or how long until a reminder),
             "alarm_time": "HH:MM" in 24-hour time (alarm),
             "label": string (what to remind about),
             "reply": string (for "answer" and "chat": what the watch shows, plain text, at most 300 characters, no markdown),
             "follow_up": boolean (true when your reply asks the user something)}
            Use "answer" for any general question or request you can answer in words. For an alarm without am/pm pick the
            next occurrence after now. If the words make no sense, use "answer" and ask them to say it again.
        """.trimIndent()

        /** The first JSON object in [content] (models sometimes add fences or words around it) as an [Ask]. */
        fun toAsk(content: String): Ask? {
            val a = content.indexOf('{'); val b = content.lastIndexOf('}')
            if (a == -1 || b <= a) return null
            val o = runCatching { JSONObject(content.substring(a, b + 1)) }.getOrNull() ?: return null
            val seconds = o.optInt("seconds", 0)
            val reply = o.optString("reply").trim()
            return when (o.optString("action").lowercase(Locale.ENGLISH)) {
                "timer" -> if (seconds > 0) Ask.Timer(seconds) else null
                "cancel_timer" -> Ask.CancelTimer
                "alarm" -> runCatching { LocalTime.parse(o.getString("alarm_time").trim().padStart(5, '0')) }.getOrNull()?.let { Ask.Alarm(it) }
                "reminder" -> if (seconds > 0) Ask.Reminder(seconds, o.optString("label").trim().ifEmpty { "check in" }) else null
                "time" -> Ask.Time
                "date" -> Ask.Date
                "heart_rate" -> Ask.HeartRate
                "steps" -> Ask.Steps
                "weather" -> Ask.Weather
                "help" -> Ask.Help
                "chat" -> Ask.Say(reply.ifEmpty { "Sure. What's on your mind?" }, listenAgain = true)
                "answer" -> reply.takeIf { it.isNotEmpty() }?.let { Ask.Say(it.take(300), o.optBoolean("follow_up")) }
                else -> null
            }
        }

        suspend fun httpPost(url: String, headers: Map<String, String>, body: String): String = withContext(Dispatchers.IO) {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.connectTimeout = 3000; c.readTimeout = 6000; c.doOutput = true
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                c.outputStream.use { it.write(body.toByteArray()) }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                check(code in 200..299) { "OpenRouter $code: ${text.take(200)}" }
                text
            } finally { c.disconnect() }
        }
    }
}
