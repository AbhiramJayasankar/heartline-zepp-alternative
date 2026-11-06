package dev.heartline.service

import java.time.ZonedDateTime
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * One finding: which part of health it is about, a short title, what the data shows, what to do, its tone, and which
 * parts of the data it rests on ([basis], as the model names them).
 */
data class Insight(val area: String, val title: String, val detail: String, val action: String?, val tone: String, val basis: List<String> = emptyList()) {
    fun json(): JSONObject = JSONObject().put("area", area).put("title", title).put("detail", detail).put("action", action ?: "").put("tone", tone)
        .put("based_on", JSONArray(basis))
}

/** One request to one model while writing insights: how long it took and why it failed, if it did. */
data class InsightAttempt(val provider: String, val model: String, val ms: Long, val error: String? = null) {
    fun json(): JSONObject = JSONObject().put("provider", provider).put("model", model).put("ms", ms).put("error", error ?: "")
    companion object {
        fun parse(o: JSONObject) = InsightAttempt(o.optString("provider"), o.optString("model"), o.optLong("ms"), o.optString("error").ifEmpty { null })
    }
}

/**
 * A set of insights written at [at] (epoch seconds) by [model], with a one-line [headline], and everything needed to see
 * how: the data sent ([digest]), the instructions ([system]), the model's raw reply, every attempt on the way, tokens.
 */
data class InsightReport(
    val at: Long, val model: String, val headline: String, val insights: List<Insight>, val tookMs: Long = 0,
    val digest: String = "", val system: String = "", val raw: String = "", val attempts: List<InsightAttempt> = emptyList(),
    val promptTokens: Int? = null, val outputTokens: Int? = null, val auto: Boolean = false,
) {
    fun json(): String = JSONObject().put("at", at).put("model", model).put("headline", headline).put("took_ms", tookMs)
        .put("insights", JSONArray(insights.map { it.json() })).put("digest", digest).put("system", system).put("raw", raw.take(40_000))
        .put("attempts", JSONArray(attempts.map { it.json() })).put("prompt_tokens", promptTokens ?: -1).put("output_tokens", outputTokens ?: -1)
        .put("auto", auto).toString()

    companion object {
        const val KEY = "ai_insights"
        val AREAS = listOf("recovery", "sleep", "heart", "activity", "stress", "body")
        val TONES = listOf("good", "watch", "neutral")

        fun parse(text: String?): InsightReport? = runCatching {
            val o = JSONObject(text ?: return null)
            val list = o.optJSONArray("insights") ?: JSONArray()
            val tries = o.optJSONArray("attempts") ?: JSONArray()
            InsightReport(o.optLong("at"), o.optString("model"), o.optString("headline"),
                (0 until list.length()).mapNotNull { list.optJSONObject(it)?.let(::insight) }, o.optLong("took_ms"),
                o.optString("digest"), o.optString("system"), o.optString("raw"),
                (0 until tries.length()).mapNotNull { tries.optJSONObject(it)?.let(InsightAttempt::parse) },
                o.optInt("prompt_tokens", -1).takeIf { it >= 0 }, o.optInt("output_tokens", -1).takeIf { it >= 0 }, o.optBoolean("auto"))
        }.getOrNull()

        /** The model's reply: a JSON object, possibly inside a ```json fence or with words around it. */
        fun fromModel(reply: String, at: Long, model: String, tookMs: Long): InsightReport {
            val start = reply.indexOf('{'); val end = reply.lastIndexOf('}')
            require(start >= 0 && end > start) { "the model didn't answer in the asked format" }
            val o = JSONObject(reply.substring(start, end + 1))
            val list = o.optJSONArray("insights") ?: JSONArray()
            val insights = (0 until list.length()).mapNotNull { list.optJSONObject(it)?.let(::insight) }
            require(insights.isNotEmpty()) { "the model gave no insights" }
            return InsightReport(at, model, o.optString("headline").trim(), insights, tookMs, raw = reply)
        }

        private fun insight(o: JSONObject): Insight? {
            val title = o.optString("title").trim(); val detail = o.optString("detail").trim()
            if (title.isEmpty() || detail.isEmpty()) return null
            val basis = o.optJSONArray("based_on")?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } } ?: emptyList()
            return Insight(o.optString("area").trim().lowercase().takeIf { it in AREAS } ?: "recovery", title, detail,
                o.optString("action").trim().takeIf { it.isNotEmpty() && it != "null" }, o.optString("tone").trim().lowercase().takeIf { it in TONES } ?: "neutral", basis)
        }
    }
}

/**
 * Which AI writes the insights. [provider] "gemini" (the owner's AI Studio key) or "openrouter" (the Alexa brain's
 * keys); each has a model and fallbacks tried in order; [useOther] lets the other provider step in when all of them
 * fail. [auto] writes new insights each day at [autoHour] (retrying through the day if the models are busy).
 */
