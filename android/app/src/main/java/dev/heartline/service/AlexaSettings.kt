package dev.heartline.service

import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything about the Alexa button the owner can change in the app (stored in meta as JSON). Defaults are what
 * Heartline was tuned with; every number is clamped to a range that works with the watch and the services.
 */
data class AlexaSettings(
    /** The brain's instructions with {PLACEHOLDERS}; blank means [AssistantAi.DEFAULT_PROMPT]. */
    val prompt: String = "",
    val disabledTools: Set<String> = emptySet(),
    /** Tool descriptions the owner rewrote, by tool name. */
    val toolDescriptions: Map<String, String> = emptyMap(),
    /** Models OpenRouter tries, in order, when the chosen one fails (it takes two). */
    val fallbackModels: List<String> = AssistantAiConfig.FALLBACK_MODELS,
    /** auto (none, a little when the model insists), off, low, medium or high. */
    val reasoning: String = "auto",
    val temperature: Double = 0.3,
    val maxTokens: Int = 16_000,
    val reasoningMaxTokens: Int = 32_000,
    /** Requests per question: each tool round is one. Short questions take one or two; research can take many. */
    val maxRounds: Int = 25,
    /** The whole answer, however many requests; after [watchWaitS] it comes as notifications, so this can be long. */
    val answerTimeoutS: Int = 900,
    /** Earlier questions and answers sent along, and the pause after which a new conversation starts. */
    val memoryTurns: Int = 6,
    val memoryMinutes: Int = 2,
    val maxAnswerChars: Int = 100_000,
    /** Bytes of the answer on the reply screen; the rest goes out as notifications. */
    val replyBytes: Int = WatchAssistant.REPLY_TEXT,
    /** How long the watch is kept on "Thinking" before it's told the answer will come as a notification. */
    /** Seconds after the stop. The watch gives up between 28 s (an answer then showed) and 34 s (it said "I didn't hear you"). */
    val watchWaitS: Int = 25,
    /** Notifications per answer, about 150 words each; the GTR 2e keeps at least 12 (2026-11-09). */
    val maxNotifications: Int = 100,
    /** Open the mic again after an answer that ends with a question. */
    val listenAgain: Boolean = true,
    val geminiModel: String = GeminiSpeech.MODEL,
    val languages: List<String> = GeminiSpeech.LANGUAGES,
    val vocabulary: List<String> = GeminiSpeech.VOCABULARY,
    val webResults: Int = WebSearch.RESULTS,
    /** Questions kept: their recordings and their entries in the conversation log. */
    val keepTurns: Int = 30,
) {
    val promptTemplate get() = prompt.ifBlank { AssistantAi.DEFAULT_PROMPT }
    fun enabled(tool: String) = tool !in disabledTools
    fun describe(t: Tool) = toolDescriptions[t.name]?.takeIf { it.isNotBlank() }?.let { t.copy(description = it) } ?: t

    /** The same settings with every value in its range. */
    fun clamped() = copy(
        reasoning = reasoning.takeIf { it in REASONING } ?: "auto",
        fallbackModels = fallbackModels.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(5),
        temperature = temperature.coerceIn(0.0, 2.0),
        maxTokens = maxTokens.coerceIn(200, 64_000), reasoningMaxTokens = reasoningMaxTokens.coerceIn(500, 64_000),
        maxRounds = maxRounds.coerceIn(1, 40), answerTimeoutS = answerTimeoutS.coerceIn(15, 1800),
        memoryTurns = memoryTurns.coerceIn(0, 20), memoryMinutes = memoryMinutes.coerceIn(0, 120),
        maxAnswerChars = maxAnswerChars.coerceIn(500, 200_000),
        replyBytes = replyBytes.coerceIn(100, WatchAssistant.REPLY_TEXT), watchWaitS = watchWaitS.coerceIn(5, 28),
        maxNotifications = maxNotifications.coerceIn(0, 150),
        geminiModel = geminiModel.trim().ifEmpty { GeminiSpeech.MODEL },
        languages = languages.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { GeminiSpeech.LANGUAGES },
        vocabulary = vocabulary.map { it.trim() }.filter { it.isNotEmpty() },
        webResults = webResults.coerceIn(1, 10), keepTurns = keepTurns.coerceIn(1, 200),
    )

    fun json(): String = JSONObject()
        .put("prompt", prompt).put("disabled_tools", JSONArray(disabledTools.sorted()))
        .put("tool_descriptions", JSONObject(toolDescriptions as Map<*, *>)).put("fallback_models", JSONArray(fallbackModels))
        .put("reasoning", reasoning).put("temperature", temperature).put("max_tokens", maxTokens).put("reasoning_max_tokens", reasoningMaxTokens)
        .put("max_rounds", maxRounds).put("answer_timeout_s", answerTimeoutS).put("memory_turns", memoryTurns).put("memory_minutes", memoryMinutes)
        .put("max_answer_chars", maxAnswerChars).put("reply_bytes", replyBytes).put("watch_wait_s", watchWaitS)
        .put("max_notifications", maxNotifications).put("listen_again", listenAgain).put("gemini_model", geminiModel)
        .put("languages", JSONArray(languages)).put("vocabulary", JSONArray(vocabulary))
        .put("web_results", webResults).put("keep_turns", keepTurns).put("limits", LIMITS).toString()

    companion object {
        const val KEY = "alexa_settings"
        val REASONING = listOf("auto", "off", "low", "medium", "high")
        /** Version of the defaults below; saved settings from before it that still hold an old default get the new one. */
        const val LIMITS = 3
        /** The defaults each version replaced: version 1's before 2, version 2's before 3 (the watch wait, 2026-11-09). */
        private val OLD_DEFAULTS = mapOf(
            1 to mapOf("max_tokens" to 4000, "reasoning_max_tokens" to 6000, "max_rounds" to 6, "answer_timeout_s" to 90,
                "max_answer_chars" to 20_000, "max_notifications" to 25, "watch_wait_s" to 34),
            2 to mapOf("watch_wait_s" to 34))

        fun parse(text: String?): AlexaSettings = runCatching {
            val o = JSONObject(text ?: "{}")
            (o.optInt("limits", 1) until LIMITS).forEach { v -> OLD_DEFAULTS[v]?.forEach { (k, old) -> if (o.has(k) && o.optInt(k) == old) o.remove(k) } }
            val d = AlexaSettings()
            fun strings(name: String, default: List<String>) = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: default
            AlexaSettings(
                prompt = AssistantAi.upgradePrompt(o.optString("prompt", d.prompt)),
                disabledTools = strings("disabled_tools", emptyList()).toSet(),
                toolDescriptions = o.optJSONObject("tool_descriptions")?.let { m -> m.keys().asSequence().associateWith { m.optString(it) } }.orEmpty(),
                fallbackModels = strings("fallback_models", d.fallbackModels),
                reasoning = o.optString("reasoning", d.reasoning),
                temperature = o.optDouble("temperature", d.temperature),
                maxTokens = o.optInt("max_tokens", d.maxTokens), reasoningMaxTokens = o.optInt("reasoning_max_tokens", d.reasoningMaxTokens),
                maxRounds = o.optInt("max_rounds", d.maxRounds), answerTimeoutS = o.optInt("answer_timeout_s", d.answerTimeoutS),
                memoryTurns = o.optInt("memory_turns", d.memoryTurns), memoryMinutes = o.optInt("memory_minutes", d.memoryMinutes),
                maxAnswerChars = o.optInt("max_answer_chars", d.maxAnswerChars), replyBytes = o.optInt("reply_bytes", d.replyBytes),
                watchWaitS = o.optInt("watch_wait_s", d.watchWaitS), maxNotifications = o.optInt("max_notifications", d.maxNotifications),
                listenAgain = o.optBoolean("listen_again", d.listenAgain), geminiModel = o.optString("gemini_model", d.geminiModel),
                languages = strings("languages", d.languages), vocabulary = strings("vocabulary", d.vocabulary),
                webResults = o.optInt("web_results", d.webResults),
                keepTurns = o.optInt("keep_turns", d.keepTurns),
            ).clamped()
        }.getOrDefault(AlexaSettings())
    }
}

