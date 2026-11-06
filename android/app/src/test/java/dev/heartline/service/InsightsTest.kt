package dev.heartline.service

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsTest {
    @Test fun reportsParseFromModelReplies() {
        val reply = """Here you go:
            ```json
            {"headline": "Sleep is the lever.", "insights": [
              {"area": "sleep", "title": "Short nights", "detail": "5.3 h on average.", "action": "Bed by 23:30", "tone": "watch"},
              {"area": "nonsense", "title": "Fit", "detail": "VO2max 52.", "action": "", "tone": "great"},
              {"area": "heart", "title": "", "detail": "dropped: no title"}]}
            ```"""
        val r = InsightReport.fromModel(reply, 100, "m", 5)
        assertEquals("Sleep is the lever.", r.headline)
        assertEquals(2, r.insights.size)
        assertEquals(Insight("sleep", "Short nights", "5.3 h on average.", "Bed by 23:30", "watch"), r.insights[0])
        assertEquals(Insight("recovery", "Fit", "VO2max 52.", null, "neutral"), r.insights[1])
        assertEquals(r, InsightReport.parse(r.json()))
        assertTrue(runCatching { InsightReport.fromModel("no json here", 1, "m", 1) }.isFailure)
    }

    @Test fun settingsDefaultToGemini() {
        assertEquals(InsightsSettings.GEMINI, InsightsSettings.parse(null).provider)
        assertEquals(GeminiText.DEFAULT_MODEL, InsightsSettings.parse("{}").geminiModel)
        val s = InsightsSettings(InsightsSettings.OPENROUTER, "gemini-x", "a/b")
        assertEquals(s, InsightsSettings.parse(s.json()))
    }

    @Test fun geminiRequestAndAnswer() = runBlocking {
        var url = ""; var headers = emptyMap<String, String>(); var body = JSONObject()
        val g = GeminiText { u, h, b ->
            url = u; headers = h; body = JSONObject(b)
            """{"candidates":[{"content":{"parts":[{"text":"thinking...","thought":true},{"text":"{\"a\":1}"}]},"finishReason":"STOP"}],
               "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":3},"modelVersion":"gemini-3.8-flash-001"}"""
        }
        val r = g.generate("k", "models/gemini-3.8-flash", "sys", "hi")
        assertEquals("${GeminiText.BASE}/gemini-3.8-flash:generateContent", url)
        assertEquals("k", headers["x-goog-api-key"])
        assertEquals("sys", body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals("application/json", body.getJSONObject("generationConfig").getString("responseMimeType"))
        assertEquals("{\"a\":1}", r.text)
        assertEquals("gemini-3.8-flash-001", r.model)
        assertEquals(10, r.promptTokens)
        val cut = GeminiText { _, _, _ -> """{"candidates":[{"content":{"parts":[]},"finishReason":"MAX_TOKENS"}]}""" }
        assertEquals("gemini-3.8-flash ran out of tokens before it answered", runCatching { cut.generate("k", "gemini-3.8-flash", "s", "u") }.exceptionOrNull()?.message)
        assertTrue(runCatching { g.generate("", "m", "s", "u") }.exceptionOrNull()!!.message!!.contains("no Gemini key"))
    }
}
