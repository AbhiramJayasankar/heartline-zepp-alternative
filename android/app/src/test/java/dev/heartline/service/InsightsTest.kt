package dev.heartline.service

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun insightsAreWrittenDownTheChainAndAWrongShapeIsAskedAgain() = runBlocking {
        val g = dev.heartline.service.ai.ModelRef("gemini", "gemini-3.8-flash")
        val o = dev.heartline.service.ai.ModelRef("openrouter", "a/b")
        var n = 0
        val fake = dev.heartline.service.ai.FakeAi(dev.heartline.service.ai.FakeAi.setup(listOf("sk"), listOf("g"), dev.heartline.service.ai.AiSection.INSIGHTS, listOf(g, o))) { _, url, _, _ ->
            n++
            when {
                "googleapis" in url && n == 1 -> dev.heartline.service.ai.HttpReply(503, """{"error":{"code":503,"message":"This model is currently experiencing high demand."}}""")
                "googleapis" in url -> dev.heartline.service.ai.HttpReply(200, """{"candidates":[{"content":{"parts":[{"text":"not json"}]}}]}""")
                else -> dev.heartline.service.ai.HttpReply(200, JSONObject().put("model", "a/b").put("choices", org.json.JSONArray().put(JSONObject().put("message",
                    JSONObject().put("content", """{"headline":"h","insights":[{"title":"t","detail":"d"}]}""")))).toString())
            }
        }
        val tools = HealthTools(dev.heartline.app.DemoController(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)))
        val r = HealthInsights(tools, fake.store).generate(auto = true)
        assertEquals("h", r.headline)
        assertEquals("a/b", r.model)
        // Busy once (asked again), then twice the wrong shape (once more, then the fallback).
        assertEquals(listOf("Busy", "Unusable answer", "Unusable answer", null), r.attempts.map { it.error?.substringBefore(":") })
    }

    @Test fun insightsYouAskForGoOnceToOneModel() = runBlocking {
        val g = dev.heartline.service.ai.ModelRef("gemini", "gemini-3.8-flash")
        val o = dev.heartline.service.ai.ModelRef("openrouter", "a/b")
        val fake = dev.heartline.service.ai.FakeAi(dev.heartline.service.ai.FakeAi.setup(listOf("sk"), listOf("g"), dev.heartline.service.ai.AiSection.INSIGHTS, listOf(g, o))) { _, url, _, _ ->
            if ("googleapis" in url) dev.heartline.service.ai.HttpReply(503, """{"error":{"code":503,"message":"This model is currently experiencing high demand."}}""")
            else dev.heartline.service.ai.HttpReply(200, JSONObject().put("model", "a/b").put("choices", org.json.JSONArray().put(JSONObject().put("message",
                JSONObject().put("content", """{"headline":"h","insights":[{"title":"t","detail":"d"}]}""")))).toString())
        }
        val tools = HealthTools(dev.heartline.app.DemoController(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)))
        // The main model is busy: one request, no retry, no fallback.
        val failed = runCatching { HealthInsights(tools, fake.store).generate() }.exceptionOrNull()
        assertTrue(failed is InsightsFailed)
        assertEquals(1, (failed as InsightsFailed).attempts.size)
        assertEquals(1, fake.requests.size)
        // Picking another model asks that one.
        val r = HealthInsights(tools, fake.store).generate(model = o)
        assertEquals("a/b", r.model)
        assertEquals(1, r.attempts.size)
    }

    @Test fun theDigestSendsEverySectionIncludingHrv() = runBlocking {
        // Every topic the digest asks for must be one the health lookups answer: an unknown one used to be skipped silently.
        HealthInsights.DIGEST.forEach { (topic, _) -> assertTrue("unknown topic $topic", HealthTools.TOPICS.any { it.first == topic }) }
        val tools = HealthTools(dev.heartline.app.DemoController(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)))
        val p = HealthInsights(tools, dev.heartline.service.ai.AiStore({ null }, { _, _ -> })).preview()
        val sections = p.digest.lines().filter { it.startsWith("## ") }
        assertEquals(HealthInsights.DIGEST.size, sections.size)
        HealthInsights.DIGEST.map { it.first }.distinct().forEach { topic ->
            val name = HealthInsights.TITLES[topic]; org.junit.Assert.assertNotNull("no heading for $topic", name)
            assertTrue("no '$name' section in $sections", sections.any { it == "## $name" || it.startsWith("## $name (") })
        }
        assertTrue("no HRV for yesterday in $sections", "## HRV (yesterday)" in sections)
        assertTrue(p.digest.contains("Overnight HRV checks"))
        assertTrue(p.ask.contains(p.digest))
        assertTrue(p.system.contains("heart rate variability"))
    }

    @Test fun reportsKeepHowTheyWereMade() {
        val r = InsightReport(5, "m", "h", listOf(Insight("sleep", "t", "d", null, "watch", listOf("trends: sleep, 14 days"))), 9, "## data", "sys", "{raw}",
            listOf(InsightAttempt("gemini", "gemini-3.8-flash", 1200, "Gemini 503: busy"), InsightAttempt("openrouter", "a/b", 900)), 100, 50, auto = true)
        assertEquals(r, InsightReport.parse(r.json()))
        val withBasis = InsightReport.fromModel("""{"headline":"h","insights":[{"title":"t","detail":"d","based_on":["readiness","trends: steps"]}]}""", 1, "m", 1)
        assertEquals(listOf("readiness", "trends: steps"), withBasis.insights[0].basis)
    }

    @Test fun settingsDefaultToGemini() {
        assertEquals(InsightsSettings.GEMINI, InsightsSettings.parse(null).provider)
        assertEquals(GeminiText.DEFAULT_MODEL, InsightsSettings.parse("{}").geminiModel)
        // Daily insights are opt-in: nothing goes to a model until the user asks or turns them on.
        assertFalse(InsightsSettings.parse(null).auto)
        assertFalse(InsightsSettings.parse("{}").auto)
        assertFalse(InsightsSettings().auto)
        assertTrue(InsightsSettings.parse(InsightsSettings(auto = true).json()).auto)
        val s = InsightsSettings(InsightsSettings.OPENROUTER, "gemini-x", "a/b", listOf("g2"), listOf("c/d"), useOther = false, auto = false, autoHour = 7)
        assertEquals(s, InsightsSettings.parse(s.json()))
    }

    @Test fun geminiRequestAndAnswer() = runBlocking {
        var url = ""; var headers = emptyMap<String, String>(); var body = JSONObject()
        val g = GeminiText(post = { u, h, b ->
            url = u; headers = h; body = JSONObject(b)
            """{"candidates":[{"content":{"parts":[{"text":"thinking...","thought":true},{"text":"{\"a\":1}"}]},"finishReason":"STOP"}],
               "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":3},"modelVersion":"gemini-3.8-flash-001"}"""
        })
        val r = g.generate("k", "models/gemini-3.8-flash", "sys", "hi")
        assertEquals("${GeminiText.BASE}/gemini-3.8-flash:generateContent", url)
        assertEquals("k", headers["x-goog-api-key"])
        assertEquals("sys", body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals("application/json", body.getJSONObject("generationConfig").getString("responseMimeType"))
        assertEquals("{\"a\":1}", r.text)
        assertEquals("gemini-3.8-flash-001", r.model)
        assertEquals(10, r.promptTokens)
        val cut = GeminiText(post = { _, _, _ -> """{"candidates":[{"content":{"parts":[]},"finishReason":"MAX_TOKENS"}]}""" })
        val lister = GeminiText(get = { _, _ -> """{"models":[{"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent"]},
            {"name":"models/embedding-2","supportedGenerationMethods":["embedContent"]},{"name":"models/gemini-3.9-pro","supportedGenerationMethods":["generateContent","countTokens"]}]}""" })
        assertEquals(listOf("gemini-3.9-pro", "gemini-3.8-flash"), lister.listModels("k"))
        assertEquals("gemini-3.8-flash ran out of tokens before it answered", runCatching { cut.generate("k", "gemini-3.8-flash", "s", "u") }.exceptionOrNull()?.message)
        assertTrue(runCatching { g.generate("", "m", "s", "u") }.exceptionOrNull()!!.message!!.contains("no Gemini key"))
    }

    @Test
    fun everyHealthTopicAnswers() = runBlocking {
        // The assistant and the insights read health data through one tool: a topic listed but not answered is a
        // section the model silently never sees.
        val tools = HealthTools(dev.heartline.app.DemoController(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)))
        HealthTools.TOPICS.forEach { (topic, _) ->
            val answer = tools.run(HealthTools.TOOL, JSONObject().put("topic", topic))
            assertTrue("topic $topic answered nothing", !answer.isNullOrBlank())
        }
    }
}
