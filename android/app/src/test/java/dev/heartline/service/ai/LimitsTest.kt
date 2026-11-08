package dev.heartline.service.ai

import dev.heartline.service.AlexaSettings
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The assistant's limits (2026-11-09): saved old defaults move to the new ones, and a too-big token budget is retried smaller. */
class LimitsTest {
    @Test fun savedOldDefaultsMoveUpButChosenValuesStay() {
        val old = JSONObject().put("max_tokens", 4000).put("reasoning_max_tokens", 6000).put("max_rounds", 6).put("answer_timeout_s", 90)
            .put("max_answer_chars", 20_000).put("max_notifications", 40).put("temperature", 0.3)
        val s = AlexaSettings.parse(old.toString())
        assertEquals(16_000, s.maxTokens); assertEquals(32_000, s.reasoningMaxTokens); assertEquals(25, s.maxRounds)
        assertEquals(900, s.answerTimeoutS); assertEquals(100_000, s.maxAnswerChars)
        assertEquals(40, s.maxNotifications) // changed by hand: kept
        // Once saved in the new version, a value equal to an old default is the owner's choice.
        val again = AlexaSettings.parse(s.copy(maxRounds = 6).json())
        assertEquals(6, again.maxRounds)
        // Version 2 settings: only the watch wait moves (34 s was too late); the rest is the owner's.
        val v2 = AlexaSettings.parse(JSONObject().put("limits", 2).put("watch_wait_s", 34).put("max_rounds", 6).toString())
        assertEquals(25, v2.watchWaitS); assertEquals(6, v2.maxRounds)
        assertEquals(20, AlexaSettings.parse(JSONObject().put("limits", 2).put("watch_wait_s", 20).toString()).watchWaitS)
    }

    @Test fun savedOldRequestTimeLimitsMoveUp() {
        val setup = FakeAi.setup().withSection(AiSection.VOICE, SectionSetup(policy = RetryPolicy.default(AiSection.VOICE).copy(timeoutS = 12)))
            .withSection(AiSection.ASSISTANT, SectionSetup(policy = RetryPolicy.default(AiSection.ASSISTANT).copy(timeoutS = 55)))
        val o = JSONObject(setup.json())
        // As saved before: no version on the policies.
        o.optJSONObject("sections")?.let { s -> s.keys().forEach { k -> s.optJSONObject(k)?.optJSONObject("policy")?.remove("limits") } }
        val parsed = AiSetup.parse(o.toString())!!
        assertEquals(30, parsed.section(AiSection.VOICE).policy.timeoutS)
        assertEquals(55, parsed.section(AiSection.ASSISTANT).policy.timeoutS)
        assertEquals(30, AiSetup.parse(parsed.json())!!.section(AiSection.VOICE).policy.timeoutS)
    }

    @Test fun aModelThatRefusesTheTokenBudgetIsAskedWithLessAndRemembered() = runBlocking {
        val budgets = ArrayList<Int>()
        val ai = FakeAi(FakeAi.setup()) { _, _, _, b ->
            val n = JSONObject(b!!).optInt("max_tokens"); budgets += n
            if (n > 8192) HttpReply(400, JSONObject().put("error", JSONObject().put("message", "max_tokens is too large: 16000. This model supports at most 8192 completion tokens").put("code", 400)).toString())
            else HttpReply(200, JSONObject().put("model", "m").put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                .put("message", JSONObject().put("content", "ok")))).toString())
        }
        val req = ChatRequest("sys", listOf(ChatMsg.User("q")), maxTokens = 16_000)
        val p = ai.store.setup().providers.first { it.id == AiProvider.OPENROUTER }
        assertEquals("ok", ai.store.api.chat(p, "k", "a/m", req, 1000).text)
        assertEquals("ok", ai.store.api.chat(p, "k", "a/m", req, 1000).text)
        assertEquals(listOf(16_000, 8192, 8192), budgets)
    }
}
