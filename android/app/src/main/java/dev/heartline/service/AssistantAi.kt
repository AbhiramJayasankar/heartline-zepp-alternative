package dev.heartline.service

import java.net.HttpURLConnection
import java.net.URI
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
        /** NVIDIA's newest free model on OpenRouter (2026-10-15), with tool calling. */
        const val DEFAULT_MODEL = "nvidia/nemotron-3.5-super-vl:free"
        /**
         * Tried in order when the chosen model fails (rate limits are common on free models). Picked by hand, all with
         * tool calling: the openrouter/free router chose any free model, including a coding model (2026-11-06).
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

/** What the brain is told up front: the clock, the latest numbers, the weather, what's set on the watch, a short health glance. */
data class AiContext(val now: ZonedDateTime, val heartRate: String? = null, val stepsToday: Int? = null, val weather: String? = null,
                     val alerts: List<AlexaAlert> = emptyList(), val dataAsOf: String? = null,
                     /** A few lines on today (readiness, last night, heart rate, stress, activity); detail comes from tools. */
                     val health: String? = null,
                     /** The workout capture running in Heartline, if any. */
                     val workout: String? = null)

/** The tools the brain may call and how to run them. */
interface ToolRunner {
    val tools: List<Tool>
    /** The result for the model, as text. An exception is passed to the model as an error, not thrown on. */
    suspend fun run(name: String, args: JSONObject): String
}

/**
 * The Alexa button's brain: an OpenRouter model answers what Gemini heard, calling Heartline's tools (health data
 * lookups and watch actions) as it needs, and its final words are shown on the watch. It throws with a readable
 * message when it can't answer; there is no fallback. The key is never logged.
 */
class AssistantAi(private val post: suspend (url: String, headers: Map<String, String>, body: String) -> String = ::httpPost) {

    /** The final words, and the tools called on the way (in order). */
    class Reply(val text: String, val toolCalls: List<String>)