data class InsightsSettings(
    val provider: String = GEMINI, val geminiModel: String = GeminiText.DEFAULT_MODEL, val openRouterModel: String = "",
    val geminiFallbacks: List<String> = emptyList(), val openRouterFallbacks: List<String> = emptyList(),
    val useOther: Boolean = true, val auto: Boolean = true, val autoHour: Int = 9,
) {
    fun json(): String = JSONObject().put("provider", provider).put("gemini_model", geminiModel).put("openrouter_model", openRouterModel)
        .put("gemini_fallbacks", JSONArray(geminiFallbacks)).put("openrouter_fallbacks", JSONArray(openRouterFallbacks))
        .put("use_other", useOther).put("auto", auto).put("auto_hour", autoHour).toString()

    companion object {
        const val KEY = "insights_settings"
        const val GEMINI = "gemini"; const val OPENROUTER = "openrouter"
        private fun list(o: JSONObject, k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } } ?: emptyList()
        fun parse(text: String?): InsightsSettings = runCatching {
            val o = JSONObject(text ?: "{}")
            InsightsSettings(o.optString("provider").takeIf { it == GEMINI || it == OPENROUTER } ?: GEMINI,
                o.optString("gemini_model").trim().ifEmpty { GeminiText.DEFAULT_MODEL }, o.optString("openrouter_model").trim(),
                list(o, "gemini_fallbacks"), list(o, "openrouter_fallbacks"), o.optBoolean("use_other", true), o.optBoolean("auto", true),
                o.optInt("auto_hour", 9).coerceIn(0, 23))
        }.getOrDefault(InsightsSettings())
    }
}

/** Every model failed; [attempts] says what each one answered. */
class InsightsFailed(val attempts: List<InsightAttempt>) :
    Exception(attempts.lastOrNull()?.let { "${it.model}: ${it.error}" } ?: "no model is set up (add a Gemini or OpenRouter key)")

/**
 * AI insights for the app's Insights page, apart from the Alexa assistant. The same health data the Alexa tools read
 * ([HealthTools]), plus heart checks, is gathered up front; then the chosen model writes findings as JSON. A busy
 * model (Gemini's free tier often answers 503 "high demand") is asked again after a pause; a rate limit is waited out
 * when Gemini says how long; anything else moves on to the next model in the chain, and finally the other provider.
 */
