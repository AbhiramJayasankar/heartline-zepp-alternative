package dev.heartline.service

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WebSearchTest {
    private val q = JSONObject().put("query", "who won the match")
    private val tavilyReply = """{"answer":"India won by 5 wickets.","results":[{"title":"Match report","url":"https://example.com/r","content":"India  chased\n down 250."}]}"""
    private val exaReply = """{"results":[{"title":"Scores","url":"https://example.org/s","text":"Final score 250/5","publishedDate":"2026-11-05T10:00:00Z"}]}"""

    @Test fun tavilyAnswerAndPages() = runBlocking {
        val sent = ArrayList<Triple<String, Map<String, String>, String>>()
        val web = WebSearch({ WebSearchConfig("tvly-key", "exa-key") }) { url, h, body -> sent += Triple(url, h, body); tavilyReply }
        val out = web.run("web_search", q)!!
        assertEquals("Summary: India won by 5 wickets.\n\n1. Match report\nhttps://example.com/r\nIndia chased down 250.", out)
        assertEquals("https://api.tavily.com/search", sent.single().first)
        assertEquals("Bearer tvly-key", sent.single().second["Authorization"])
        assertEquals("who won the match", JSONObject(sent.single().third).getString("query"))
    }

    @Test fun exaWhenTavilyFails() = runBlocking {
        val web = WebSearch({ WebSearchConfig("tvly-key", "exa-key") }) { url, h, _ ->
            if ("tavily" in url) error("432 usage limit") else { assertEquals("exa-key", h["x-api-key"]); exaReply }
        }
        assertEquals("1. Scores (2026-11-05)\nhttps://example.org/s\nFinal score 250/5", web.run("web_search", q))
    }

    @Test fun bothFailingOrMissingKeysIsAnError() = runBlocking {
        val failing = WebSearch({ WebSearchConfig("t", "e") }) { url, _, _ -> error(if ("tavily" in url) "432 limit" else "401 bad key") }
        try { failing.run("web_search", q); fail() } catch (e: IllegalStateException) { assertEquals("Tavily: 432 limit; Exa: 401 bad key", e.message) }
        val none = WebSearch({ WebSearchConfig() }) { _, _, _ -> fail(); "" }
        try { none.run("web_search", q); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("Tavily or Exa key")) }
    }

    @Test fun otherToolsAndConfig() = runBlocking {
        assertNull(WebSearch({ WebSearchConfig("t") }) { _, _, _ -> "" }.run("get_sleep", q))
        assertEquals(WebSearchConfig("a", "b"), WebSearchConfig.parse(WebSearchConfig(" a", "b ").json().let { WebSearchConfig.parse(it).json() }))
        assertEquals(WebSearchConfig(), WebSearchConfig.parse("not json"))
        assertEquals("Query limit reached", WebSearch.errorMessage("""{"detail":{"error":"Query limit reached"}}"""))
        assertEquals("Invalid API key", WebSearch.errorMessage("""{"error":"Invalid API key"}"""))
    }
}
