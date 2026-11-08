package dev.heartline.service

import dev.heartline.service.ai.AiFailed
import dev.heartline.service.ai.AiProvider
import dev.heartline.service.ai.AiSection
import dev.heartline.service.ai.FakeAi
import dev.heartline.service.ai.HttpReply
import dev.heartline.service.ai.ModelRef
import dev.heartline.service.ai.RetryPolicy
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AssistantAiTest {
    private val context = AiContext(ZonedDateTime.of(2026, 11, 6, 15, 40, 0, 0, ZoneId.of("Asia/Kolkata")), "72 bpm at 3:39 PM", 715,
        "Noida: 24°C, clear sky now", listOf(AlexaAlert(3, 1, "heartline-alarm-1", 1_794_000_000)), health = "Readiness: 62 out of 100")
    private val main = ModelRef(AiProvider.OPENROUTER, "nvidia/nemotron-3.5-super-vl:free")

    private fun content(text: String, model: String = "m") = HttpReply(200, JSONObject().put("model", model)
        .put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("role", "assistant").put("content", text)))).toString())
    private fun calls(vararg c: Pair<String, String>) = HttpReply(200, JSONObject().put("model", "m").put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls")
        .put("message", JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", JSONArray(c.mapIndexed { i, (name, args) ->
            JSONObject().put("id", "call_$i").put("type", "function").put("function", JSONObject().put("name", name).put("arguments", args))
        }))))).toString())
    private fun fail(code: Int, m: String) = HttpReply(code, JSONObject().put("error", JSONObject().put("message", m).put("code", code)).toString())

    private class Runner(val result: (String, JSONObject) -> String = { _, _ -> "ok" }) : ToolRunner {
        val ran = ArrayList<Pair<String, String>>()
        override val tools = listOf(Tool("get_sleep", "Sleep for a night.", JSONObject().put("type", "object").put("properties", JSONObject())),
            Tool("set_timer", "Start a timer.", JSONObject().put("type", "object").put("properties", JSONObject().put("seconds", JSONObject().put("type", "integer")))))
        override suspend fun run(name: String, args: JSONObject): String { ran += name to args.toString(); return result(name, args) }
    }

    private fun brain(keys: List<String> = listOf("sk-test"), chain: List<ModelRef> = listOf(main), gemini: List<String> = emptyList(),
                      reply: (url: String, headers: Map<String, String>, body: JSONObject) -> HttpReply): Pair<AssistantAi, FakeAi> {
        val fake = FakeAi(FakeAi.setup(keys, gemini, AiSection.ASSISTANT, chain, RetryPolicy.default(AiSection.ASSISTANT))) { _, url, h, b -> reply(url, h, JSONObject(b!!)) }
        return AssistantAi(fake.store) to fake
    }
    private fun bodies(f: FakeAi) = f.requests.map { JSONObject(it.third!!) }

    @Test fun requestCarriesTheModelToolsKeyAndContext() = runBlocking {
        val (ai, fake) = brain { _, _, _ -> content("Hello.") }
        val reply = ai.converse("Hi", context, listOf("hi" to "Hello."), Runner())
        assertEquals("Hello.", reply.text)
        val (url, headers, body) = fake.requests.single()
        assertEquals("https://openrouter.ai/api/v1/chat/completions", url)
        assertEquals("Bearer sk-test", headers["Authorization"])
        val json = JSONObject(body!!)
        assertEquals("nvidia/nemotron-3.5-super-vl:free", json.getString("model"))
        assertEquals(listOf("get_sleep", "set_timer"), (0 until 2).map { json.getJSONArray("tools").getJSONObject(it).getJSONObject("function").getString("name") })
        assertFalse(json.getJSONObject("reasoning").getBoolean("enabled"))
        val messages = json.getJSONArray("messages")
        assertEquals(4, messages.length()) // system, one past turn, the question
        val system = messages.getJSONObject(0).getString("content")
        listOf("Asia/Kolkata", "15:40", "id 3: alarm at", "- Readiness: 62 out of 100", "call get_info with the topic").forEach { assertTrue(it, system.contains(it)) }
    }

    @Test fun toolCallsRunAndTheirResultsGoBack() = runBlocking {
        val replies = ArrayDeque(listOf(calls("get_sleep" to """{"date":"2026-11-06"}""", "set_timer" to """{"seconds":300}"""), content("You slept 7 hours. Timer set.")))
        val runner = Runner { name, _ -> if (name == "get_sleep") "Asleep 7 h 5 min" else "Done: timer for 5 minutes" }
        val (ai, fake) = brain { _, _, _ -> replies.removeFirst() }
        val reply = ai.converse("How did I sleep? And a 5 minute timer", context, emptyList(), runner)
        assertEquals("You slept 7 hours. Timer set.", reply.text)
        assertEquals(listOf("get_sleep", "set_timer"), reply.toolCalls)
        assertEquals(listOf("get_sleep" to """{"date":"2026-11-06"}""", "set_timer" to """{"seconds":300}"""), runner.ran)
        val second = bodies(fake)[1].getJSONArray("messages")
        val n = second.length()
        assertEquals("assistant", second.getJSONObject(n - 3).getString("role"))
        assertEquals(2, second.getJSONObject(n - 3).getJSONArray("tool_calls").length())
        assertEquals("tool", second.getJSONObject(n - 2).getString("role"))
        assertEquals("call_0", second.getJSONObject(n - 2).getString("tool_call_id"))
        assertEquals("Asleep 7 h 5 min", second.getJSONObject(n - 2).getString("content"))
        assertEquals("Done: timer for 5 minutes", second.getJSONObject(n - 1).getString("content"))
    }

    @Test fun toolErrorsAreToldToTheModel() = runBlocking {
        val replies = ArrayDeque(listOf(calls("get_sleep" to "not json"), content("I couldn't find that.")))
        val runner = Runner { _, _ -> throw IllegalStateException("no data") }
        val (ai, fake) = brain { _, _, _ -> replies.removeFirst() }
        ai.converse("Sleep?", context, emptyList(), runner)
        val messages = bodies(fake)[1].getJSONArray("messages")
        assertEquals("Error: no data", messages.getJSONObject(messages.length() - 1).getString("content"))
        assertEquals("{}", runner.ran.single().second)
    }

    @Test fun modelsThatMustReasonKeepReasoningForTheWholeConversation() = runBlocking {
        val replies = ArrayDeque(listOf(calls("get_sleep" to "{}"), content("Fine.")))
        var n = 0
        val (ai, fake) = brain { _, _, _ ->
            if (n++ == 0) fail(400, "Reasoning is mandatory for this endpoint and cannot be disabled.") else replies.removeFirst()
        }
        assertEquals("Fine.", ai.converse("Sleep?", context, emptyList(), Runner()).text)
        assertEquals(3, fake.requests.size)
        bodies(fake).drop(1).forEach {
            assertEquals("low", it.getJSONObject("reasoning").getString("effort"))
            assertEquals(AlexaSettings().reasoningMaxTokens, it.getInt("max_tokens"))
        }
    }

    @Test fun failuresSayWhy() = runBlocking {
        val (limited, _) = brain { _, _, _ -> fail(429, "Rate limit exceeded: free-models-per-day") }
        val e = runCatching { limited.converse("hi", context, emptyList(), Runner()) }.exceptionOrNull()
        assertTrue(e is AiFailed)
        assertTrue(e!!.message!!.contains("Rate limit exceeded: free-models-per-day"))
        val (cut, _) = brain { _, _, _ -> HttpReply(200, """{"model":"m","choices":[{"finish_reason":"length","message":{"content":null}}]}""") }
        assertTrue(runCatching { cut.converse("hi", context, emptyList(), Runner()) }.exceptionOrNull()!!.message!!.contains("ran out of tokens before it answered"))
        val (loop, _) = brain { _, _, _ -> calls("get_sleep" to "{}") }
        assertEquals("m kept calling tools without answering (6 rounds)", runCatching { loop.converse("hi", context, emptyList(), Runner()) }.exceptionOrNull()?.message)
    }

    @Test fun anotherKeyTakesOverWhenOneIsOutOfQuota() = runBlocking {
        val used = ArrayList<String>()
        val (ai, _) = brain(listOf("sk-one", "sk-two", "sk-tri")) { _, headers, _ ->
            val key = headers.getValue("Authorization").removePrefix("Bearer ")
            used += key
            if (key != "sk-tri") fail(429, "Rate limit exceeded: free-models-per-day") else content("Hi.")
        }
        assertEquals("Hi.", ai.converse("hi", context, emptyList(), Runner()).text)
        assertEquals(listOf("sk-one", "sk-two", "sk-tri"), used)
        assertEquals(3, ai.lastKey)
        // The key that worked is tried first next time, and the spent ones rest.
        used.clear(); ai.converse("hi", context, emptyList(), Runner())
        assertEquals(listOf("sk-tri"), used)
    }

    @Test fun aFallbackModelAtAnotherProviderPicksUpTheConversation() = runBlocking {
        val g = ModelRef(AiProvider.GEMINI, "gemini-3.8-flash")
        var round = 0
        val (ai, fake) = brain(listOf("sk-one"), listOf(main, g), listOf("g-key")) { url, _, _ ->
            round++
            when {
                round == 1 -> calls("get_sleep" to "{}")
                "openrouter" in url -> fail(429, "Rate limit exceeded: free-models-per-day")
                else -> HttpReply(200, """{"candidates":[{"content":{"role":"model","parts":[{"text":"You slept well."}]},"finishReason":"STOP"}],"modelVersion":"gemini-3.8-flash"}""")
            }
        }
        val trace = TurnTrace()
        assertEquals("You slept well.", ai.converse("Sleep?", context, emptyList(), Runner { _, _ -> "7 h" }, trace = trace).text)
        val gem = bodies(fake).last()
        val contents = gem.getJSONArray("contents")
        // The OpenRouter model's tool call goes to Gemini as a function call (with the placeholder signature) and its result.
        val call = contents.getJSONObject(contents.length() - 2).getJSONArray("parts").getJSONObject(0)
        assertEquals("get_sleep", call.getJSONObject("functionCall").getString("name"))
        assertEquals("skip_thought_signature_validator", call.getString("thoughtSignature"))
        assertEquals("7 h", contents.getJSONObject(contents.length() - 1).getJSONArray("parts").getJSONObject(0)
            .getJSONObject("functionResponse").getJSONObject("response").getString("result"))
        assertEquals("get_sleep", gem.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0).getString("name"))
        assertEquals("Gemini: gemini-3.8-flash", trace.rounds.last().model)
    }

    @Test fun geminiToolCallsKeepTheirSignatures() = runBlocking {
        val g = ModelRef(AiProvider.GEMINI, "gemini-3.8-flash")
        var round = 0
        val (ai, fake) = brain(emptyList(), listOf(g), listOf("g-key")) { _, _, _ ->
            if (round++ == 0) HttpReply(200, """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"set_timer","args":{"seconds":60}},"thoughtSignature":"SIG"}]}}]}""")
            else HttpReply(200, """{"candidates":[{"content":{"role":"model","parts":[{"text":"Timer set."}]}}]}""")
        }
        val runner = Runner()
        assertEquals("Timer set.", ai.converse("timer", context, emptyList(), runner).text)
        assertEquals("""{"seconds":60}""", runner.ran.single().second)
        val echoed = bodies(fake)[1].getJSONArray("contents").getJSONObject(1).getJSONArray("parts").getJSONObject(0)
        assertEquals("SIG", echoed.getString("thoughtSignature"))
        assertEquals("g-key", fake.requests[0].second["x-goog-api-key"])
    }

    @Test fun theAnsweringModelIsRemembered() = runBlocking {
        val (ai, _) = brain { _, _, _ -> content("Hi.", model = "nvidia/nemotron-3.5-lightning:free") }
        ai.converse("hi", context, emptyList(), Runner())
        assertEquals("nvidia/nemotron-3.5-lightning:free", ai.lastModel)
    }

    @Test fun oldConfigStillParsesForTheMove() {
        assertEquals(AssistantAiConfig.DEFAULT_MODEL, AssistantAiConfig.parse("""{"key":"k","model":"openrouter/free"}""").model)
        assertEquals(listOf("sk-one", "sk-tri"), AssistantAiConfig("sk-one", apiKey3 = "sk-tri").keys)
    }
}
