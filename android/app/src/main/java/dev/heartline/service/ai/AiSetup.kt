package dev.heartline.service.ai

import dev.heartline.service.GeminiSpeech
import org.json.JSONArray
import org.json.JSONObject

/** How a provider is spoken to: an OpenAI-compatible chat completions API, or Google's Gemini API. */
enum class ProviderKind(val label: String) { OPENAI("OpenAI-compatible"), GEMINI("Gemini API") }

/**
 * One place models come from, with as many keys as the owner adds. Keys are tried in order (starting with the one that
 * worked last); a key that is out of quota or refused is skipped for a while and the next one is used.
 */
data class AiProvider(val id: String, val kind: ProviderKind, val name: String, val baseUrl: String, val keys: List<String> = emptyList()) {
    val isOpenRouter get() = kind == ProviderKind.OPENAI && "openrouter.ai" in baseUrl
    val builtIn get() = id == OPENROUTER || id == GEMINI

    fun json(): JSONObject = JSONObject().put("id", id).put("kind", kind.name).put("name", name).put("base_url", baseUrl).put("keys", JSONArray(keys))

    companion object {
        const val OPENROUTER = "openrouter"
        const val GEMINI = "gemini"
        const val OPENROUTER_URL = "https://openrouter.ai/api/v1"
        const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta"

        fun parse(o: JSONObject): AiProvider? {
            val id = o.optString("id").trim().ifEmpty { return null }
            val kind = runCatching { ProviderKind.valueOf(o.optString("kind")) }.getOrNull() ?: return null
            val keys = o.optJSONArray("keys")?.let { a -> (0 until a.length()).map { a.optString(it).trim() } }.orEmpty().filter { it.isNotEmpty() }.distinct()
            return AiProvider(id, kind, o.optString("name").trim().ifEmpty { id }, o.optString("base_url").trim().trimEnd('/').ifEmpty { defaultUrl(kind) }, keys)
        }

        fun defaultUrl(kind: ProviderKind) = if (kind == ProviderKind.GEMINI) GEMINI_URL else OPENROUTER_URL
    }
}

/** A model at a provider. Written "provider|model" when stored (model ids have slashes and colons of their own). */
data class ModelRef(val provider: String, val model: String) {
    val id get() = "$provider|$model"
    override fun toString() = id
    companion object {
        fun parse(s: String?): ModelRef? {
            val t = s?.trim().orEmpty(); val i = t.indexOf('|')
            if (i <= 0 || i == t.length - 1) return null
            return ModelRef(t.substring(0, i), t.substring(i + 1))
        }
    }
}

/** What a section needs from a model. Shown on the pickers; a model that can't is still allowed, with a warning. */
enum class Need { TOOLS, AUDIO, TEXT }

/** Every part of Heartline that asks an AI something. Each has its own models and retry rules. */
enum class AiSection(val key: String, val title: String, val what: String, val need: Need) {
    ASSISTANT("assistant", "Watch assistant", "Answers what you say to the watch's Alexa button, calling Heartline's tools.", Need.TOOLS),
    VOICE("voice", "Voice to text", "Turns each question's recording into words before the assistant answers.", Need.AUDIO),
    INSIGHTS("insights", "AI insights", "Writes the Insights page from two weeks of your data.", Need.TEXT),
    COACH("coach", "Coach's note", "The note under a workout capture's analysis.", Need.TEXT),
    TITLES("titles", "Workout names", "Names each workout capture from its exercises.", Need.TEXT);

    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } }
}

/**
 * When a request fails, what to do next. Every failure is sorted first (see [FailKind]):
 *  - out of quota, or the key refused: the next key of the same provider (if [otherKeys]), then the next model;
 *  - rate limited for a short while: wait it out when the API says when, up to [waitForLimitS], on the same model;
 *  - busy, overloaded, timed out or no network: the same model again, [busyRetries] times, waiting [retryWaitsS];
 *  - the model is gone, can't do what's asked, or the request is too long for it: the next model at once;
 *  - an answer that can't be used (empty, wrong shape): the same model [badAnswerRetries] more times, then the next.
 * [timeoutS] bounds each request.
 */
