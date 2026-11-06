package dev.heartline.service

import java.time.ZonedDateTime
import org.json.JSONArray
import org.json.JSONObject

/** One finding: which part of health it is about, a short title, what the data shows, what to do, and its tone. */
data class Insight(val area: String, val title: String, val detail: String, val action: String?, val tone: String) {
    fun json(): JSONObject = JSONObject().put("area", area).put("title", title).put("detail", detail).put("action", action ?: "").put("tone", tone)
}

/** A set of insights written at [at] (epoch seconds) by [model], with a one-line [headline]. */
data class InsightReport(val at: Long, val model: String, val headline: String, val insights: List<Insight>, val tookMs: Long = 0) {
    fun json(): String = JSONObject().put("at", at).put("model", model).put("headline", headline).put("took_ms", tookMs)
        .put("insights", JSONArray(insights.map { it.json() })).toString()

    companion object {
        const val KEY = "ai_insights"
        val AREAS = listOf("recovery", "sleep", "heart", "activity", "stress", "body")
        val TONES = listOf("good", "watch", "neutral")

        fun parse(text: String?): InsightReport? = runCatching {
            val o = JSONObject(text ?: return null)
            val list = o.optJSONArray("insights") ?: JSONArray()
            InsightReport(o.optLong("at"), o.optString("model"), o.optString("headline"),
                (0 until list.length()).mapNotNull { list.optJSONObject(it)?.let(::insight) }, o.optLong("took_ms"))
        }.getOrNull()

        /** The model's reply: a JSON object, possibly inside a ```json fence or with words around it. */
        fun fromModel(reply: String, at: Long, model: String, tookMs: Long): InsightReport {
            val start = reply.indexOf('{'); val end = reply.lastIndexOf('}')
            require(start >= 0 && end > start) { "the model didn't answer in the asked format" }
            val o = JSONObject(reply.substring(start, end + 1))
            val list = o.optJSONArray("insights") ?: JSONArray()
            val insights = (0 until list.length()).mapNotNull { list.optJSONObject(it)?.let(::insight) }
            require(insights.isNotEmpty()) { "the model gave no insights" }
            return InsightReport(at, model, o.optString("headline").trim(), insights, tookMs)
        }

        private fun insight(o: JSONObject): Insight? {
            val title = o.optString("title").trim(); val detail = o.optString("detail").trim()
            if (title.isEmpty() || detail.isEmpty()) return null
            return Insight(o.optString("area").trim().lowercase().takeIf { it in AREAS } ?: "recovery", title, detail,
                o.optString("action").trim().takeIf { it.isNotEmpty() && it != "null" }, o.optString("tone").trim().lowercase().takeIf { it in TONES } ?: "neutral")
        }
    }
}

/** Which AI writes the insights: [provider] "gemini" (the owner's AI Studio key) or "openrouter" (the Alexa brain's keys). */
data class InsightsSettings(val provider: String = GEMINI, val geminiModel: String = GeminiText.DEFAULT_MODEL, val openRouterModel: String = "") {
    fun json(): String = JSONObject().put("provider", provider).put("gemini_model", geminiModel).put("openrouter_model", openRouterModel).toString()
    companion object {
        const val KEY = "insights_settings"
        const val GEMINI = "gemini"; const val OPENROUTER = "openrouter"
        fun parse(text: String?): InsightsSettings = runCatching {
            val o = JSONObject(text ?: "{}")
            InsightsSettings(o.optString("provider").takeIf { it == GEMINI || it == OPENROUTER } ?: GEMINI,
                o.optString("gemini_model").trim().ifEmpty { GeminiText.DEFAULT_MODEL }, o.optString("openrouter_model").trim())
        }.getOrDefault(InsightsSettings())
    }
}

/**
 * AI insights for the app's Insights page, apart from the Alexa assistant: the same health data the Alexa tools read
 * ([HealthTools]) is gathered up front, the owner's OpenRouter model (and keys) reads it, may look up more days with
 * the same tools, and writes findings as JSON.
 */
