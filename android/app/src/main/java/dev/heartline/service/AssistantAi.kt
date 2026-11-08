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

/**
 * The owner's OpenRouter keys (typed into the app, stored in meta) and model; no key = no answers. [apiKey2] and
 * [apiKey3] are keys from other accounts, tried in turn when OpenRouter refuses one (such as its daily free-model limit).
 */
data class AssistantAiConfig(val apiKey: String = "", val model: String = DEFAULT_MODEL, val apiKey2: String = "", val apiKey3: String = "") {
    val keys get() = listOf(apiKey, apiKey2, apiKey3).filter { it.isNotBlank() }.distinct()
    val enabled get() = keys.isNotEmpty()
    fun json(): String = JSONObject().put("key", apiKey).put("model", model).put("key2", apiKey2).put("key3", apiKey3).toString()
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
            AssistantAiConfig(o.optString("key").trim(), o.optString("model").trim().takeUnless { it.isEmpty() || it in OLD_DEFAULTS } ?: DEFAULT_MODEL,
                o.optString("key2").trim(), o.optString("key3").trim())
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

    /**
     * Answers [transcript] with [settings] (the owner's prompt, models, reasoning and limits), filling [trace] as it
     * goes so the app can show every request and tool call, even when it fails.
     */
    suspend fun converse(transcript: String, context: AiContext, history: List<Pair<String, String>>, config: AssistantAiConfig, runner: ToolRunner,
                         settings: AlexaSettings = AlexaSettings(), trace: TurnTrace = TurnTrace()): Reply {
        check(config.enabled) { "no OpenRouter key saved" }
        val prompt = renderPrompt(settings.promptTemplate, context)
        trace.prompt = prompt
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", prompt))
        val memory = if (settings.memoryTurns == 0) emptyList() else history.takeLast(settings.memoryTurns)
        trace.memory = memory.size
        memory.forEach { (asked, said) ->
            messages.put(JSONObject().put("role", "user").put("content", asked)).put(JSONObject().put("role", "assistant").put("content", said))
        }
        messages.put(JSONObject().put("role", "user").put("content", transcript))
        val tools = JSONArray(runner.tools.map { t ->
            JSONObject().put("type", "function").put("function", JSONObject().put("name", t.name).put("description", t.description).put("parameters", t.parameters))
        })
        val called = ArrayList<String>()
        // auto: thinking is slow, so ask for none; models that must reason refuse that ("Reasoning is mandatory for
        // this endpoint", 2026-11-06), and from then on get a little reasoning and room for it.
        var effort: String? = settings.reasoning.takeIf { it in listOf("low", "medium", "high") }
        for (round in 1..settings.maxRounds) {
            val body = JSONObject().put("model", config.model)
                .put("models", JSONArray((listOf(config.model) + settings.fallbackModels).distinct().take(3)))
                .put("messages", messages).put("temperature", settings.temperature)
            if (tools.length() > 0) body.put("tools", tools)
            fun request(e: String?) = JSONObject(body.toString()).apply {
                if (e == null) put("max_tokens", settings.maxTokens).put("reasoning", JSONObject().put("enabled", false))
                else put("max_tokens", settings.reasoningMaxTokens).put("reasoning", JSONObject().put("effort", e).put("exclude", true))
            }.toString()
            val t0 = System.currentTimeMillis()
            val reply = try { send(config, request(effort)) } catch (e: OpenRouterError) {
                if (!e.reasoning || effort != null) throw e
                if (settings.reasoning == "off") throw OpenRouterError("${e.message} (Reasoning is set to off; set it to auto or low for this model)")
                effort = "low"
                send(config, request(effort))
            }
            val ms = System.currentTimeMillis() - t0
            val o = JSONObject(reply)
            val choice = runCatching { o.getJSONArray("choices").getJSONObject(0) }.getOrNull()
                ?: error("no answer in OpenRouter's reply: ${reply.take(120)}")
            val message = choice.getJSONObject("message")
            val model = o.optString("model").ifEmpty { config.model }
            lastModel = model
            val usage = o.optJSONObject("usage")
            fun round(calls: List<ToolCallLog>, text: String?) = RoundLog(model, lastKey, effort ?: "off", ms,
                usage?.optInt("prompt_tokens", -1)?.takeIf { it >= 0 }, usage?.optInt("completion_tokens", -1)?.takeIf { it >= 0 }, calls, text)
            val calls = message.optJSONArray("tool_calls")
            if (calls != null && calls.length() > 0) {
                // The model's turn goes back as it came (some providers need their reasoning details to continue).
                messages.put(JSONObject().put("role", "assistant").put("content", message.opt("content") ?: "").put("tool_calls", calls).apply {
                    message.optJSONArray("reasoning_details")?.let { put("reasoning_details", it) }
                })
                val logs = ArrayList<ToolCallLog>()
                for (i in 0 until calls.length()) {
                    val call = calls.getJSONObject(i)
                    val fn = call.optJSONObject("function") ?: JSONObject()
                    val name = fn.optString("name")
                    val args = runCatching { JSONObject(fn.optString("arguments").ifBlank { "{}" }) }.getOrDefault(JSONObject())
                    called += name
                    val c0 = System.currentTimeMillis()
                    val result = try { runner.run(name, args) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "Error: ${e.message}" }
                    logs += ToolCallLog(name, args.toString(), result, System.currentTimeMillis() - c0)
                    messages.put(JSONObject().put("role", "tool").put("tool_call_id", call.optString("id")).put("name", name).put("content", result))
                }
                trace.rounds += round(logs, message.optString("content").takeIf { !message.isNull("content") && it.isNotBlank() })
                continue
            }
            val content = message.optString("content").takeIf { !message.isNull("content") && it.isNotBlank() }
            trace.rounds += round(emptyList(), content)
            if (content != null) return Reply(content.trim(), called)
            error(if (choice.optString("finish_reason") == "length") "$model ran out of tokens before it answered" else "$model gave no answer")
        }
        error("${lastModel ?: config.model} kept calling tools without answering (${settings.maxRounds} rounds)")
    }

