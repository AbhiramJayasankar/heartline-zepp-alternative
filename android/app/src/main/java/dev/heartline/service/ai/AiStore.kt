package dev.heartline.service.ai

import dev.heartline.service.AlexaSettings
import dev.heartline.service.AssistantAiConfig
import dev.heartline.service.GeminiSpeech
import dev.heartline.service.InsightsSettings
import dev.heartline.service.SpeechConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Heartline's AI setup in meta: read (moving the older per-feature keys and models over the first time), changed, and
 * the shared [router] every AI feature sends its requests through, so a key that ran out of quota for one feature is
 * left alone by the others too.
 */
class AiStore(
    private val get: suspend (String) -> String?,
    private val set: suspend (String, String) -> Unit,
    val api: ChatApi = AiClients(),
    private val clock: () -> Long = System::currentTimeMillis,
    pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    private val lock = Mutex()
    private val logLock = Mutex()
    val router = AiRouter({ setup() }, { record(it) }, pause, clock)

    suspend fun setup(): AiSetup = AiSetup.parse(get(AiSetup.KEY)) ?: lock.withLock {
        AiSetup.parse(get(AiSetup.KEY)) ?: migrate().also { set(AiSetup.KEY, it.json()) }
    }

    suspend fun update(change: (AiSetup) -> AiSetup): AiSetup = lock.withLock {
        val next = change(AiSetup.parse(get(AiSetup.KEY)) ?: migrate())
        set(AiSetup.KEY, next.json()); next
    }

    suspend fun catalog(): AiCatalog = AiCatalog.parse(get(AiCatalog.KEY))

    private suspend fun record(attempts: List<AiAttempt>) = logLock.withLock { set(AiAttempt.KEY, AiAttempt.added(get(AiAttempt.KEY), attempts)) }

    /**
     * Fetches every provider's model list with the first key that works (or only [only]'s). A provider that can't be
     * reached keeps its last list and gets an error to show.
     */
    suspend fun refreshCatalog(only: String? = null): AiCatalog {
        val s = setup()
        var cat = catalog()
        for (p in s.providers.filter { only == null || it.id == only }) {
            if (p.keys.isEmpty()) { cat = cat.copy(errors = cat.errors + (p.id to "no key")); continue }
            var err: String? = null
            for (key in p.keys) {
                try {
                    val list = api.models(p, key)
                    cat = AiCatalog(cat.models.filter { it.ref.provider != p.id } + list, cat.fetchedAt + (p.id to clock() / 1000), cat.errors - p.id)
                    err = null; break
                } catch (e: Exception) { dev.heartline.ble.rethrowIfCancelled(e); err = AiErrors.of(e).message }
            }
            if (err != null) cat = cat.copy(errors = cat.errors + (p.id to err))
        }
        // Providers that were removed take their models with them.
        val ids = s.providers.map { it.id }.toSet()
        cat = cat.copy(models = cat.models.filter { it.ref.provider in ids })
        set(AiCatalog.KEY, cat.json())
        return cat
    }

    /** Each of [providerId]'s keys asked for the model list: null when it works, else why not. */
    suspend fun testKeys(providerId: String): List<String?> {
        val p = setup().provider(providerId) ?: return emptyList()
        return p.keys.map { key ->
            try { api.models(p, key).let { null } } catch (e: Exception) { dev.heartline.ble.rethrowIfCancelled(e);
                val a = AiErrors.of(e); "${a.kind.label}: ${a.message}"
            }
        }
    }

    /** The first setup, from what the earlier versions saved per feature. */
    private suspend fun migrate(): AiSetup {
        val brain = AssistantAiConfig.parse(get(AssistantAiConfig.KEY))
        val gemini = SpeechConfig.parse(get(SpeechConfig.KEY)).geminiKey
        val alexa = AlexaSettings.parse(get(AlexaSettings.KEY))
        val ins = InsightsSettings.parse(get(InsightsSettings.KEY))
        val providers = AiSetup.defaultProviders().map { p ->
            when (p.id) { AiProvider.OPENROUTER -> p.copy(keys = brain.keys); AiProvider.GEMINI -> p.copy(keys = listOfNotNull(gemini.takeIf { it.isNotBlank() })); else -> p }
        }
        fun or(m: String?) = m?.trim()?.takeIf { it.isNotEmpty() }?.let { ModelRef(AiProvider.OPENROUTER, it) }
        fun gm(m: String?) = m?.trim()?.removePrefix("models/")?.takeIf { it.isNotEmpty() }?.let { ModelRef(AiProvider.GEMINI, it) }
        val assistant = (listOfNotNull(or(brain.model)) + alexa.fallbackModels.mapNotNull(::or)).distinct()
        val voice = gm(alexa.geminiModel)
        val gChain = (listOfNotNull(gm(ins.geminiModel)) + ins.geminiFallbacks.mapNotNull(::gm)).distinct()
        val oChain = (listOfNotNull(or(ins.openRouterModel.ifBlank { brain.model })) + ins.openRouterFallbacks.mapNotNull(::or)).distinct()
        val insights = (if (ins.provider == InsightsSettings.GEMINI) gChain + (if (ins.useOther) oChain else emptyList())
            else oChain + (if (ins.useOther) gChain else emptyList())).distinct()
        fun chain(s: AiSection, l: List<ModelRef>) = SectionSetup(l.getOrNull(0), l.getOrNull(1), l.getOrNull(2), RetryPolicy.default(s))
        val geminiText = listOfNotNull(gm(ins.geminiModel)) + listOfNotNull(or(brain.model))
        val sections = mapOf(
            AiSection.ASSISTANT to chain(AiSection.ASSISTANT, assistant),
            AiSection.VOICE to chain(AiSection.VOICE, listOfNotNull(voice ?: gm(GeminiSpeech.MODEL))),
            AiSection.INSIGHTS to chain(AiSection.INSIGHTS, insights),
            AiSection.COACH to chain(AiSection.COACH, geminiText),
            AiSection.TITLES to chain(AiSection.TITLES, geminiText),
        )
        val picked = sections.values.flatMap { it.chain }.distinct()
        return AiSetup(providers, picked, sections)
    }
}
