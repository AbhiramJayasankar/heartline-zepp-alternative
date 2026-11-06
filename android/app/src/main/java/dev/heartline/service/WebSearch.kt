package dev.heartline.service

import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The owner's web search keys (typed into the app, stored in meta). Both have free tiers without a card (2026-11-06):
 * Tavily 1,000 searches a month; Exa $10 of credit a month, about 800 searches with page text. Tavily is tried first.
 */
data class WebSearchConfig(val tavilyKey: String = "", val exaKey: String = "") {
    val enabled get() = tavilyKey.isNotBlank() || exaKey.isNotBlank()
    fun json(): String = JSONObject().put("tavily", tavilyKey).put("exa", exaKey).toString()
    companion object {
        const val KEY = "web_search"
        fun parse(text: String?): WebSearchConfig = runCatching {
            val o = JSONObject(text ?: "{}")
            WebSearchConfig(o.optString("tavily").trim(), o.optString("exa").trim())
        }.getOrDefault(WebSearchConfig())
    }
}

/**
 * The brain's web_search tool: Tavily (its short answer and the top pages' snippets), else Exa (the top pages' text),
 * as plain text for the model. Keys are never logged.
 */
class WebSearch(
    private val config: suspend () -> WebSearchConfig,
    private val post: suspend (url: String, headers: Map<String, String>, body: String) -> String = ::httpPost,
) {
    val tools = listOf(Tool("web_search",
        "Search the web. Use it for anything current or that you aren't sure of: news, sports results, prices, opening " +
            "hours, recent events, facts about people, places or products. Returns snippets from the top pages with their sources.",
        JSONObject().put("type", "object").put("properties", JSONObject()
            .put("query", JSONObject().put("type", "string").put("description", "What to search for, as a search engine query")))
            .put("required", JSONArray().put("query"))))

    /** The tool's result, or null for a tool that isn't web_search. */
    suspend fun run(name: String, args: JSONObject): String? {
        if (name != "web_search") return null
        val query = args.optString("query").trim().ifEmpty { error("no query") }
        val c = config()
        if (!c.enabled) error("web search isn't set up: add a Tavily or Exa key in Heartline (Watch, Alexa answers)")
        val failures = ArrayList<String>()
        if (c.tavilyKey.isNotBlank()) try { return tavily(query, c.tavilyKey) } catch (e: CancellationException) { throw e } catch (e: Exception) { failures += "Tavily: ${e.message}" }
        if (c.exaKey.isNotBlank()) try { return exa(query, c.exaKey) } catch (e: CancellationException) { throw e } catch (e: Exception) { failures += "Exa: ${e.message}" }
        error(failures.joinToString("; "))
    }

    private suspend fun tavily(query: String, key: String): String {
        val body = JSONObject().put("query", query).put("search_depth", "basic").put("max_results", RESULTS).put("include_answer", true)
        val o = JSONObject(post("https://api.tavily.com/search", mapOf("Authorization" to "Bearer $key"), body.toString()))
        val results = o.optJSONArray("results") ?: JSONArray()
        return format(o.optString("answer").takeIf { it.isNotBlank() && it != "null" },
            (0 until results.length()).map { results.getJSONObject(it).let { r -> Page(r.optString("title"), r.optString("url"), r.optString("content"), null) } })
    }

    private suspend fun exa(query: String, key: String): String {
        val body = JSONObject().put("query", query).put("type", "auto").put("numResults", RESULTS)
            .put("contents", JSONObject().put("text", JSONObject().put("maxCharacters", PAGE_TEXT)))
        val o = JSONObject(post("https://api.exa.ai/search", mapOf("x-api-key" to key), body.toString()))
        val results = o.optJSONArray("results") ?: JSONArray()
        return format(null, (0 until results.length()).map { results.getJSONObject(it).let { r ->
            Page(r.optString("title"), r.optString("url"), r.optString("text"), r.optString("publishedDate").take(10).ifBlank { null })
        } })
    }

    private class Page(val title: String, val url: String, val text: String, val date: String?)

    private fun format(answer: String?, pages: List<Page>): String {
        if (answer == null && pages.isEmpty()) return "No results."
        val out = StringBuilder()
        answer?.let { out.append("Summary: ").append(it.trim()).append("\n\n") }
        pages.forEachIndexed { i, p ->
            out.append("${i + 1}. ${p.title.trim()}").append(p.date?.let { " ($it)" } ?: "").append("\n").append(p.url).append("\n")
                .append(p.text.replace(Regex("\\s+"), " ").trim().take(PAGE_TEXT)).append("\n\n")
        }
        return out.toString().trim()
    }

    companion object {
        const val RESULTS = 5
        /** Characters of each page passed to the model. */
        const val PAGE_TEXT = 800
        private const val TIMEOUT_MS = 20_000

        suspend fun httpPost(url: String, headers: Map<String, String>, body: String): String = withContext(Dispatchers.IO) {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.connectTimeout = 10_000; c.readTimeout = TIMEOUT_MS; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                c.outputStream.use { it.write(body.toByteArray()) }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) error("$code ${errorMessage(text)}")
                text
            } finally { c.disconnect() }
        }

        /** The service's own error message: Tavily sends {"detail": {"error": ...}}, Exa {"error": ...}. */
        fun errorMessage(body: String): String = runCatching {
            val o = JSONObject(body)
            o.optJSONObject("detail")?.optString("error")?.takeIf { it.isNotBlank() }
                ?: o.optString("detail").takeIf { it.isNotBlank() } ?: o.optString("error").takeIf { it.isNotBlank() }
        }.getOrNull() ?: body.take(160)
    }
}
