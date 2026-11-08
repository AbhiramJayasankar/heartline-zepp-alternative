package dev.heartline.service.ai

/**
 * An [AiStore] over a map, talking to a fake transport: [reply] gets each request (method, url, headers, body) and
 * returns the reply, or throws. Pauses are skipped, so retry waits cost nothing.
 */
class FakeAi(setup: AiSetup, val reply: suspend (method: String, url: String, headers: Map<String, String>, body: String?) -> HttpReply) {
    val meta = HashMap<String, String>().apply { put(AiSetup.KEY, setup.json()) }
    val requests = ArrayList<Triple<String, Map<String, String>, String?>>()
    val transport: Transport = { method, url, headers, body, _ -> requests += Triple(url, headers, body); reply(method, url, headers, body) }
    val waits = ArrayList<Long>()
    val store = AiStore({ meta[it] }, { k, v -> meta[k] = v }, AiClients(transport), pause = { waits += it })
    val activity get() = AiAttempt.parseAll(meta[AiAttempt.KEY])

    companion object {
        /** OpenRouter and Gemini with the given keys; [section]'s chain set to [chain]. */
        fun setup(openRouterKeys: List<String> = listOf("sk-test"), geminiKeys: List<String> = emptyList(), section: AiSection = AiSection.ASSISTANT,
                  chain: List<ModelRef> = listOf(ModelRef(AiProvider.OPENROUTER, "a/main")), policy: RetryPolicy? = null): AiSetup {
            val base = AiSetup(listOf(
                AiProvider(AiProvider.OPENROUTER, ProviderKind.OPENAI, "OpenRouter", AiProvider.OPENROUTER_URL, openRouterKeys),
                AiProvider(AiProvider.GEMINI, ProviderKind.GEMINI, "Gemini", AiProvider.GEMINI_URL, geminiKeys)))
            return base.withSection(section, SectionSetup(chain.getOrNull(0), chain.getOrNull(1), chain.getOrNull(2), policy ?: RetryPolicy.default(section)))
        }
    }
}
