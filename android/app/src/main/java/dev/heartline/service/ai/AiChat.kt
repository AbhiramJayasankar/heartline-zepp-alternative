package dev.heartline.service.ai

import dev.heartline.service.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/** A tool call the model asked for. [signature] is Gemini's thought signature, sent back with the call. */
data class ToolCall(val id: String, val name: String, val args: JSONObject, val signature: String? = null)

/** Audio sent with a message: 16 kHz mono 16-bit PCM. */
class Audio(val pcm: ShortArray, val rate: Int = 16_000) {
    /** As a WAV file, for APIs that take a file. */
    fun wav(): ByteArray {
        val data = ByteArray(pcm.size * 2).also { b -> pcm.forEachIndexed { i, v -> b[2 * i] = v.toByte(); b[2 * i + 1] = (v.toInt() shr 8).toByte() } }
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(data.size)
        return h.array() + data
    }
}

/** One message in a conversation, in a form either kind of provider can be given. */
sealed class ChatMsg {
    data class User(val text: String, val audio: Audio? = null) : ChatMsg()
    /** [gemini] is Gemini's own content for this turn (with its thought signatures), sent back as it came when the next request is to Gemini. */
    data class Assistant(val text: String?, val calls: List<ToolCall> = emptyList(), val openAi: JSONObject? = null, val gemini: JSONObject? = null) : ChatMsg()
    data class ToolResult(val callId: String, val name: String, val content: String) : ChatMsg()
}

/**
 * What to ask. [reasoning] null is none; "low", "medium" or "high" ask for thinking (with [reasoningMaxTokens] room).
 * [json] asks for a JSON answer where the API can be told so (Gemini); OpenAI-compatible APIs are only told in words.
 */
data class ChatRequest(
    val system: String, val messages: List<ChatMsg>, val tools: List<Tool> = emptyList(), val temperature: Double = 0.4,
    val maxTokens: Int = 4000, val json: Boolean = false, val reasoning: String? = null, val reasoningMaxTokens: Int = 6000,
)

data class ChatReply(val text: String?, val calls: List<ToolCall>, val model: String, val tokensIn: Int?, val tokensOut: Int?,
                     val finish: String?, val turn: ChatMsg.Assistant)

/** An HTTP reply: the code, the body, and the Retry-After header if any. */
class HttpReply(val code: Int, val body: String, val retryAfter: String? = null)

/** How requests go out; replaceable for tests. */
typealias Transport = suspend (method: String, url: String, headers: Map<String, String>, body: String?, timeoutMs: Int) -> HttpReply

object Http {
    val default: Transport = { method, url, headers, body, timeoutMs ->
        withContext(Dispatchers.IO) {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                c.requestMethod = method; c.connectTimeout = 10_000; c.readTimeout = timeoutMs
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                HttpReply(code, text, c.getHeaderField("Retry-After"))
            } finally { c.disconnect() }
        }
    }
}

/** Talks to one provider with one key: chat, and its model list. */
interface ChatApi {
    suspend fun chat(p: AiProvider, key: String, model: String, req: ChatRequest, timeoutMs: Int): ChatReply
    suspend fun models(p: AiProvider, key: String): List<CatalogModel>
}

/** Both kinds of provider behind one interface. */
class AiClients(private val http: Transport = Http.default) : ChatApi {
    private val openAi = OpenAiApi(http)
    private val gemini = GeminiApi(http)
    private fun of(p: AiProvider): ChatApi = if (p.kind == ProviderKind.GEMINI) gemini else openAi
    override suspend fun chat(p: AiProvider, key: String, model: String, req: ChatRequest, timeoutMs: Int) = of(p).chat(p, key, model, req, timeoutMs)
    override suspend fun models(p: AiProvider, key: String) = of(p).models(p, key)
}

/**
 * OpenAI-compatible chat completions (OpenRouter, and any other endpoint that speaks the same API). OpenRouter gets its
 * own reasoning switch and app headers; others get reasoning_effort only when reasoning is asked for. A model that must
 * reason (OpenRouter: "Reasoning is mandatory") is asked again with a little, and remembered.
 */
class OpenAiApi(private val http: Transport) : ChatApi {
    private val mustReason = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val tokenCap = java.util.concurrent.ConcurrentHashMap<String, Int>()

    override suspend fun chat(p: AiProvider, key: String, model: String, req: ChatRequest, timeoutMs: Int): ChatReply {
        val effort = req.reasoning ?: if (model in mustReason) "low" else null
        val r = tokenCap[model]?.let { req.capped(it) } ?: req
        return try { send(p, key, model, r, effort, timeoutMs) } catch (e: AiError) {
            val m = e.message.orEmpty()
            when {
                effort == null && m.contains("Reasoning is mandatory", true) -> { mustReason += model; send(p, key, model, r, "low", timeoutMs) }
                TOKEN_LIMIT.containsMatchIn(m) && maxOf(r.maxTokens, r.reasoningMaxTokens) > SAFE_TOKENS ->
                    { tokenCap[model] = SAFE_TOKENS; send(p, key, model, r.capped(SAFE_TOKENS), effort, timeoutMs) }
                else -> throw e
            }
        }
    }