/** One tool call in a turn: what the model asked for and what Heartline gave back. */
data class ToolCallLog(val name: String, val args: String, val result: String, val ms: Long)

/** One OpenRouter request: who answered, how, and the tools it called (or the words it said). */
data class RoundLog(val model: String, val key: Int, val reasoning: String, val ms: Long, val tokensIn: Int?, val tokensOut: Int?,
                    val calls: List<ToolCallLog>, val text: String?)

/** Filled in as a question is answered, so a failure still shows how far it got. */
class TurnTrace {
    var prompt: String = ""
    var memory: Int = 0
    val rounds = ArrayList<RoundLog>()
}

/**
 * One question in the conversation log: what was heard, everything the brain did, and what reached the watch.
 * [at] is epoch milliseconds; [recording] names the turn's audio under files/alexa/ (without .wav).
 */
data class TurnRecord(
    val at: Long, val source: String, val heard: String?, val audioS: Double?, val speechMs: Long?,
    val prompt: String, val memory: Int, val rounds: List<RoundLog>, val reply: String?, val delivery: String,
    val error: String?, val totalMs: Long, val recording: String?,
) {
    fun json(): JSONObject = JSONObject().put("at", at).put("source", source).put("heard", heard).put("audio_s", audioS).put("speech_ms", speechMs)
        .put("prompt", prompt.take(MAX_PROMPT)).put("memory", memory).put("reply", reply?.take(MAX_REPLY)).put("delivery", delivery)
        .put("error", error).put("total_ms", totalMs).put("recording", recording)
        .put("rounds", JSONArray(rounds.map { r ->
            JSONObject().put("model", r.model).put("key", r.key).put("reasoning", r.reasoning).put("ms", r.ms)
                .put("tokens_in", r.tokensIn).put("tokens_out", r.tokensOut).put("text", r.text?.take(MAX_REPLY))
                .put("calls", JSONArray(r.calls.map { c ->
                    JSONObject().put("name", c.name).put("args", c.args.take(MAX_ARGS)).put("result", c.result.take(MAX_RESULT)).put("ms", c.ms)
                }))
        }))

    companion object {
        const val MAX_PROMPT = 12_000
        const val MAX_REPLY = 20_000
        const val MAX_ARGS = 2_000
        const val MAX_RESULT = 6_000

        private fun JSONObject.str(name: String) = if (isNull(name)) null else optString(name)
        private fun JSONObject.long(name: String) = if (isNull(name) || !has(name)) null else optLong(name)
        private fun JSONObject.int(name: String) = if (isNull(name) || !has(name)) null else optInt(name)

        fun parse(o: JSONObject) = TurnRecord(
            o.optLong("at"), o.optString("source"), o.str("heard"), if (o.isNull("audio_s") || !o.has("audio_s")) null else o.optDouble("audio_s"),
            o.long("speech_ms"), o.optString("prompt"), o.optInt("memory"),
            (o.optJSONArray("rounds") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { r ->
                RoundLog(r.optString("model"), r.optInt("key"), r.optString("reasoning"), r.optLong("ms"), r.int("tokens_in"), r.int("tokens_out"),
                    (r.optJSONArray("calls") ?: JSONArray()).let { c -> (0 until c.length()).map { c.getJSONObject(it) }.map { x ->
                        ToolCallLog(x.optString("name"), x.optString("args"), x.optString("result"), x.optLong("ms"))
                    } }, r.str("text"))
            } },
            o.str("reply"), o.optString("delivery"), o.str("error"), o.optLong("total_ms"), o.str("recording"),
        )
    }
}

/** The conversation log in meta: newest first, [AlexaSettings.keepTurns] long. */
object AlexaLog {
    const val KEY = "alexa_turns"

    fun parse(json: String?): List<TurnRecord> = runCatching {
        val a = JSONArray(json ?: "[]")
        (0 until a.length()).mapNotNull { runCatching { TurnRecord.parse(a.getJSONObject(it)) }.getOrNull() }
    }.getOrDefault(emptyList())

    /** Characters the whole log may take: the oldest turns go first beyond it, so the log stays a cheap read. */
    const val MAX_CHARS = 1_000_000

    fun added(json: String?, record: TurnRecord, keep: Int): String {
        val turns = (listOf(record) + parse(json)).take(keep).map { it.json().toString() }
        var size = 2
        val kept = turns.takeWhile { t -> size += t.length + 1; size <= MAX_CHARS }.ifEmpty { turns.take(1) }
        return kept.joinToString(",", "[", "]")
    }
}
