package dev.heartline.service

import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AssistantAiTest {
    private val config = AssistantAiConfig("sk-test")
    private val context = AiContext(ZonedDateTime.of(2026, 11, 6, 15, 40, 0, 0, ZoneId.of("Asia/Kolkata")), "72 bpm at 3:39 PM", 715,
        "Bengaluru: 24°C, partly cloudy now", listOf(AlexaAlert(3, 1, "heartline-alarm-1", 1_794_000_000)))
    private fun reply(content: String) = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", content)))).toString()

    @Test fun requestCarriesModelsKeyAndContext() = runBlocking {
        var sent: Triple<String, Map<String, String>, String>? = null
        val ai = AssistantAi { url, headers, body -> sent = Triple(url, headers, body); reply("""{"action":"alarm","time":"09:30"}""") }
        assertEquals(Ask.Alarm(LocalTime.of(9, 30)), ai.interpret("Set an alarm for 9:30 a.m.", context, listOf("hi" to "Hello."), config))
        val (url, headers, body) = sent!!
        assertEquals(AssistantAi.URL, url)
        assertEquals("Bearer sk-test", headers["Authorization"])
        val json = JSONObject(body)
        assertEquals(AssistantAiConfig.DEFAULT_MODEL, json.getString("model"))
        assertEquals(listOf(AssistantAiConfig.DEFAULT_MODEL, AssistantAiConfig.FALLBACK_MODEL), (0 until 2).map { json.getJSONArray("models").getString(it) })
        assertEquals("json_object", json.getJSONObject("response_format").getString("type"))
        assertFalse(json.getJSONObject("reasoning").getBoolean("enabled"))
        val messages = json.getJSONArray("messages")
        assertEquals(4, messages.length()) // system, one past turn, the question
        val system = messages.getJSONObject(0).getString("content")
        listOf("Asia/Kolkata", "15:40", "Bengaluru: 24°C", "id 3: alarm at").forEach { assertTrue(it, system.contains(it)) }
    }

    @Test fun modelsThatMustReasonAreAskedAgainWithReasoning() = runBlocking {
        val bodies = ArrayList<JSONObject>()
        val ai = AssistantAi { _, _, body ->
            bodies += JSONObject(body)
            if (bodies.size == 1) throw AssistantAi.OpenRouterError("OpenRouter 400: Reasoning is mandatory for this endpoint and cannot be disabled.")
            reply("""{"action":"help"}""")
        }
        assertEquals(Ask.Help, ai.interpret("What all can you do?", context, emptyList(), config))
        assertEquals(2, bodies.size)
        assertFalse(bodies[1].has("reasoning"))
    }

    @Test fun otherErrorsAreNotRetried() = runBlocking {
        var calls = 0
        val ai = AssistantAi { _, _, _ -> calls++; throw AssistantAi.OpenRouterError("OpenRouter 429: Rate limit exceeded") }
        val e = runCatching { ai.interpret("hello", context, emptyList(), config) }.exceptionOrNull()
        assertEquals("OpenRouter 429: Rate limit exceeded", e?.message)
        assertEquals(1, calls)
    }

    @Test fun actionsMapToAsks() {
        assertEquals(Ask.Timer(300), AssistantAi.toAsk("""{"action":"timer","seconds":300}"""))
        assertEquals(Ask.Reminder("drink water", inSeconds = 120), AssistantAi.toAsk("""{"action":"reminder","seconds":120,"label":"drink water"}"""))
        assertEquals(Ask.Reminder("call home", at = LocalTime.of(18, 0)), AssistantAi.toAsk("""{"action":"reminder","time":"18:00","label":"call home"}"""))
        assertEquals(Ask.Alarm(LocalTime.of(7, 5)), AssistantAi.toAsk("""{"action":"alarm","time":"7:05"}"""))
        assertEquals(Ask.Cancel(listOf(1, 3)), AssistantAi.toAsk("""{"action":"cancel","ids":[1,3]}"""))
        assertEquals(Ask.Weather, AssistantAi.toAsk("""{"action":"weather"}"""))
        assertEquals(Ask.Say("Paris.", false), AssistantAi.toAsk("""{"action":"answer","reply":"Paris."}"""))
        assertEquals(Ask.Say("Which city?", true), AssistantAi.toAsk("""{"action":"answer","reply":"Which city?","follow_up":true}"""))
        assertEquals(Ask.Timer(60), AssistantAi.toAsk("Sure!\n```json\n{\"action\":\"timer\",\"seconds\":60}\n```"))
        assertNull(AssistantAi.toAsk("""{"action":"timer"}"""))
        assertNull(AssistantAi.toAsk("no idea"))
    }

    @Test fun nullContentFallsBackToReasoning() = runBlocking {
        // A thinking model that left content null (seen with gemma-4-26b-a4b-it:free, 2026-11-05).
        val r = """{"model":"m","choices":[{"finish_reason":"stop","message":{"content":null,"reasoning":"They want 9:30. {\"action\":\"alarm\",\"time\":\"09:30\"}"}}]}"""
        assertEquals(Ask.Alarm(LocalTime.of(9, 30)), AssistantAi { _, _, _ -> r }.interpret("alarm 9:30", context, emptyList(), config))
        val empty = AssistantAi { _, _, _ -> """{"model":"m","choices":[{"finish_reason":"length","message":{"content":null}}]}""" }
        val e = runCatching { empty.interpret("hi", context, emptyList(), config) }.exceptionOrNull()
        assertTrue(e!!.message!!.contains("unusable answer (length)"))
    }

    @Test fun errorMessagesComeFromOpenRouter() {
        assertEquals("Rate limit exceeded: free-models-per-day", AssistantAi.errorMessage("""{"error":{"message":"Rate limit exceeded: free-models-per-day","code":429}}"""))
        assertEquals("Provider returned error: model overloaded",
            AssistantAi.errorMessage("""{"error":{"message":"Provider returned error","metadata":{"raw":"{\"error\":{\"message\":\"model overloaded\"}}"}}}"""))
        assertEquals("<html>", AssistantAi.errorMessage("<html>"))
    }

    @Test fun configRoundTripsAndDefaults() {
        assertEquals(config, AssistantAiConfig.parse(config.json()))
        assertEquals(AssistantAiConfig(), AssistantAiConfig.parse("garbage"))
        assertFalse(AssistantAiConfig.parse(null).enabled)
    }
}