    private fun ChatRequest.capped(n: Int) = copy(maxTokens = minOf(maxTokens, n), reasoningMaxTokens = minOf(reasoningMaxTokens, n))

    private companion object {
        /** A budget every chat model takes; used when one refuses Heartline's larger default. */
        const val SAFE_TOKENS = 8192
        val TOKEN_LIMIT = Regex("max_tokens|max_completion_tokens|maximum context length|max(imum)? (output|completion) tokens", RegexOption.IGNORE_CASE)
    }

    private suspend fun send(p: AiProvider, key: String, model: String, req: ChatRequest, effort: String?, timeoutMs: Int): ChatReply {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", req.system))
        req.messages.forEach { m ->
            when (m) {
                is ChatMsg.User -> messages.put(JSONObject().put("role", "user").put("content", if (m.audio == null) m.text as Any else JSONArray()
                    .put(JSONObject().put("type", "text").put("text", m.text))
                    .put(JSONObject().put("type", "input_audio").put("input_audio", JSONObject()
                        .put("data", java.util.Base64.getEncoder().encodeToString(m.audio.wav())).put("format", "wav")))))
                is ChatMsg.Assistant -> messages.put(m.openAi ?: JSONObject().put("role", "assistant").put("content", m.text ?: "").apply {
                    if (m.calls.isNotEmpty()) put("tool_calls", JSONArray(m.calls.map { c ->
                        JSONObject().put("id", c.id).put("type", "function").put("function", JSONObject().put("name", c.name).put("arguments", c.args.toString()))
                    }))
                })
                is ChatMsg.ToolResult -> messages.put(JSONObject().put("role", "tool").put("tool_call_id", m.callId).put("name", m.name).put("content", m.content))
            }
        }
        val body = JSONObject().put("model", model).put("messages", messages).put("temperature", req.temperature)
        if (req.tools.isNotEmpty()) body.put("tools", JSONArray(req.tools.map { t ->
            JSONObject().put("type", "function").put("function", JSONObject().put("name", t.name).put("description", t.description).put("parameters", t.parameters))
        }))
        if (p.isOpenRouter) {
            if (effort == null) body.put("max_tokens", req.maxTokens).put("reasoning", JSONObject().put("enabled", false))
            else body.put("max_tokens", req.reasoningMaxTokens).put("reasoning", JSONObject().put("effort", effort).put("exclude", true))
        } else {
            body.put("max_tokens", if (effort == null) req.maxTokens else req.reasoningMaxTokens)
            if (effort != null) body.put("reasoning_effort", effort)
        }
        val headers = mutableMapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json")
        if (p.isOpenRouter) { headers["HTTP-Referer"] = "https://github.com/AbhiramJayasankar/heartline-zepp-alternative"; headers["X-Title"] = "Heartline" }
        val r = try { http("POST", "${p.baseUrl}/chat/completions", headers, body.toString(), timeoutMs) } catch (e: Exception) { throw AiErrors.of(e) }
        if (r.code !in 200..299) throw AiErrors.http(p.name, r.code, r.body, r.retryAfter)
        val o = runCatching { JSONObject(r.body) }.getOrElse { throw badAnswer("${p.name} sent something that isn't JSON: ${r.body.take(120)}") }
        // A routed request can also fail inside a 200 reply.
        o.optJSONObject("error")?.let { e -> throw AiErrors.http(p.name, e.optInt("code", 502).takeIf { it in 400..599 } ?: 502, r.body) }
        val choice = o.optJSONArray("choices")?.optJSONObject(0) ?: throw badAnswer("no answer in ${p.name}'s reply")
        val message = choice.optJSONObject("message") ?: JSONObject()
        val calls = message.optJSONArray("tool_calls")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty().map { c ->
            val fn = c.optJSONObject("function") ?: JSONObject()
            ToolCall(c.optString("id").ifEmpty { "call_${System.nanoTime()}" }, fn.optString("name"),
                runCatching { JSONObject(fn.optString("arguments").ifBlank { "{}" }) }.getOrDefault(JSONObject()))
        }
        val text = message.optString("content").takeIf { !message.isNull("content") && it.isNotBlank() }
        // The model's turn goes back as it came (some providers need their reasoning details to continue).
        val echo = JSONObject().put("role", "assistant").put("content", message.opt("content")?.takeIf { it != JSONObject.NULL } ?: "").apply {
            message.optJSONArray("tool_calls")?.let { put("tool_calls", it) }
            message.optJSONArray("reasoning_details")?.let { put("reasoning_details", it) }
        }
        val usage = o.optJSONObject("usage")
        return ChatReply(text, calls, o.optString("model").ifEmpty { model }, usage?.optInt("prompt_tokens", -1)?.takeIf { it >= 0 },
            usage?.optInt("completion_tokens", -1)?.takeIf { it >= 0 }, choice.optString("finish_reason").ifEmpty { null },
            ChatMsg.Assistant(text, calls, openAi = echo))
    }

