package dev.heartline.service

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AssistantAiTest {
    private val config = AssistantAiConfig("sk-test")
    private val context = AiContext(ZonedDateTime.of(2026, 11, 6, 15, 40, 0, 0, ZoneId.of("Asia/Kolkata")), "72 bpm at 3:39 PM", 715,
        "Noida: 24°C, clear sky now", listOf(AlexaAlert(3, 1, "heartline-alarm-1", 1_794_000_000)), health = "Readiness: 62 out of 100")

    private fun content(text: String, model: String = "m") = JSONObject().put("model", model)
        .put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("role", "assistant").put("content", text)))).toString()
    private fun calls(vararg c: Pair<String, String>) = JSONObject().put("model", "m").put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls")
        .put("message", JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", JSONArray(c.mapIndexed { i, (name, args) ->
            JSONObject().put("id", "call_$i").put("type", "function").put("function", JSONObject().put("name", name).put("arguments", args))
        }))))).toString()

    private class Runner(val result: (String, JSONObject) -> String = { _, _ -> "ok" }) : ToolRunner {
        val ran = ArrayList<Pair<String, String>>()
        override val tools = listOf(Tool("get_sleep", "Sleep for a night.", JSONObject().put("type", "object").put("properties", JSONObject())),
            Tool("set_timer", "Start a timer.", JSONObject().put("type", "object").put("properties", JSONObject().put("seconds", JSONObject().put("type", "integer")))))
        override suspend fun run(name: String, args: JSONObject): String { ran += name to args.toString(); return result(name, args) }
    }

    @Test fun requestCarriesModelsToolsKeyAndContext() = runBlocking {
        var sent: Triple<String, Map<String, String>, String>? = null
        val ai = AssistantAi { url, headers, body -> sent = Triple(url, headers, body); content("Hello.") }
        val reply = ai.converse("Hi", context, listOf("hi" to "Hello."), config, Runner())
        assertEquals("Hello.", reply.text)
        val (url, headers, body) = sent!!
        assertEquals(AssistantAi.URL, url)
        assertEquals("Bearer sk-test", headers["Authorization"])
        val json = JSONObject(body)
        assertEquals("nvidia/nemotron-3.5-super-vl:free", json.getString("model"))
        assertEquals(listOf("nvidia/nemotron-3.5-super-vl:free", "nvidia/nemotron-3.5-lightning:free", "google/gemma-4-31b-it:free"),
            (0 until json.getJSONArray("models").length()).map { json.getJSONArray("models").getString(it) })
        assertEquals(listOf("get_sleep", "set_timer"), (0 until 2).map { json.getJSONArray("tools").getJSONObject(it).getJSONObject("function").getString("name") })
        assertFalse(json.getJSONObject("reasoning").getBoolean("enabled"))
        assertFalse(json.has("response_format"))
        val messages = json.getJSONArray("messages")
        assertEquals(4, messages.length()) // system, one past turn, the question
        val system = messages.getJSONObject(0).getString("content")
        listOf("Asia/Kolkata", "15:40", "id 3: alarm at", "- Readiness: 62 out of 100", "call the get_ tools").forEach { assertTrue(it, system.contains(it)) }
    }

    @Test fun toolCallsRunAndTheirResultsGoBack() = runBlocking {
        val bodies = ArrayList<JSONObject>()
        val replies = ArrayDeque(listOf(calls("get_sleep" to """{"date":"2026-11-06"}""", "set_timer" to """{"seconds":300}"""), content("You slept 7 hours. Timer set.")))
        val runner = Runner { name, _ -> if (name == "get_sleep") "Asleep 7 h 5 min" else "Done: timer for 5 minutes" }
        val reply = AssistantAi { _, _, body -> bodies += JSONObject(body); replies.removeFirst() }.converse("How did I sleep? And a 5 minute timer", context, emptyList(), config, runner)
        assertEquals("You slept 7 hours. Timer set.", reply.text)
        assertEquals(listOf("get_sleep", "set_timer"), reply.toolCalls)
        assertEquals(listOf("get_sleep" to """{"date":"2026-11-06"}""", "set_timer" to """{"seconds":300}"""), runner.ran)
        val second = bodies[1].getJSONArray("messages")
        val n = second.length()
        assertEquals("assistant", second.getJSONObject(n - 3).getString("role"))
        assertEquals(2, second.getJSONObject(n - 3).getJSONArray("tool_calls").length())
        assertEquals("tool", second.getJSONObject(n - 2).getString("role"))
        assertEquals("call_0", second.getJSONObject(n - 2).getString("tool_call_id"))
        assertEquals("Asleep 7 h 5 min", second.getJSONObject(n - 2).getString("content"))
        assertEquals("Done: timer for 5 minutes", second.getJSONObject(n - 1).getString("content"))
    }

    @Test fun toolErrorsAreToldToTheModel() = runBlocking {
        val bodies = ArrayList<JSONObject>()
        val replies = ArrayDeque(listOf(calls("get_sleep" to "not json"), content("I couldn't find that.")))
        val runner = Runner { _, _ -> throw IllegalStateException("no data") }
        AssistantAi { _, _, body -> bodies += JSONObject(body); replies.removeFirst() }.converse("Sleep?", context, emptyList(), config, runner)
        val messages = bodies[1].getJSONArray("messages")
        assertEquals("Error: no data", messages.getJSONObject(messages.length() - 1).getString("content"))
        assertEquals("{}", runner.ran.single().second)
    }

    @Test fun modelsThatMustReasonKeepReasoningForTheWholeConversation() = runBlocking {
        val bodies = ArrayList<JSONObject>()
        val replies = ArrayDeque(listOf(calls("get_sleep" to "{}"), content("Fine.")))
        val ai = AssistantAi { _, _, body ->
            bodies += JSONObject(body)
            if (bodies.size == 1) throw AssistantAi.OpenRouterError("OpenRouter 400: Reasoning is mandatory for this endpoint and cannot be disabled.")
            replies.removeFirst()
        }
        assertEquals("Fine.", ai.converse("Sleep?", context, emptyList(), config, Runner()).text)
        assertEquals(3, bodies.size)
        bodies.drop(1).forEach {
            assertEquals("low", it.getJSONObject("reasoning").getString("effort"))
            assertEquals(AlexaSettings().reasoningMaxTokens, it.getInt("max_tokens"))
        }
    }

    @Test fun failuresSayWhy() = runBlocking {
        var calls = 0
        val limited = AssistantAi { _, _, _ -> calls++; throw AssistantAi.OpenRouterError("OpenRouter 429: Rate limit exceeded") }
        assertEquals("OpenRouter 429: Rate limit exceeded", runCatching { limited.converse("hi", context, emptyList(), config, Runner()) }.exceptionOrNull()?.message)
        assertEquals(1, calls)
        val cut = AssistantAi { _, _, _ -> """{"model":"m","choices":[{"finish_reason":"length","message":{"content":null}}]}""" }
        assertEquals("m ran out of tokens before it answered", runCatching { cut.converse("hi", context, emptyList(), config, Runner()) }.exceptionOrNull()?.message)
        val loop = AssistantAi { _, _, _ -> calls("get_sleep" to "{}") }
        assertEquals("m kept calling tools without answering (6 rounds)", runCatching { loop.converse("hi", context, emptyList(), config, Runner()) }.exceptionOrNull()?.message)
        assertEquals("no OpenRouter key saved", runCatching { limited.converse("hi", context, emptyList(), AssistantAiConfig(), Runner()) }.exceptionOrNull()?.message)
    }

    @Test fun theSecondAccountsKeyTakesOverWhenTheFirstIsRefused() = runBlocking {
        val two = AssistantAiConfig("sk-one", apiKey2 = "sk-two")
        val used = ArrayList<String>()
        var oneLimited = true
        val ai = AssistantAi { _, headers, _ ->
            val key = headers.getValue("Authorization").removePrefix("Bearer ")
            used += key
            if (key == "sk-one" && oneLimited) throw AssistantAi.OpenRouterError("OpenRouter 429: Rate limit exceeded: free-models-per-day")
            content("Hi.")
        }
        assertEquals("Hi.", ai.converse("hi", context, emptyList(), two, Runner()).text)
        assertEquals(listOf("sk-one", "sk-two"), used)
        assertEquals(2, ai.lastKey)
        // The key that worked is tried first next time.
        used.clear(); ai.converse("hi", context, emptyList(), two, Runner())
        assertEquals(listOf("sk-two"), used)
        // Both refused: both reasons are given.
        val none = AssistantAi { _, headers, _ -> throw AssistantAi.OpenRouterError("OpenRouter 429: limit for ${headers.getValue("Authorization").takeLast(3)}") }
        assertEquals("key 1: OpenRouter 429: limit for one; key 2: OpenRouter 429: limit for two",
            runCatching { none.converse("hi", context, emptyList(), two, Runner()) }.exceptionOrNull()?.message)
        assertEquals(two, AssistantAiConfig.parse(two.json()))
        assertEquals(listOf("sk-one"), AssistantAiConfig("sk-one", apiKey2 = "sk-one").keys)
        assertTrue(AssistantAiConfig(apiKey2 = "sk-two").enabled)
    }

    @Test fun theAnsweringModelIsRemembered() = runBlocking {
        val ai = AssistantAi { _, _, _ -> content("Hi.", model = "nvidia/nemotron-3.5-lightning:free") }
        ai.converse("hi", context, emptyList(), config, Runner())
        assertEquals("nvidia/nemotron-3.5-lightning:free", ai.lastModel)
    }

    @Test fun errorMessagesComeFromOpenRouter() {
        assertEquals("Rate limit exceeded: free-models-per-day", AssistantAi.errorMessage("""{"error":{"message":"Rate limit exceeded: free-models-per-day","code":429}}"""))
        assertEquals("Provider returned error: model overloaded",
            AssistantAi.errorMessage("""{"error":{"message":"Provider returned error","metadata":{"raw":"{\"error\":{\"message\":\"model overloaded\"}}"}}}"""))
        assertEquals("<html>", AssistantAi.errorMessage("<html>"))
    }

    @Test fun configRoundTripsAndDefaults() {
        // Earlier versions saved Gemma or the random free router as the model: move them to Nemotron.
        assertEquals(AssistantAiConfig.DEFAULT_MODEL, AssistantAiConfig.parse("""{"key":"k","model":"google/gemma-4-31b-it:free"}""").model)
        assertEquals(AssistantAiConfig.DEFAULT_MODEL, AssistantAiConfig.parse("""{"key":"k","model":"openrouter/free"}""").model)
        assertEquals("x/y:free", AssistantAiConfig.parse("""{"key":"k","model":"x/y:free"}""").model)
        assertEquals(config, AssistantAiConfig.parse(config.json()))
        assertEquals(AssistantAiConfig(), AssistantAiConfig.parse("garbage"))
        assertFalse(AssistantAiConfig.parse(null).enabled)
    }
}
