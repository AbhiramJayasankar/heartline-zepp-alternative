package dev.heartline.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/**
 * One-shot text generation with the Gemini API (generateContent) and the owner's AI Studio key, the same key the
 * Alexa button's speech uses. [post] is replaceable for tests.
 */
class GeminiText(private val post: suspend (url: String, headers: Map<String, String>, body: String) -> String = ::httpPost,
                 private val get: suspend (url: String, headers: Map<String, String>) -> String = ::httpGet) {
    /** The text models this key can use (generateContent), newest names first. */
    suspend fun listModels(key: String): List<String> {
        check(key.isNotBlank()) { "no Gemini key saved" }
        val o = JSONObject(get("$BASE?pageSize=200", mapOf("x-goog-api-key" to key)))
        val models = o.optJSONArray("models") ?: JSONArray()
        return (0 until models.length()).mapNotNull { models.optJSONObject(it) }
            .filter { m -> m.optJSONArray("supportedGenerationMethods")?.let { a -> (0 until a.length()).any { a.optString(it) == "generateContent" } } == true }
            .map { it.optString("name").removePrefix("models/") }.filter { it.isNotEmpty() }.sortedDescending()
    }

    class Reply(val text: String, val model: String, val promptTokens: Int?, val outputTokens: Int?)

    suspend fun generate(key: String, model: String, system: String, user: String, json: Boolean = true, temperature: Double = 0.4,
                         maxTokens: Int = 8192): Reply {
        check(key.isNotBlank()) { "no Gemini key saved (add it under Watch, Alexa, Voice)" }
        val name = model.trim().removePrefix("models/").ifEmpty { DEFAULT_MODEL }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
            .put("generationConfig", JSONObject().put("temperature", temperature).put("maxOutputTokens", maxTokens).apply {
                if (json) put("responseMimeType", "application/json")
            })
        val reply = JSONObject(post("$BASE/$name:generateContent", mapOf("x-goog-api-key" to key, "Content-Type" to "application/json"), body.toString()))
        val candidate = reply.optJSONArray("candidates")?.optJSONObject(0)
            ?: error("Gemini gave no answer" + (reply.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotEmpty() }?.let { " (blocked: $it)" } ?: ""))
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        // Thought summaries, if any, are marked "thought"; the answer is the rest.
        val text = (0 until parts.length()).mapNotNull { parts.optJSONObject(it) }.filter { !it.optBoolean("thought") }.joinToString("") { it.optString("text") }.trim()
        if (text.isEmpty()) error(if (candidate.optString("finishReason") == "MAX_TOKENS") "$name ran out of tokens before it answered" else "$name gave no answer (${candidate.optString("finishReason")})")
        val usage = reply.optJSONObject("usageMetadata")
        return Reply(text, reply.optString("modelVersion").ifEmpty { name }, usage?.optInt("promptTokenCount"), usage?.optInt("candidatesTokenCount"))
    }

    companion object {
        const val BASE = "https://generativelanguage.googleapis.com/v1beta/models"
        /** The smartest free text model on the owner's AI Studio plan (2026-11: 5 requests a minute, 20 a day). */
        const val DEFAULT_MODEL = "gemini-3.8-flash"
        const val TIMEOUT_MS = 180_000

        suspend fun httpGet(url: String, headers: Map<String, String>): String = withContext(Dispatchers.IO) {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 10_000; c.readTimeout = 30_000
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) error("Gemini $code: " + (runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull() ?: text.take(200)))
                text
            } finally { c.disconnect() }
        }

        suspend fun httpPost(url: String, headers: Map<String, String>, body: String): String = withContext(Dispatchers.IO) {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.connectTimeout = 10_000; c.readTimeout = TIMEOUT_MS; c.doOutput = true
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                c.outputStream.use { it.write(body.toByteArray()) }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val message = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull() ?: text.take(200)
                    error("Gemini $code: $message")
                }
                text
            } finally { c.disconnect() }
        }
    }
}