data class RetryPolicy(
    val busyRetries: Int = 2,
    val retryWaitsS: List<Int> = listOf(5, 20),
    val waitForLimitS: Int = 45,
    val badAnswerRetries: Int = 1,
    val otherKeys: Boolean = true,
    val timeoutS: Int = 120,
) {
    fun waitFor(attempt: Int): Long = (retryWaitsS.getOrNull(attempt - 1) ?: retryWaitsS.lastOrNull() ?: 5) * 1000L

    fun clamped() = copy(busyRetries = busyRetries.coerceIn(0, 6), retryWaitsS = retryWaitsS.map { it.coerceIn(0, 300) }.take(6).ifEmpty { listOf(5) },
        waitForLimitS = waitForLimitS.coerceIn(0, 300), badAnswerRetries = badAnswerRetries.coerceIn(0, 4), timeoutS = timeoutS.coerceIn(5, 600))

    fun json(): JSONObject = JSONObject().put("busy_retries", busyRetries).put("retry_waits_s", JSONArray(retryWaitsS)).put("wait_for_limit_s", waitForLimitS)
        .put("bad_answer_retries", badAnswerRetries).put("other_keys", otherKeys).put("timeout_s", timeoutS)

    companion object {
        fun parse(o: JSONObject?, d: RetryPolicy): RetryPolicy = if (o == null) d else RetryPolicy(
            o.optInt("busy_retries", d.busyRetries),
            o.optJSONArray("retry_waits_s")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: d.retryWaitsS,
            o.optInt("wait_for_limit_s", d.waitForLimitS), o.optInt("bad_answer_retries", d.badAnswerRetries),
            o.optBoolean("other_keys", d.otherKeys), o.optInt("timeout_s", d.timeoutS)).clamped()

        /** Heartline's defaults per section: the assistant and voice are waited on at the watch, so they move on sooner. */
        /** Time limits Heartline used before (each request is whole, not streamed, so a thinking model needs room). */
        val OLD_TIMEOUTS = mapOf(AiSection.ASSISTANT to 40, AiSection.VOICE to 12)

        fun default(s: AiSection) = when (s) {
            AiSection.ASSISTANT -> RetryPolicy(busyRetries = 1, retryWaitsS = listOf(2), waitForLimitS = 5, badAnswerRetries = 0, timeoutS = 180)
            AiSection.VOICE -> RetryPolicy(busyRetries = 1, retryWaitsS = listOf(1), waitForLimitS = 3, badAnswerRetries = 0, timeoutS = (GeminiSpeech.TIMEOUT_MS / 1000).toInt())
            AiSection.INSIGHTS -> RetryPolicy(busyRetries = 2, retryWaitsS = listOf(6, 20), waitForLimitS = 45, badAnswerRetries = 1, timeoutS = 180)
            AiSection.COACH -> RetryPolicy(busyRetries = 2, retryWaitsS = listOf(5, 15), waitForLimitS = 30, badAnswerRetries = 1, timeoutS = 120)
            AiSection.TITLES -> RetryPolicy(busyRetries = 1, retryWaitsS = listOf(5), waitForLimitS = 20, badAnswerRetries = 1, timeoutS = 60)
        }
    }
}

/** A section's main model, its two fallbacks (null when unset) and its retry rules. */
data class SectionSetup(val main: ModelRef? = null, val fallback1: ModelRef? = null, val fallback2: ModelRef? = null, val policy: RetryPolicy) {
    /** The chain, in order, without gaps or repeats. */
    val chain get() = listOfNotNull(main, fallback1, fallback2).distinct()
    fun at(level: Int) = when (level) { 0 -> main; 1 -> fallback1; else -> fallback2 }
    fun with(level: Int, ref: ModelRef?) = when (level) { 0 -> copy(main = ref); 1 -> copy(fallback1 = ref); else -> copy(fallback2 = ref) }

    fun json(): JSONObject = JSONObject().put("main", main?.id ?: "").put("fallback1", fallback1?.id ?: "").put("fallback2", fallback2?.id ?: "")
        .put("policy", policy.json().put("limits", LIMITS))

    companion object {
        const val LIMITS = 2
        fun parse(o: JSONObject?, s: AiSection): SectionSetup {
            val saved = o?.optJSONObject("policy")
            var policy = RetryPolicy.parse(saved, RetryPolicy.default(s))
            if (saved != null && saved.optInt("limits", 1) < LIMITS && policy.timeoutS == RetryPolicy.OLD_TIMEOUTS[s])
                policy = policy.copy(timeoutS = RetryPolicy.default(s).timeoutS)
            return SectionSetup(ModelRef.parse(o?.optString("main")), ModelRef.parse(o?.optString("fallback1")), ModelRef.parse(o?.optString("fallback2")), policy)
        }
    }
}

/**
 * Everything about Heartline's AI: the providers and their keys, the models the owner picked to choose from, and each
 * section's models and rules. Stored in meta as JSON under [KEY]; keys never leave the phone except to their provider.
 */
