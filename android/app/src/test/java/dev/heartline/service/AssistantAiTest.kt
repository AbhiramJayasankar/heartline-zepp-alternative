package dev.heartline.service

import dev.heartline.service.SampleAssistant.Ask
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AssistantAiTest {
    private val config = AssistantAiConfig("sk-test")
    private val context = AiContext(ZonedDateTime.of(2026, 11, 4, 1, 50, 0, 0, ZoneId.of("Asia/Kolkata")), "72 bpm at 1:49 AM", 715)
    private fun reply(content: String) = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", content)))).toString()

    @Test fun requestCarriesModelsKeyAndClock() = runBlocking {
        var sent: Triple<String, Map<String, String>, String>? = null
        val ai = AssistantAi { url, headers, body -> sent = Triple(url, headers, body); reply("""{"action":"alarm","alarm_time":"09:30"}""") }
        assertEquals(Ask.Alarm(LocalTime.of(9, 30)), ai.interpret("set an alarm for my and thirty am", context, listOf("hi" to "Hello."), config))
        val (url, headers, body) = sent!!
        assertEquals(AssistantAi.URL, url)
        assertEquals("Bearer sk-test", headers["Authorization"])
        val json = JSONObject(body)
        assertEquals(AssistantAiConfig.DEFAULT_MODEL, json.getString("model"))
        assertEquals(listOf(AssistantAiConfig.DEFAULT_MODEL, AssistantAiConfig.FALLBACK_MODEL), (0 until 2).map { json.getJSONArray("models").getString(it) })
        assertEquals("json_object", json.getJSONObject("response_format").getString("type"))
        val messages = json.getJSONArray("messages")
        assertEquals(4, messages.length()) // system, one past turn, the question
        assertTrue(messages.getJSONObject(0).getString("content").contains("Asia/Kolkata"))
        assertTrue(messages.getJSONObject(0).getString("content").contains("01:50"))
        assertEquals("set an alarm for my and thirty am", messages.getJSONObject(3).getString("content"))
    }

    @Test fun actionsMapToAsks() {
        assertEquals(Ask.Timer(300), AssistantAi.toAsk("""{"action":"timer","seconds":300}"""))
        assertEquals(Ask.Reminder(120, "drink water"), AssistantAi.toAsk("""{"action":"reminder","seconds":120,"label":"drink water"}"""))
        assertEquals(Ask.Alarm(LocalTime.of(7, 5)), AssistantAi.toAsk("""{"action":"alarm","alarm_time":"7:05"}"""))
        assertEquals(Ask.CancelTimer, AssistantAi.toAsk("""{"action":"cancel_timer"}"""))
        assertEquals(Ask.Weather, AssistantAi.toAsk("""{"action":"weather"}"""))
        assertEquals(Ask.Say("Paris.", false), AssistantAi.toAsk("""{"action":"answer","reply":"Paris."}"""))
        assertEquals(Ask.Say("Which city?", true), AssistantAi.toAsk("""{"action":"answer","reply":"Which city?","follow_up":true}"""))
        assertEquals(Ask.Timer(60), AssistantAi.toAsk("Sure!\n```json\n{\"action\":\"timer\",\"seconds\":60}\n```"))
        assertNull(AssistantAi.toAsk("""{"action":"timer"}"""))
        assertNull(AssistantAi.toAsk("no idea"))
    }

    @Test fun failuresReturnNull() = runBlocking {
        val failing = AssistantAi { _, _, _ -> error("OpenRouter 429: rate limited") }
        assertNull(failing.interpret("hello", context, emptyList(), config))
        assertEquals("OpenRouter 429: rate limited", failing.lastError)
        assertNull(AssistantAi { _, _, _ -> "<html>" }.interpret("hello", context, emptyList(), config))
    }

    @Test fun noKeyNeverCalls() = runBlocking {
        var called = false
        assertNull(AssistantAi { _, _, _ -> called = true; "" }.interpret("hello", context, emptyList(), AssistantAiConfig()))
        assertFalse(called)
    }

    @Test fun configRoundTripsAndDefaults() {
        assertEquals(config, AssistantAiConfig.parse(config.json()))
        assertEquals(AssistantAiConfig(), AssistantAiConfig.parse("garbage"))
        assertFalse(AssistantAiConfig.parse(null).enabled)
    }
}
