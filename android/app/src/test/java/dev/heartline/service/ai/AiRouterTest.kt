package dev.heartline.service.ai

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRouterTest {
    private val or = AiProvider.OPENROUTER
    private fun ok(text: String = "hi", model: String = "m") = HttpReply(200, JSONObject().put("model", model).put("choices", JSONArray().put(
        JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("content", text)))).toString())
    private fun fail(code: Int, message: String) = HttpReply(code, JSONObject().put("error", JSONObject().put("message", message).put("code", code)).toString())
    private fun key(h: Map<String, String>) = h["Authorization"]?.removePrefix("Bearer ") ?: h["x-goog-api-key"]
    private fun model(body: String?) = JSONObject(body!!).optString("model")

    private suspend fun ask(ai: FakeAi, s: AiSection = AiSection.ASSISTANT) = ai.store.router.run(s) { t ->
        ai.store.api.chat(t.provider, t.key, t.model, ChatRequest("sys", listOf(ChatMsg.User("q"))), t.timeoutMs).text ?: throw badAnswer("empty")
    }

    @Test fun outOfQuotaMovesToTheNextKeyThenTheNextModel() = runBlocking {
        val chain = listOf(ModelRef(or, "a/main"), ModelRef(or, "b/fallback"))
        val ai = FakeAi(FakeAi.setup(listOf("k1", "k2"), chain = chain)) { _, _, h, b ->
            if (model(b) == "a/main") fail(429, "Rate limit exceeded: free-models-per-day") else ok("from ${key(h)}")
        }
        val out = ask(ai)
        assertEquals("from k1", out.value)
        assertEquals(1, out.target.level)
        // Both keys were tried on the main model, then the fallback answered with the first key.
        assertEquals(listOf("a/main" to "k1", "a/main" to "k2", "b/fallback" to "k1"), ai.requests.map { model(it.third) to key(it.second) })
        assertEquals(listOf(FailKind.QUOTA, FailKind.QUOTA, null), out.attempts.map { it.kind })
        // The spent keys rest: the next request skips the main model's keys entirely.
        ai.requests.clear()
        ask(ai)
        assertEquals(listOf("b/fallback"), ai.requests.map { model(it.third) })
        assertEquals(3 + 2, ai.activity.size)
    }

    @Test fun busyModelsAreAskedAgainBeforeMovingOn() = runBlocking {
        val chain = listOf(ModelRef(or, "a/main"), ModelRef(or, "b/fallback"))
        var n = 0
        val ai = FakeAi(FakeAi.setup(chain = chain, policy = RetryPolicy(busyRetries = 2, retryWaitsS = listOf(6, 20)))) { _, _, _, b ->
            if (model(b) == "a/main" && n++ < 2) fail(503, "This model is currently experiencing high demand") else ok("fine")
        }
        val out = ask(ai)
        assertEquals(0, out.target.level)
        assertEquals(listOf(6_000L, 20_000L), ai.waits)
        // Busy three times: the fallback.
        n = -10
        val ai2 = FakeAi(FakeAi.setup(chain = chain, policy = RetryPolicy(busyRetries = 2, retryWaitsS = listOf(1)))) { _, _, _, b ->
            if (model(b) == "a/main") fail(502, "Provider returned error") else ok("fallback")
        }
        assertEquals("fallback", ask(ai2).value)
        assertEquals(4, ai2.requests.size)
    }

    @Test fun shortRateLimitsAreWaitedOutLongOnesAreNot() = runBlocking {
        var first = true
        val g = ModelRef(AiProvider.GEMINI, "gemini-x")
        val ai = FakeAi(FakeAi.setup(emptyList(), listOf("g1", "g2"), AiSection.INSIGHTS, listOf(g))) { _, _, _, _ ->
            if (first) { first = false; HttpReply(429, """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"Quota exceeded for metric: generate_content_free_tier_requests, limit: 5 per minute. Please retry in 23.5s.",
                "details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"23s"}]}}""") }
            else HttpReply(200, """{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}]}""")
        }
        assertEquals("ok", ask(ai, AiSection.INSIGHTS).value)
        assertEquals(listOf(23_500L), ai.waits)
        assertEquals(listOf("g1", "g1"), ai.requests.map { key(it.second) })
        // Longer than the section waits: the next key instead.
        first = true
        val ai2 = FakeAi(FakeAi.setup(emptyList(), listOf("g1", "g2"), AiSection.ASSISTANT, listOf(g))) { _, _, _, _ ->
            if (first) { first = false; HttpReply(429, """{"error":{"code":429,"message":"Please retry in 40s.","details":[{"retryDelay":"40s"}]}}""") }
            else HttpReply(200, """{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}""")
        }
        ask(ai2)
        assertEquals(listOf("g1", "g2"), ai2.requests.map { key(it.second) })
        assertTrue(ai2.waits.isEmpty())
    }

    @Test fun modelsThatCantDoItAreSkippedAtOnce() = runBlocking {
        val chain = listOf(ModelRef(or, "a/main"), ModelRef(or, "b/fallback"))
        val ai = FakeAi(FakeAi.setup(listOf("k1", "k2"), chain = chain)) { _, _, _, b ->
            if (model(b) == "a/main") fail(404, "No endpoints found that support tool use") else ok()
        }
        ask(ai)
        assertEquals(listOf("a/main", "b/fallback"), ai.requests.map { model(it.third) })
    }

    @Test fun unusableAnswersAreAskedAgainPerTheRules() = runBlocking {
        var n = 0
        val ai = FakeAi(FakeAi.setup(chain = listOf(ModelRef(or, "a/main")), policy = RetryPolicy(badAnswerRetries = 1))) { _, _, _, _ ->
            if (n++ == 0) ok("") else ok("good")
        }
        assertEquals("good", ask(ai).value)
        assertEquals(FailKind.BAD_ANSWER, ai.activity.last().kind)
    }

    @Test fun everyFailureIsReported() = runBlocking {
        val ai = FakeAi(FakeAi.setup(listOf("k1"), chain = listOf(ModelRef(or, "a/main"), ModelRef(AiProvider.GEMINI, "g")))) { _, _, _, _ -> fail(401, "No auth credentials found") }
        val e = runCatching { ask(ai) }.exceptionOrNull() as AiFailed
        assertEquals(FailKind.AUTH, e.attempts.first().kind)
        assertEquals("no key for Gemini", e.attempts.last().note)
        val none = FakeAi(FakeAi.setup(chain = emptyList())) { _, _, _, _ -> ok() }
        assertTrue((runCatching { ask(none) }.exceptionOrNull() as AiFailed).message!!.contains("no model is set"))
    }

    @Test fun onlyTheFirstKeyWhenOtherKeysAreOff() = runBlocking {
        val ai = FakeAi(FakeAi.setup(listOf("k1", "k2"), policy = RetryPolicy(otherKeys = false))) { _, _, _, _ -> fail(402, "Insufficient credits") }
        runCatching { ask(ai) }
        assertEquals(listOf("k1"), ai.requests.map { key(it.second) })
    }

    @Test fun errorsAreSorted() {
        fun k(code: Int, m: String) = AiErrors.http("P", code, JSONObject().put("error", JSONObject().put("message", m)).toString()).kind
        assertEquals(FailKind.QUOTA, k(429, "Rate limit exceeded: free-models-per-day"))
        assertEquals(FailKind.RATE_LIMIT, k(429, "Rate limit exceeded: free-models-per-min"))
        assertEquals(FailKind.QUOTA, k(402, "This request requires more credits"))
        assertEquals(FailKind.AUTH, k(400, "API key not valid. Please pass a valid API key."))
        assertEquals(FailKind.AUTH, k(401, "User not found."))
        assertEquals(FailKind.BUSY, k(503, "The model is overloaded. Please try again later."))
        assertEquals(FailKind.MODEL, k(404, "models/gemini-nope is not found for API version v1beta"))
        assertEquals(FailKind.CONTEXT, k(400, "This endpoint's maximum context length is 8192 tokens"))
        assertEquals(FailKind.REQUEST, k(400, "Invalid value at 'contents'"))
        val perDay = AiErrors.http("Gemini", 429, """{"error":{"code":429,"message":"You exceeded your current quota. Please retry in 3h2m.","status":"RESOURCE_EXHAUSTED",
            "details":[{"violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}]}}""")
        assertEquals(FailKind.QUOTA, perDay.kind)
        assertEquals(FailKind.TIMEOUT, AiErrors.of(java.net.SocketTimeoutException("Read timed out")).kind)
        assertEquals(FailKind.NETWORK, AiErrors.of(java.net.UnknownHostException("openrouter.ai")).kind)
        // The message is short and has no links.
        val long = AiErrors.http("Gemini", 429, """{"error":{"message":"Quota exceeded. For more information, see https://ai.google.dev/gemini-api/docs/rate-limits. Please retry in 5s."}}""")
        assertTrue(!long.message!!.contains("https://"))
        assertEquals(5_000L, long.retryAfterMs)
    }

    @Test fun setupRoundTripsAndKeepsTheBuiltInProviders() {
        val s = FakeAi.setup(listOf("a", "b"), listOf("g"), AiSection.COACH, listOf(ModelRef("gemini", "gemini-3.8-flash"), ModelRef("openrouter", "x/y:free")),
            RetryPolicy(3, listOf(1, 2, 3), 10, 2, false, 99)).copy(picked = listOf(ModelRef("openrouter", "x/y:free")))
            .withProvider(AiProvider("groq", ProviderKind.OPENAI, "Groq", "https://api.groq.com/openai/v1", listOf("gk")))
        assertEquals(s, AiSetup.parse(s.json()))
        val bare = AiSetup.parse("""{"providers":[]}""")!!
        assertEquals(listOf("openrouter", "gemini"), bare.providers.map { it.id })
        assertNull(ModelRef.parse("nobar"))
        assertEquals(ModelRef("openrouter", "a/b:free"), ModelRef.parse("openrouter|a/b:free"))
    }
}