class HealthInsights(private val health: HealthTools, private val ai: AssistantAi, private val config: suspend () -> AssistantAiConfig,
                     private val settings: suspend () -> InsightsSettings = { InsightsSettings(InsightsSettings.OPENROUTER) },
                     private val geminiKey: suspend () -> String = { "" }, private val gemini: GeminiText = GeminiText(),
                     private val now: () -> ZonedDateTime = { ZonedDateTime.now() }) {

    /** Everything the model is shown up front, one section per lookup. */
    suspend fun digest(): String = buildString {
        for ((name, args) in DIGEST) {
            val text = runCatching { health.run(name, JSONObject(args)) }.getOrNull() ?: continue
            append("## ").append(name.removePrefix("get_").replace('_', ' ')).append(if (args.contains("yesterday")) " (yesterday)" else "")
                .append('\n').append(text.trim()).append("\n\n")
        }
    }.trim()

    suspend fun generate(): InsightReport {
        val t = now()
        val data = digest()
        val s = settings()
        val ask = "My health data from the Heartline app:\n\n$data\n\nWrite my insights as the JSON object described."
        var geminiError: String? = null
        if (s.provider == InsightsSettings.GEMINI) {
            val t0 = System.currentTimeMillis()
            // Gemini gets everything up front (no tool lookups).
            val system = AssistantAi.renderPrompt(PROMPT, AiContext(t)).replace(" You may call the tools for other days\nif a comparison needs it.", "")
            // A busy model (503 "high demand", 2026-11-06) is asked once more; if it still fails, OpenRouter writes them.
            for (attempt in 1..2) {
                try {
                    val reply = gemini.generate(geminiKey(), s.geminiModel, system, ask)
                    return InsightReport.fromModel(reply.text, t.toEpochSecond(), reply.model, System.currentTimeMillis() - t0)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    geminiError = e.message ?: e.toString()
                    if (attempt == 1 && BUSY.any { geminiError!!.contains(it) }) kotlinx.coroutines.delay(4_000) else break
                }
            }
            if (!config().enabled) error(geminiError!!)
        }
        val settings = AlexaSettings(prompt = PROMPT, memoryTurns = 0, maxTokens = 3000, reasoningMaxTokens = 6000, maxRounds = 4,
            temperature = 0.4, answerTimeoutS = 150)
        val runner = object : ToolRunner {
            override val tools = health.tools
            override suspend fun run(name: String, args: JSONObject): String = health.run(name, args) ?: "Error: no tool called $name"
        }
        val t0 = System.currentTimeMillis()
        val cfg = config().let { c -> if (s.openRouterModel.isNotBlank()) c.copy(model = s.openRouterModel) else c }
        val reply = try { ai.converse(ask, AiContext(t), emptyList(), cfg, runner, settings) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            throw IllegalStateException(listOfNotNull(geminiError?.let { "Gemini: $it" }, "OpenRouter: ${e.message}").joinToString("\n"))
        }
        val note = geminiError?.let { " (Gemini failed: ${it.take(80)})" } ?: ""
        return InsightReport.fromModel(reply.text, t.toEpochSecond(), (ai.lastModel ?: cfg.model) + note, System.currentTimeMillis() - t0)
    }

    companion object {
        private val BUSY = listOf(" 503", " 500", " 429", "high demand", "overloaded", "UNAVAILABLE")
        private val DIGEST = listOf(
            "get_readiness" to "{}", "get_trends" to """{"days":14}""", "get_sleep" to """{"date":"today"}""",
            "get_heart_rate" to """{"date":"yesterday"}""", "get_heart_rate" to """{"date":"today"}""",
            "get_activity" to """{"date":"yesterday"}""", "get_activity" to """{"date":"today"}""",
            "get_stress" to """{"date":"yesterday"}""", "get_spo2_and_temperature" to """{"date":"today"}""",
            "get_workout_captures" to """{"limit":5}""",
        )

        val PROMPT = """
            You are a careful health analyst inside Heartline, an app that reads an Amazfit smartwatch. Now: {NOW}.
            The user sends their data: readiness, two weeks of trends against their usual level, last night's sleep,
            heart rate, activity, stress, SpO2 and temperature, and gym captures. You may call the tools for other days
            if a comparison needs it. Find the 4 to 7 most useful insights: patterns across days, changes from their own
            baseline, links between sleep, strain, stress and resting heart rate, things going well, and things to watch.
            Use their actual numbers and dates. Never invent data; if something is missing or too thin to judge, say so
            briefly or leave it out. No diagnosis: for anything that might need a doctor, say it's worth mentioning to one.
            Answer with only a JSON object, no other text:
            {"headline": "one sentence, the main takeaway",
             "insights": [{"area": "recovery|sleep|heart|activity|stress|body", "title": "under 8 words",
               "detail": "2 to 3 sentences with the numbers", "action": "one concrete thing to do, or empty",
               "tone": "good|watch|neutral"}]}
            Order the insights by importance. Plain sentences, no markdown.
        """.trimIndent()
    }
}