    /** The model that gave the last answer (OpenRouter may have used a fallback). */
    var lastModel: String? = null
        private set

    /** Which key (1 or 2) answered last; it is tried first next time, so a key at its daily limit isn't retried every turn. */
    var lastKey = 1
        private set

    /** [body] to OpenRouter with the key that worked last, then the other one if OpenRouter refuses it. */
    private suspend fun send(config: AssistantAiConfig, body: String): String {
        val keys = config.keys
        val order = keys.indices.sortedBy { if (it == lastKey - 1) 0 else 1 }
        val errors = ArrayList<String>()
        for (i in order) {
            try { return post(URL, headers(keys[i]), body).also { lastKey = i + 1 } }
            catch (e: OpenRouterError) {
                // Asking a model that must reason not to is about the request, not the key.
                if (e.reasoning) throw e
                errors += if (keys.size > 1) "key ${i + 1}: ${e.message}" else e.message.orEmpty()
            }
        }
        throw OpenRouterError(errors.joinToString("; "))
    }

    private fun headers(key: String) = mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json",
        "HTTP-Referer" to "https://github.com/AbhiramJayasankar/heartline-zepp-alternative", "X-Title" to "Heartline")

    /** OpenRouter answered with an error: [message] is OpenRouter's own words, with the HTTP code. */
    class OpenRouterError(message: String) : Exception(message) {
        val reasoning get() = message.orEmpty().contains("Reasoning is mandatory", ignoreCase = true)
    }

    companion object {
        const val URL = "https://openrouter.ai/api/v1/chat/completions"
        /**
         * The longest a single OpenRouter request may take; the whole answer has its own limit (Alexa settings). The
         * watch gives up after about 40 s on "Thinking", so a slower answer is sent as a watch notification (AlexaBridge).
         */
        const val TIMEOUT_MS = 180_000L

        /** What each placeholder in the instructions becomes. */
        val PLACEHOLDERS = linkedMapOf(
            "{NOW}" to "the date, time and time zone",
            "{HEART_RATE}" to "the latest heart rate and when it was measured",
            "{STEPS}" to "steps today",
            "{SYNCED_UP_TO}" to "the time of the newest data synced from the watch",
            "{ALERTS}" to "alarms, timers and reminders set by voice, with their ids",
            "{WORKOUT}" to "the workout capture running in Heartline",
            "{HEALTH}" to "a short glance at today: readiness, last night, heart rate, stress, activity (one line each)",
        )

        val DEFAULT_PROMPT = """
            You are the voice assistant on the user's Amazfit smartwatch, answering what they say to its Alexa button. You
            run inside Heartline, the phone app that reads the watch's health data.
            Now: {NOW}.
            Latest heart rate: {HEART_RATE}. Steps today: {STEPS} (watch data synced up to {SYNCED_UP_TO}).
            Timers, reminders and older voice alarms set by voice: {ALERTS}.
            Workout capture in Heartline: {WORKOUT}.
            Today at a glance:
            {HEALTH}
            Use the tools. For questions about readiness, sleep, heart rate, HRV, stress, activity, blood oxygen,
            temperature, trends, workouts, the AI insights or their quick notes, call get_info with the topic (several
            times if useful) and answer from what it returns, with a short analysis when they ask how something was. The
            other tools each take an action: alarms (list, set, delete the watch's alarms; list first when they mean an
            existing one), timers_and_reminders, show_on_watch (a table, the weather card, things to ask, or long text as
            notifications), recording (a heart check or a workout capture), phone (media and the ringer) and app (sync
            the watch, add a note). When they ask what the weather is like, show the weather card; get_info weather is for
            a specific question such as whether it will rain tomorrow. For news, scores, prices, recent events or any fact
            you aren't sure of, call web_search and answer from what it finds (don't read out web addresses). Never say you
            did something without calling its tool. For an alarm or reminder time without am/pm, pick the next occurrence
            after now; for "in N minutes" use in_seconds.
            Your reply is shown on the watch as plain text: markdown isn't drawn (no bold, headings or tables), but new lines
            and blank lines are, and so are every language's script and symbols such as ° ± ½ × ÷ ≤ ² € £ © → ↑ ▲ ■ ● ○ ①
            ✓ ★ ♥ ♪ (not ✔ ✗ ❤ • or ₹: write Rs). Whenever the answer is several items that each have a value or detail (a
            ranking or top 10, heights, prices, scores, a schedule, times, a forecast by day, steps with timings), call
            show_on_watch with a table, even if they say "list" and not "table"; only items with nothing beside them
            (tips, a shopping list) go in the text, one per line starting with a fitting emoji or symbol, "- " or a number.
            The watch draws only these emoji: ${WatchAssistant.EMOJI_SHOWN}, and these symbols: ${WatchAssistant.SYMBOLS_SHOWN}
            ° → ↑ ↓ ▲ ▼ ■ ● ○ ① ② ③; anything else vanishes. Use them generously, they make the small screen lively and
            easy to scan: open most replies with one emoji that fits, start listed items with a matching one (✓ done,
            ▲ ▼ up and down, ★ a highlight, ♥ heart, ☀ ☁ weather, ♪ music, ① ② ③ steps), and add one where it fits the
            mood. Never use any other emoji. Keep it short (at most 300 characters)
            unless they ask for something long, like a story or a detailed explanation: then write it in full, never refuse
            because of the screen; a long reply continues as watch notifications, or send it with show_on_watch notifications
            when they ask for it on the watch. After showing a card, a few words are enough.
            A question at the end makes the watch listen again, so ask one only when you need their answer (never offers like
            "Want me to check anything else?"); for a game or joke where they answer back, like a knock-knock joke, say only
            your next line.
        """.trimIndent()

        /** [template] with its placeholders filled from [c]. */
        fun renderPrompt(template: String, c: AiContext): String = template
            .replace("{NOW}", "${c.now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH))} (${c.now.zone.id})")
            .replace("{HEART_RATE}", c.heartRate ?: "unknown")
            .replace("{STEPS}", c.stepsToday?.toString() ?: "unknown")
            .replace("{SYNCED_UP_TO}", c.dataAsOf ?: "unknown")
            .replace("{ALERTS}", if (c.alerts.isEmpty()) "none" else c.alerts.joinToString("; ") { "id ${it.id}: ${it.describe(c.now)}" })
            .replace("{WORKOUT}", c.workout ?: "not running")
            .replace("{HEALTH}", c.health?.lines()?.filter { it.isNotBlank() }?.joinToString("\n") { "- $it" } ?: "- not available")

        fun systemPrompt(c: AiContext): String = renderPrompt(DEFAULT_PROMPT, c)

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
