package dev.heartline.service

import dev.heartline.service.ai.AiAttempt
import dev.heartline.service.ai.AiFailed
import dev.heartline.service.ai.AiSection
import dev.heartline.service.ai.AiStore
import dev.heartline.service.ai.ChatMsg
import dev.heartline.service.ai.ChatRequest
import dev.heartline.service.ai.badAnswer
import java.time.ZonedDateTime
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
 * fail. [auto] writes new insights each day at [autoHour] (retrying through the day if the models are busy); it is off
 * until the user turns it on, so nothing is sent to a model unless they ask.
 */
data class InsightsSettings(
    val provider: String = GEMINI, val geminiModel: String = GeminiText.DEFAULT_MODEL, val openRouterModel: String = "",
    val geminiFallbacks: List<String> = emptyList(), val openRouterFallbacks: List<String> = emptyList(),
    val useOther: Boolean = true, val auto: Boolean = false, val autoHour: Int = 9,
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
                list(o, "gemini_fallbacks"), list(o, "openrouter_fallbacks"), o.optBoolean("use_other", true), o.optBoolean("auto", false),
                o.optInt("auto_hour", 9).coerceIn(0, 23))
        }.getOrDefault(InsightsSettings())
    }
}

/** What a run sends: the instructions ([system]), the message ([ask]) and the data in it ([digest]). */
data class InsightsPreview(val system: String, val ask: String, val digest: String)

/** Every model failed; [attempts] says what each one answered. */
class InsightsFailed(val attempts: List<InsightAttempt>) :
    Exception(attempts.lastOrNull()?.let { "${it.model}: ${it.error}" } ?: "no model is set up (add a Gemini or OpenRouter key)")

/**
 * AI insights for the app's Insights page, apart from the Alexa assistant. The same health data the Alexa tools read
 * ([HealthTools]), plus heart checks, is gathered up front; then the AI insights section's models (AI models) write
 * findings as JSON. Retries, other keys and fallbacks follow that section's rules ([dev.heartline.service.ai.AiRouter]);
 * an answer that isn't in the asked shape counts as an unusable answer, so it is asked again or passed down the chain.
 */
class HealthInsights(private val health: HealthTools, private val store: AiStore,
                     private val extra: suspend () -> String = { "" },
                     private val now: () -> ZonedDateTime = { ZonedDateTime.now() }) {

    /**
     * Everything the model is shown up front, one section per lookup ([DIGEST]). A lookup that answers nothing is a
     * bug, not missing data (each topic says so itself when a day has no data), so it fails loudly: in Nov 2026 the
     * lookups were merged into get_info and the digest went on asking for the old names, silently sending almost nothing.
     */
    suspend fun digest(): String = buildString {
        for ((topic, args) in DIGEST) {
            val a = JSONObject(args).put("topic", topic)
            val text = try { health.run(HealthTools.TOOL, a) } catch (e: Exception) { dev.heartline.ble.rethrowIfCancelled(e); "Couldn't read this: ${e.message}" }
                ?: error("the insights digest asks for '$topic', which the health lookups don't answer")
            val day = a.optString("date").takeIf { it.isNotEmpty() && it != "today" }
            append("## ").append(TITLES[topic] ?: topic.replace('_', ' ')).append(day?.let { " ($it)" } ?: "")
                .append('\n').append(text.trim()).append("\n\n")
        }
        runCatching { extra() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { append(it.trim()).append("\n\n") }
    }.trim()

    /** Exactly what a run would send now: the instructions, the message and the data in it. Nothing is sent. */
    suspend fun preview(): InsightsPreview {
        val data = digest()
        return InsightsPreview(AssistantAi.renderPrompt(PROMPT, AiContext(now())), ask(data), data)
    }

    private fun ask(data: String) = "My health data from the Heartline app:\n\n$data\n\nWrite my insights as the JSON object described."

    /**
     * Writes insights. The daily run ([auto]) goes down the whole chain with its retries, as it has all day; one you
     * ask for goes once to [model] (the section's main model if null), with no retry and no fallback, so it answers or
     * fails quickly and you can pick another model.
     */
    suspend fun generate(auto: Boolean = false, model: dev.heartline.service.ai.ModelRef? = null): InsightReport {
        val t = now()
        val data = digest()
        val ask = ask(data)
        val system = AssistantAi.renderPrompt(PROMPT, AiContext(t))
        val t0 = System.currentTimeMillis()
        val out = try {
            store.router.run(AiSection.INSIGHTS, only = if (auto) null else model, once = !auto) { tg ->
                val r = store.api.chat(tg.provider, tg.key, tg.model, ChatRequest(system, listOf(ChatMsg.User(ask)), temperature = 0.4, maxTokens = 8192, json = true), tg.timeoutMs)
                val text = r.text ?: throw badAnswer(if (r.finish == "length") "${tg.model} ran out of tokens before it answered" else "${tg.model} gave no answer")
                val report = try { InsightReport.fromModel(text, t.toEpochSecond(), r.model, 0) } catch (e: Exception) { throw badAnswer(e.message ?: "unreadable answer") }
                report to r
            }
        } catch (e: AiFailed) { throw InsightsFailed(e.attempts.map(::attempt)) }
        val (report, reply) = out.value
        return report.copy(tookMs = System.currentTimeMillis() - t0, digest = data, system = system, attempts = out.attempts.map(::attempt),
            promptTokens = reply.tokensIn, outputTokens = reply.tokensOut, auto = auto)
    }

    companion object {
        fun attempt(a: AiAttempt) = InsightAttempt(a.provider, a.model, a.ms, a.error?.let { e -> a.kind?.let { "${it.label}: $e" } ?: e } ?: a.note)

        /** Each digest section's heading, as the model and the "What's sent" page read it. */
        internal val TITLES = mapOf("readiness" to "Readiness", "trends" to "Trends", "sleep" to "Sleep", "hrv" to "HRV",
            "heart_rate" to "Heart rate", "activity" to "Activity", "stress" to "Stress", "spo2_temperature" to "SpO2 and temperature",
            "workouts" to "Workout captures")

        /** The health lookups ([HealthTools] topics) sent with every run, in order; a test checks each one answers. */
        val DIGEST = listOf(
            "readiness" to "{}", "trends" to """{"days":14}""",
            "sleep" to """{"date":"today"}""", "sleep" to """{"date":"yesterday"}""",
            "hrv" to """{"date":"today"}""", "hrv" to """{"date":"yesterday"}""",
            "heart_rate" to """{"date":"yesterday"}""", "heart_rate" to """{"date":"today"}""",
            "activity" to """{"date":"yesterday"}""", "activity" to """{"date":"today"}""",
            "stress" to """{"date":"yesterday"}""", "stress" to """{"date":"today"}""",
            "spo2_temperature" to """{"date":"yesterday"}""", "spo2_temperature" to """{"date":"today"}""",
            "workouts" to """{"limit":5}""",
        )

        private val PROMPT_TEMPLATE = """
            You are a careful health analyst inside Heartline, an app that reads an Amazfit smartwatch. Now: {NOW}.
            The user sends their data: readiness, two weeks of trends against their usual level, the last two nights'
            sleep, heart rate variability (overnight samples, daytime samples and heart checks), heart rate, activity,
            stress, SpO2 and temperature for yesterday and today, workout captures and heart checks. Find the 4 to 7 most useful insights: patterns across days, changes from their own
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