    override suspend fun models(p: AiProvider, key: String): List<CatalogModel> {
        val r = try { http("GET", "${p.baseUrl}/models", mapOf("Authorization" to "Bearer $key"), null, 30_000) } catch (e: Exception) { throw AiErrors.of(e) }
        if (r.code !in 200..299) throw AiErrors.http(p.name, r.code, r.body, r.retryAfter)
        val a = runCatching { JSONObject(r.body).optJSONArray("data") }.getOrNull() ?: throw badAnswer("${p.name}'s model list isn't in the usual shape")
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { m ->
            val id = m.optString("id").ifEmpty { return@mapNotNull null }
            val params = m.optJSONArray("supported_parameters")?.let { x -> (0 until x.length()).map { x.optString(it) } }
            val inputs = m.optJSONObject("architecture")?.optJSONArray("input_modalities")?.let { x -> (0 until x.length()).map { x.optString(it) } }
            val pricing = m.optJSONObject("pricing")
            val free = pricing?.let { pr -> pr.optString("prompt") in setOf("0", "0.0") && pr.optString("completion") in setOf("0", "0.0") } ?: id.endsWith(":free").takeIf { it }
            CatalogModel(ModelRef(p.id, id), m.optString("name").ifEmpty { id }, m.optInt("context_length", -1).takeIf { it > 0 },
                params?.let { "tools" in it }, inputs?.let { "audio" in it }, false, free)
        }.sortedBy { it.ref.model }
    }
}

/**
 * Gemini's generateContent. Tool calls come back as functionCall parts with thought signatures; the model's whole turn
 * is kept and sent back as it came. A call that another provider's model made (after a fallback) gets the documented
 * placeholder signature, since Gemini 3 checks them.
 */