    suspend fun converse(transcript: String, context: AiContext, history: List<Pair<String, String>>, config: AssistantAiConfig, runner: ToolRunner): Reply {
        check(config.enabled) { "no OpenRouter key saved" }
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt(context)))
        history.takeLast(6).forEach { (asked, said) ->
            messages.put(JSONObject().put("role", "user").put("content", asked)).put(JSONObject().put("role", "assistant").put("content", said))
        }
        messages.put(JSONObject().put("role", "user").put("content", transcript))
        val tools = JSONArray(runner.tools.map { t ->
            JSONObject().put("type", "function").put("function", JSONObject().put("name", t.name).put("description", t.description).put("parameters", t.parameters))
        })
        val headers = mapOf("Authorization" to "Bearer ${config.apiKey}", "Content-Type" to "application/json",
            "HTTP-Referer" to "https://github.com/AbhiramJayasankar/heartline-zepp-alternative", "X-Title" to "Heartline")
        val called = ArrayList<String>()
        var mustReason = false
        for (round in 1..MAX_ROUNDS) {
            val body = JSONObject().put("model", config.model)
                .put("models", JSONArray((listOf(config.model) + AssistantAiConfig.FALLBACK_MODELS).distinct().take(3)))
                .put("messages", messages).put("tools", tools).put("temperature", 0.3)
            // Thinking is slow, so ask for none. Models that must reason refuse that ("Reasoning is mandatory for this
            // endpoint", 2026-11-06): from then on ask with a little reasoning and room for it.
            fun withReasoning() = JSONObject(body.toString()).put("max_tokens", REASONING_MAX_TOKENS)
                .put("reasoning", JSONObject().put("effort", "low").put("exclude", true)).toString()
            val reply = if (mustReason) post(URL, headers, withReasoning()) else try {
                post(URL, headers, JSONObject(body.toString()).put("max_tokens", 1500).put("reasoning", JSONObject().put("enabled", false)).toString())
            } catch (e: OpenRouterError) {
                if (!e.message.orEmpty().contains("reasoning", ignoreCase = true)) throw e
                mustReason = true
                post(URL, headers, withReasoning())
            }
            val choice = runCatching { JSONObject(reply).getJSONArray("choices").getJSONObject(0) }.getOrNull()
                ?: error("no answer in OpenRouter's reply: ${reply.take(120)}")
            val message = choice.getJSONObject("message")
            val model = JSONObject(reply).optString("model").ifEmpty { config.model }
            lastModel = model
            val calls = message.optJSONArray("tool_calls")
            if (calls != null && calls.length() > 0) {
                // The model's turn goes back as it came (some providers need their reasoning details to continue).
                messages.put(JSONObject().put("role", "assistant").put("content", message.opt("content") ?: "").put("tool_calls", calls).apply {
                    message.optJSONArray("reasoning_details")?.let { put("reasoning_details", it) }
                })
                for (i in 0 until calls.length()) {
                    val call = calls.getJSONObject(i)
                    val fn = call.optJSONObject("function") ?: JSONObject()
                    val name = fn.optString("name")
                    val args = runCatching { JSONObject(fn.optString("arguments").ifBlank { "{}" }) }.getOrDefault(JSONObject())
                    called += name
                    val result = try { runner.run(name, args) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "Error: ${e.message}" }
                    messages.put(JSONObject().put("role", "tool").put("tool_call_id", call.optString("id")).put("name", name).put("content", result))
                }
                continue
            }
            val content = message.optString("content").takeIf { !message.isNull("content") && it.isNotBlank() }
            if (content != null) return Reply(content.trim(), called)
            error(if (choice.optString("finish_reason") == "length") "$model ran out of tokens before it answered" else "$model gave no answer")
        }
        error("${lastModel ?: config.model} kept calling tools without answering")
    }

    /** The model that gave the last answer (OpenRouter may have used a fallback). */
    var lastModel: String? = null
        private set

    /** OpenRouter answered with an error: [message] is OpenRouter's own words, with the HTTP code. */
    class OpenRouterError(message: String) : Exception(message)

    companion object {
        const val URL = "https://openrouter.ai/api/v1/chat/completions"
        const val REASONING_MAX_TOKENS = 4000
        /** Tool calls and answers: each round is one OpenRouter request. */
        const val MAX_ROUNDS = 6
        /**
         * Thinking models and several tool rounds can be slow. The watch gives up after about 40 s on "Thinking", so a
         * slower answer is sent as a watch notification instead (see AlexaBridge); this is the limit for that.
         */
        const val TIMEOUT_MS = 90_000L

        fun systemPrompt(c: AiContext): String = """
            You are the voice assistant on the user's Amazfit smartwatch, answering what they say to its Alexa button. You
            run inside Heartline, the phone app that reads the watch's health data.
            Now: ${c.now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH))} (${c.now.zone.id}).
            Latest heart rate: ${c.heartRate ?: "unknown"}. Steps today: ${c.stepsToday?.toString() ?: "unknown"}${c.dataAsOf?.let { " (watch data synced up to $it)" } ?: ""}.
            Weather: ${c.weather ?: "unknown"}.
            Alarms, timers and reminders set by voice: ${if (c.alerts.isEmpty()) "none" else c.alerts.joinToString("; ") { "id ${it.id}: ${it.describe(c.now)}" }}.
            Workout capture in Heartline: ${c.workout ?: "not running"}.
            Today at a glance:
            {HEALTH}
            Use the tools. For questions about heart rate, stress, sleep, blood oxygen, temperature, activity, readiness,
            trends or workouts, call the get_ tools for the detail (several if useful) and answer from what they return,
            with a short analysis when they ask how something was. Use the action tools to set timers, alarms and
            reminders, cancel them by id, show the weather card or the list of things to ask, start or stop a workout
            capture, and sync the watch. Never say you did something without calling its tool. For an alarm or reminder
            time without am/pm, pick the next occurrence after now; for "in N minutes" use in_seconds.
            Your reply is shown on a small watch screen: short plain sentences, at most 300 characters, no emoji or
            markdown. After showing a card, a few words are enough. A question at the end makes the watch listen again, so
            ask one only when you need their answer (never offers like "Want me to check anything else?"); for a game or
            joke where they answer back, like a knock-knock joke, say only your next line.
        """.trimIndent().replace("{HEALTH}", c.health?.lines()?.filter { it.isNotBlank() }?.joinToString("\n") { "- $it" } ?: "- not available")

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