data class AiSetup(
    val providers: List<AiProvider> = defaultProviders(),
    val picked: List<ModelRef> = emptyList(),
    val sections: Map<AiSection, SectionSetup> = AiSection.entries.associateWith { SectionSetup(policy = RetryPolicy.default(it)) },
) {
    fun provider(id: String) = providers.firstOrNull { it.id == id }
    fun section(s: AiSection) = sections[s] ?: SectionSetup(policy = RetryPolicy.default(s))
    /** Whether any model in [s]'s chain has a provider with a key. */
    fun ready(s: AiSection) = section(s).chain.any { r -> provider(r.provider)?.keys?.isNotEmpty() == true }

    fun json(): String = JSONObject().put("version", 1)
        .put("providers", JSONArray(providers.map { it.json() }))
        .put("picked", JSONArray(picked.map { it.id }))
        .put("sections", JSONObject().apply { AiSection.entries.forEach { put(it.key, section(it).json()) } }).toString()

    fun withProvider(p: AiProvider) = copy(providers = if (providers.any { it.id == p.id }) providers.map { if (it.id == p.id) p else it } else providers + p)
    fun withSection(s: AiSection, setup: SectionSetup) = copy(sections = sections + (s to setup))

    companion object {
        const val KEY = "ai_setup"

        fun defaultProviders() = listOf(
            AiProvider(AiProvider.OPENROUTER, ProviderKind.OPENAI, "OpenRouter", AiProvider.OPENROUTER_URL),
            AiProvider(AiProvider.GEMINI, ProviderKind.GEMINI, "Gemini", AiProvider.GEMINI_URL),
        )

        fun parse(text: String?): AiSetup? = runCatching {
            val o = JSONObject(text ?: return null)
            val list = o.optJSONArray("providers") ?: JSONArray()
            var providers = (0 until list.length()).mapNotNull { list.optJSONObject(it)?.let(AiProvider::parse) }.distinctBy { it.id }
            // The two built-in providers are always there, even with no keys.
            defaultProviders().forEach { d -> if (providers.none { it.id == d.id }) providers = providers + d }
            val picked = o.optJSONArray("picked")?.let { a -> (0 until a.length()).mapNotNull { ModelRef.parse(a.optString(it)) } }.orEmpty().distinct()
            val sec = o.optJSONObject("sections")
            AiSetup(providers, picked, AiSection.entries.associateWith { SectionSetup.parse(sec?.optJSONObject(it.key), it) })
        }.getOrNull()
    }
}

/** A model a provider offers, as its model list describes it. Null capabilities are unknown. */
data class CatalogModel(val ref: ModelRef, val name: String, val context: Int? = null, val tools: Boolean? = null, val audio: Boolean? = null,
                        val live: Boolean = false, val free: Boolean? = null) {
    fun suits(n: Need) = when (n) { Need.TOOLS -> tools != false && !live; Need.AUDIO -> live || audio == true; Need.TEXT -> !live }

    fun json(): JSONObject = JSONObject().put("ref", ref.id).put("name", name).put("context", context ?: -1)
        .put("tools", tools?.let { if (it) 1 else 0 } ?: -1).put("audio", audio?.let { if (it) 1 else 0 } ?: -1).put("live", live)
        .put("free", free?.let { if (it) 1 else 0 } ?: -1)

    companion object {
        private fun tri(o: JSONObject, k: String) = when (o.optInt(k, -1)) { 1 -> true; 0 -> false; else -> null }
        fun parse(o: JSONObject): CatalogModel? {
            val ref = ModelRef.parse(o.optString("ref")) ?: return null
            return CatalogModel(ref, o.optString("name").ifEmpty { ref.model }, o.optInt("context", -1).takeIf { it > 0 }, tri(o, "tools"), tri(o, "audio"),
                o.optBoolean("live"), tri(o, "free"))
        }
    }
}

/** Every provider's model list as last fetched: [fetchedAt] epoch seconds per provider, and why a fetch failed. */
data class AiCatalog(val models: List<CatalogModel> = emptyList(), val fetchedAt: Map<String, Long> = emptyMap(), val errors: Map<String, String> = emptyMap()) {
    fun of(ref: ModelRef) = models.firstOrNull { it.ref == ref }

    fun json(): String = JSONObject().put("models", JSONArray(models.map { it.json() }))
        .put("fetched_at", JSONObject(fetchedAt as Map<*, *>)).put("errors", JSONObject(errors as Map<*, *>)).toString()

    companion object {
        const val KEY = "ai_catalog"
        fun parse(text: String?): AiCatalog = runCatching {
            val o = JSONObject(text ?: return AiCatalog())
            val a = o.optJSONArray("models") ?: JSONArray()
            val f = o.optJSONObject("fetched_at"); val e = o.optJSONObject("errors")
            AiCatalog((0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(CatalogModel::parse) },
                f?.keys()?.asSequence()?.associateWith { f.optLong(it) }.orEmpty(), e?.keys()?.asSequence()?.associateWith { e.optString(it) }.orEmpty())
        }.getOrDefault(AiCatalog())
    }
}