class GeminiApi(private val http: Transport) : ChatApi {
    override suspend fun chat(p: AiProvider, key: String, model: String, req: ChatRequest, timeoutMs: Int): ChatReply {
        val name = model.removePrefix("models/")
        val contents = JSONArray()
        var pendingResults: JSONArray? = null
        fun flushResults() { pendingResults?.let { contents.put(JSONObject().put("role", "user").put("parts", it)) }; pendingResults = null }
        req.messages.forEach { m ->
            if (m !is ChatMsg.ToolResult) flushResults()
            when (m) {
                is ChatMsg.User -> contents.put(JSONObject().put("role", "user").put("parts", JSONArray().apply {
                    if (m.text.isNotBlank()) put(JSONObject().put("text", m.text))
                    m.audio?.let { a -> put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav").put("data", java.util.Base64.getEncoder().encodeToString(a.wav())))) }
                }))
                is ChatMsg.Assistant -> contents.put(m.gemini ?: JSONObject().put("role", "model").put("parts", JSONArray().apply {
                    m.text?.takeIf { it.isNotBlank() }?.let { put(JSONObject().put("text", it)) }
                    m.calls.forEach { c -> put(JSONObject().put("functionCall", JSONObject().put("name", c.name).put("args", c.args))
                        .put("thoughtSignature", c.signature ?: "skip_thought_signature_validator")) }
                }))
                is ChatMsg.ToolResult -> (pendingResults ?: JSONArray().also { pendingResults = it })
                    .put(JSONObject().put("functionResponse", JSONObject().put("name", m.name).put("response", JSONObject().put("result", m.content))))
            }
        }
        flushResults()
        val config = JSONObject().put("temperature", req.temperature).put("maxOutputTokens", if (req.reasoning == null) req.maxTokens else req.reasoningMaxTokens)
        if (req.json && req.tools.isEmpty()) config.put("responseMimeType", "application/json")
        if (req.reasoning == "low" || req.reasoning == "high") config.put("thinkingConfig", JSONObject().put("thinkingLevel", req.reasoning))
        val body = JSONObject().put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", req.system))))
            .put("contents", contents).put("generationConfig", config)
        if (req.tools.isNotEmpty()) body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray(req.tools.map { t ->
            JSONObject().put("name", t.name).put("description", t.description).put("parameters", schema(t.parameters))
        }))))
        val r = try { http("POST", "${p.baseUrl}/models/$name:generateContent", mapOf("x-goog-api-key" to key, "Content-Type" to "application/json"), body.toString(), timeoutMs) }
            catch (e: Exception) { throw AiErrors.of(e) }
        if (r.code !in 200..299) throw AiErrors.http(p.name, r.code, r.body, r.retryAfter)
        val o = runCatching { JSONObject(r.body) }.getOrElse { throw badAnswer("${p.name} sent something that isn't JSON") }
        val candidate = o.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw (o.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotEmpty() }
                ?.let { AiError("${p.name} blocked the request ($it)", FailKind.REQUEST) } ?: badAnswer("$name gave no answer"))
        val content = candidate.optJSONObject("content") ?: JSONObject().put("role", "model").put("parts", JSONArray())
        val parts = content.optJSONArray("parts") ?: JSONArray()
        val list = (0 until parts.length()).mapNotNull { parts.optJSONObject(it) }
        // Thought summaries, if any, are marked "thought"; the answer is the rest.
        val text = list.filter { !it.optBoolean("thought") && it.has("text") }.joinToString("") { it.optString("text") }.trim().ifEmpty { null }
        val calls = list.filter { it.has("functionCall") }.mapIndexed { i, part ->
            val fc = part.getJSONObject("functionCall")
            ToolCall(fc.optString("id").ifEmpty { "gemini_${i}_${System.nanoTime()}" }, fc.optString("name"), fc.optJSONObject("args") ?: JSONObject(),
                part.optString("thoughtSignature").ifEmpty { null })
        }
        val usage = o.optJSONObject("usageMetadata")
        val finish = candidate.optString("finishReason").ifEmpty { null }
        return ChatReply(text, calls, o.optString("modelVersion").ifEmpty { name }, usage?.optInt("promptTokenCount"), usage?.optInt("candidatesTokenCount"),
            finish?.let { if (it == "MAX_TOKENS") "length" else it.lowercase() }, ChatMsg.Assistant(text, calls, gemini = content.put("role", "model")))
    }

    override suspend fun models(p: AiProvider, key: String): List<CatalogModel> {
        val out = ArrayList<CatalogModel>()
        var page: String? = null
        do {
            val url = "${p.baseUrl}/models?pageSize=1000" + (page?.let { "&pageToken=$it" } ?: "")
            val r = try { http("GET", url, mapOf("x-goog-api-key" to key), null, 30_000) } catch (e: Exception) { throw AiErrors.of(e) }
            if (r.code !in 200..299) throw AiErrors.http(p.name, r.code, r.body, r.retryAfter)
            val o = JSONObject(r.body)
            val a = o.optJSONArray("models") ?: JSONArray()
            for (i in 0 until a.length()) {
                val m = a.optJSONObject(i) ?: continue
                val methods = m.optJSONArray("supportedGenerationMethods")?.let { x -> (0 until x.length()).map { x.optString(it) } }.orEmpty()
                val id = m.optString("name").removePrefix("models/")
                val live = "bidiGenerateContent" in methods
                if (id.isEmpty() || ("generateContent" !in methods && !live)) continue
                // Embedding, image and speech-out models list generateContent too but don't answer in text.
                if (listOf("embedding", "imagen", "-tts", "image-generation", "veo").any { it in id }) continue
                out += CatalogModel(ModelRef(p.id, id), m.optString("displayName").ifEmpty { id }, m.optInt("inputTokenLimit", -1).takeIf { it > 0 },
                    tools = if (live && "generateContent" !in methods) false else !id.startsWith("gemma"), audio = id.startsWith("gemini"),
                    live = live && "generateContent" !in methods, free = null)
            }
            page = o.optString("nextPageToken").ifEmpty { null }
        } while (page != null)
        return out.sortedByDescending { it.ref.model }
    }

    companion object {
        private val KEEP = setOf("type", "description", "properties", "items", "enum", "required", "nullable", "format", "minimum", "maximum",
            "minItems", "maxItems", "anyOf")
        /** The tool's JSON Schema, cut to what Gemini's function declarations take. */
        fun schema(s: JSONObject): JSONObject {
            val out = JSONObject()
            s.keys().forEach { k ->
                if (k !in KEEP) return@forEach
                when (val v = s.get(k)) {
                    is JSONObject -> out.put(k, if (k == "properties") JSONObject().apply { v.keys().forEach { p -> put(p, schema(v.getJSONObject(p))) } } else schema(v))
                    is JSONArray -> out.put(k, if (k == "anyOf") JSONArray((0 until v.length()).map { schema(v.getJSONObject(it)) }) else v)
                    else -> out.put(k, v)
                }
            }
            return out
        }
    }
}
