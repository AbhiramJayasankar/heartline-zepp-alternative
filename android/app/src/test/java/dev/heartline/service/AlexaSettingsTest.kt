package dev.heartline.service

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlexaSettingsTest {
    @Test fun settingsRoundTripAndClamp() {
        val s = AlexaSettings(prompt = "Hi {NOW}", disabledTools = setOf("web_search"), toolDescriptions = mapOf("get_sleep" to "Sleep."),
            fallbackModels = listOf("a/b", "c/d"), reasoning = "low", temperature = 1.1, maxTokens = 1234, memoryTurns = 3, replyBytes = 1500,
            listenAgain = false, languages = listOf("en-GB"), vocabulary = listOf("Heartline"), webResults = 7, keepTurns = 50)
        assertEquals(s, AlexaSettings.parse(s.json()))
        assertEquals(AlexaSettings(), AlexaSettings.parse(null))
        assertEquals(AlexaSettings(), AlexaSettings.parse("not json"))
        val wild = AlexaSettings.parse(JSONObject().put("reply_bytes", 9000).put("watch_wait_s", 90).put("reasoning", "maximum")
            .put("temperature", 5).put("languages", org.json.JSONArray()).toString())
        assertEquals(WatchAssistant.REPLY_TEXT, wild.replyBytes)
        assertEquals(38, wild.watchWaitS)
        assertEquals("auto", wild.reasoning)
        assertEquals(2.0, wild.temperature, 0.0)
        assertEquals(GeminiSpeech.LANGUAGES, wild.languages)
        assertEquals(AssistantAi.DEFAULT_PROMPT, AlexaSettings().promptTemplate)
    }

    @Test fun theLogKeepsTheNewestTurns() {
        fun turn(at: Long) = TurnRecord(at, "Watch", "q$at", 1.5, 800, "prompt", 1,
            listOf(RoundLog("m", 1, "off", 900, 100, 20, listOf(ToolCallLog("get_sleep", "{}", "slept", 5)), null)), "a$at", "Reply on the watch", null, 2000, "alexa-$at")
        var json: String? = null
        for (at in 1L..5L) json = AlexaLog.added(json, turn(at), keep = 3)
        val log = AlexaLog.parse(json)
        assertEquals(listOf(5L, 4L, 3L), log.map { it.at })
        assertEquals(turn(5), log.first())
        val long = turn(9).copy(rounds = listOf(RoundLog("m", 1, "off", 1, null, null, listOf(ToolCallLog("x", "{}", "r".repeat(20_000), 1)), null)))
        assertTrue(AlexaLog.parse(AlexaLog.added(null, long, 5)).first().rounds[0].calls[0].result.length == TurnRecord.MAX_RESULT)
    }

    @Test fun placeholdersAreAllFilled() {
        val text = AssistantAi.renderPrompt(AssistantAi.PLACEHOLDERS.keys.joinToString(" "),
            AiContext(java.time.ZonedDateTime.of(2026, 11, 6, 9, 5, 0, 0, java.time.ZoneId.of("Asia/Kolkata")), "60 bpm at 09:00", 100, null, emptyList(), "09:01", "Line one", null))
        assertTrue(text, !text.contains("{"))
        assertEquals("Friday 6 November 2026, 09:05 (Asia/Kolkata) 60 bpm at 09:00 100 09:01 none not running - Line one", text)
    }

    @Test fun theOldFollowUpWordingIsReplacedInSavedPrompts() {
        val old = "Follow-ups: after your reply to the user's query, the watch opens its mic again automatically for their answer only " +
            "when your reply ends with the tag [listen]. Put a question last.\n\nLeave the tag out if the answer is complete. " +
            "Do not add it to every response. The tag goes only at the end of your reply."
        val base = AssistantAi.DEFAULT_PROMPT.substring(0, AssistantAi.DEFAULT_PROMPT.indexOf("Follow-ups:"))
        // Heartline's own text with the old paragraph (and the owner's added line) becomes Heartline's own again.
        assertEquals("", AlexaSettings.parse(org.json.JSONObject().put("prompt", base + old).toString()).prompt)
        // The owner's other changes stay; only the paragraph changes.
        val mine = AlexaSettings.parse(org.json.JSONObject().put("prompt", "Be brief.\n\n" + old).toString()).prompt
        assertEquals("Be brief.\n\n" + AssistantAi.FOLLOW_UPS, mine)
        assertEquals("Be brief.", AlexaSettings.parse(org.json.JSONObject().put("prompt", "Be brief.").toString()).prompt)
    }
}