class HealthInsights(private val health: HealthTools, private val ai: AssistantAi, private val config: suspend () -> AssistantAiConfig,
                     private val settings: suspend () -> InsightsSettings = { InsightsSettings(InsightsSettings.OPENROUTER) },
                     private val geminiKey: suspend () -> String = { "" }, private val gemini: GeminiText = GeminiText(),
                     private val extra: suspend () -> String = { "" },
                     private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
                     private val pause: suspend (Long) -> Unit = { delay(it) }) {

    /** Everything the model is shown up front, one section per lookup. */
    suspend fun digest(): String = buildString {
        for ((name, args) in DIGEST) {
            val text = runCatching { health.run(name, JSONObject(args)) }.getOrNull() ?: continue
            val day = JSONObject(args).optString("date").takeIf { it.isNotEmpty() && it != "today" }
            append("## ").append(name.removePrefix("get_").replace('_', ' ')).append(day?.let { " ($it)" } ?: "")
                .append('\n').append(text.trim()).append("\n\n")
        }
        runCatching { extra() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { append(it.trim()).append("\n\n") }
    }.trim()

    private class Step(val provider: String, val model: String)

    suspend fun generate(auto: Boolean = false): InsightReport {
        val t = now()
        val data = digest()
        val s = settings()
        val ask = "My health data from the Heartline app:\n\n$data\n\nWrite my insights as the JSON object described."
        val system = AssistantAi.renderPrompt(PROMPT, AiContext(t))
        val cfg = config()
        val gKey = geminiKey()
        val geminiSteps = if (gKey.isBlank()) emptyList() else (listOf(s.geminiModel) + s.geminiFallbacks).distinct().map { Step(InsightsSettings.GEMINI, it) }
        val orSteps = if (!cfg.enabled) emptyList() else (listOf(s.openRouterModel.ifBlank { cfg.model }) + s.openRouterFallbacks).distinct().map { Step(InsightsSettings.OPENROUTER, it) }
        val chain = if (s.provider == InsightsSettings.GEMINI) geminiSteps + (if (s.useOther) orSteps else emptyList())
            else orSteps + (if (s.useOther) geminiSteps else emptyList())
        val attempts = ArrayList<InsightAttempt>()
        val t0 = System.currentTimeMillis()
        for (step in chain) {
            var tries = 0
            while (true) {
                tries++
                val a0 = System.currentTimeMillis()
                try {
                    val (text, model, tokens) = if (step.provider == InsightsSettings.GEMINI) {
                        // Gemini gets everything up front (no tool lookups).
                        val r = gemini.generate(gKey, step.model, system.replace(TOOLS_SENTENCE, ""), ask)
                        Triple(r.text, r.model, r.promptTokens to r.outputTokens)
                    } else {
                        val runner = object : ToolRunner {
                            override val tools = health.tools
                            override suspend fun run(name: String, args: JSONObject): String = health.run(name, args) ?: "Error: no tool called $name"
                        }
                        val trace = TurnTrace()
                        val r = ai.converse(ask, AiContext(t), emptyList(), cfg.copy(model = step.model), runner, OPENROUTER_SETTINGS, trace)
                        Triple(r.text, ai.lastModel ?: step.model, trace.rounds.sumOf { it.tokensIn ?: 0 } to trace.rounds.sumOf { it.tokensOut ?: 0 })
                    }
                    attempts += InsightAttempt(step.provider, model, System.currentTimeMillis() - a0)
                    return InsightReport.fromModel(text, t.toEpochSecond(), model, System.currentTimeMillis() - t0)
                        .copy(digest = data, system = if (step.provider == InsightsSettings.GEMINI) system.replace(TOOLS_SENTENCE, "") else system,
                            attempts = attempts, promptTokens = tokens.first, outputTokens = tokens.second, auto = auto)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    val msg = e.message ?: e.toString()
                    attempts += InsightAttempt(step.provider, step.model, System.currentTimeMillis() - a0, msg)
                    val wait = retryAfter(msg, tries) ?: break
                    pause(wait)
                }
            }
        }
        throw InsightsFailed(attempts)
    }

    companion object {
        /** How long to wait before asking the same model again, or null to move on to the next one. */
        fun retryAfter(error: String, tries: Int): Long? {
            val e = error.lowercase()
            // Gemini's rate limit says when to come back ("retry in 23s" / "retryDelay": "23s"); short waits are worth it.
            if ("429" in e || "resource_exhausted" in e || "rate limit" in e) {
                val secs = Regex("""retry(?:delay)?\D{0,12}(\d+(?:\.\d+)?)\s*s""").find(e)?.groupValues?.get(1)?.toDoubleOrNull()
                if ("per day" in e || "perday" in e || "daily" in e) return null
                return if (tries < 2 && secs != null && secs <= 45) (secs * 1000).toLong() + 1000 else null
            }
            // Busy or a server hiccup: twice more, after 6 s then 20 s.
            if (listOf(" 503", " 500", " 502", " 504", "high demand", "overloaded", "unavailable", "timed out", "timeout").any { it in e })
                return when (tries) { 1 -> 6_000L; 2 -> 20_000L; else -> null }
            return null
        }

        private const val TOOLS_SENTENCE = " You may call the tools for other days\nif a comparison needs it."
        private val OPENROUTER_SETTINGS get() = AlexaSettings(prompt = PROMPT_TEMPLATE, memoryTurns = 0, maxTokens = 3500, reasoningMaxTokens = 6000,
            maxRounds = 4, temperature = 0.4, answerTimeoutS = 150)

        private val DIGEST = listOf(
            "get_readiness" to "{}", "get_trends" to """{"days":14}""",
            "get_sleep" to """{"date":"today"}""", "get_sleep" to """{"date":"yesterday"}""",
            "get_heart_rate" to """{"date":"yesterday"}""", "get_heart_rate" to """{"date":"today"}""",
            "get_activity" to """{"date":"yesterday"}""", "get_activity" to """{"date":"today"}""",
            "get_stress" to """{"date":"yesterday"}""", "get_stress" to """{"date":"today"}""",
            "get_spo2_and_temperature" to """{"date":"yesterday"}""", "get_spo2_and_temperature" to """{"date":"today"}""",
            "get_workout_captures" to """{"limit":5}""",
        )

        private val PROMPT_TEMPLATE = """
            You are a careful health analyst inside Heartline, an app that reads an Amazfit smartwatch. Now: {NOW}.
            The user sends their data: readiness, two weeks of trends against their usual level, the last two nights'
            sleep, heart rate, activity, stress, SpO2 and temperature for yesterday and today, gym captures and heart
            checks. You may call the tools for other days
            if a comparison needs it. Find the 4 to 7 most useful insights: patterns across days, changes from their own
            baseline, links between sleep, strain, stress and resting heart rate, things going well, and things to watch.
            Use their actual numbers and dates. Never invent data; if something is missing or too thin to judge, say so
            briefly or leave it out. Today's totals are still adding up, so judge activity on whole days. No diagnosis:
            for anything that might need a doctor, say it's worth mentioning to one.
            Answer with only a JSON object, no other text:
            {"headline": "one sentence, the main takeaway",
             "insights": [{"area": "recovery|sleep|heart|activity|stress|body", "title": "under 8 words",
               "detail": "2 to 3 sentences with the numbers", "action": "one concrete thing to do, or empty",
               "tone": "good|watch|neutral",
               "based_on": ["the data sections and values this rests on, e.g. 'trends: resting heart rate, 14 days'"]}]}
            Order the insights by importance. Plain sentences, no markdown.
        """.trimIndent()

        /** The instructions as a template; {NOW} is filled when they are sent. */
        val PROMPT: String get() = PROMPT_TEMPLATE
    }
}
