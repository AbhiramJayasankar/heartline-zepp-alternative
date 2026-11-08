package dev.heartline.service.ai

import org.json.JSONObject

/** Why a request failed, which decides what the router does next (see [RetryPolicy]). */
enum class FailKind(val label: String) {
    /** The key's quota or credits are used up (per day, per month, or no credits left): another key, then another model. */
    QUOTA("Out of quota"),
    /** The key was refused (wrong, revoked, no permission): another key, then another model. */
    AUTH("Key refused"),
    /** Too many requests for now; often says when to come back. */
    RATE_LIMIT("Rate limited"),
    /** The provider or model is overloaded or had a server error. */
    BUSY("Busy"),
    TIMEOUT("Timed out"),
    NETWORK("No connection"),
    /** The model doesn't exist here, or can't do what was asked (tools, audio). */
    MODEL("Model can't do it"),
    /** The request is too long for the model. */
    CONTEXT("Too long for the model"),
    /** Something else about the request was refused. */
    REQUEST("Request refused"),
    /** The model answered, but not usably (empty, cut off, wrong shape). */
    BAD_ANSWER("Unusable answer");

    /** Failures where the next key of the same provider may work. */
    val keyBound get() = this == QUOTA || this == AUTH || this == RATE_LIMIT
}

/** A failed request, sorted. [retryAfterMs] is when the provider said to come back, if it did. */
class AiError(message: String, val kind: FailKind, val status: Int? = null, val retryAfterMs: Long? = null) : Exception(message)

/** The model answered but the answer can't be used; [AiRouter] asks again or moves on per the section's rules. */
fun badAnswer(message: String) = AiError(message, FailKind.BAD_ANSWER)

object AiErrors {
    /**
     * Sorts an HTTP failure from either kind of provider. [body] is the error reply; its message is kept short (Gemini's
     * quota messages are long and full of links). Keys never appear in the message.
     */
    fun http(provider: String, status: Int, body: String, retryAfterHeader: String? = null): AiError {
        val o = runCatching { JSONObject(body) }.getOrNull()
        val err = o?.optJSONObject("error")
        val raw = err?.optJSONObject("metadata")?.optString("raw")?.takeIf { it.isNotBlank() }
            ?.let { r -> runCatching { JSONObject(r).optJSONObject("error")?.optString("message") }.getOrNull() ?: r }
        val message = listOfNotNull(err?.optString("message")?.takeIf { it.isNotBlank() }, raw?.take(200)).distinct().joinToString(": ")
            .ifBlank { body.take(200) }
        val status2 = err?.optString("status").orEmpty()
        // Gemini: details[] with RetryInfo.retryDelay "23s" and QuotaFailure violations naming the quota (…PerDay…).
        val details = err?.optJSONArray("details")
        var delay: Long? = null; var perDay = false
        if (details != null) for (i in 0 until details.length()) {
            val d = details.optJSONObject(i) ?: continue
            d.optString("retryDelay").takeIf { it.endsWith("s") }?.removeSuffix("s")?.toDoubleOrNull()?.let { delay = (it * 1000).toLong() }
            d.optJSONArray("violations")?.let { v -> for (j in 0 until v.length()) if (v.optJSONObject(j)?.optString("quotaId").orEmpty().contains("PerDay", true)) perDay = true }
        }
        val header = retryAfterHeader?.trim()?.toDoubleOrNull()?.let { (it * 1000).toLong() }
        return sort("$provider $status: ${shorten(message)}", status, message + " " + status2, delay ?: header ?: inlineDelay(message), perDay)
    }

    /** Sorts a failure that only has words (a WebSocket close, an exception from elsewhere). */
    fun text(message: String): AiError = sort(message, Regex("""\b([1-5]\d\d)\b""").find(message)?.value?.toIntOrNull(), message, inlineDelay(message), false)

    /** Sorts any exception from a request. */
    fun of(e: Throwable): AiError = when (e) {
        is AiError -> e
        is java.net.SocketTimeoutException, is kotlinx.coroutines.TimeoutCancellationException -> AiError("timed out", FailKind.TIMEOUT)
        is java.net.UnknownHostException, is java.net.ConnectException, is javax.net.ssl.SSLException -> AiError("no connection (${e.message})", FailKind.NETWORK)
        is java.io.IOException -> AiError(e.message ?: "connection failed", FailKind.NETWORK)
        else -> text(e.message ?: e.toString())
    }

    private fun sort(shown: String, status: Int?, text: String, retryMs: Long?, perDay: Boolean): AiError {
        val t = text.lowercase()
        val daily = perDay || listOf("per day", "perday", "per-day", "daily", "free-models-per-day", "per month", "monthly").any { it in t }
        val kind = when {
            "reasoning is mandatory" in t -> FailKind.REQUEST
            status == 402 || "insufficient credits" in t || "insufficient_quota" in t || "credit" in t && "add" in t -> FailKind.QUOTA
            status == 401 || status == 403 || "api key not valid" in t || "api_key_invalid" in t || "invalid api key" in t || "permission_denied" in t ||
                "unauthorized" in t || "no auth credentials" in t || "user not found" in t -> FailKind.AUTH
            status == 429 || "resource_exhausted" in t || "rate limit" in t || "rate-limited" in t || "too many requests" in t || "quota" in t ->
                // A per-minute limit says when to come back and clears soon; a daily one doesn't.
                if (daily || ("quota" in t && retryMs == null && "minute" !in t)) FailKind.QUOTA else FailKind.RATE_LIMIT
            "context length" in t || "context window" in t || "maximum context" in t || "too many tokens" in t || "input token count" in t ||
                "prompt is too long" in t -> FailKind.CONTEXT
            status == 404 || "not found" in t || "no endpoints found" in t || "does not support" in t || "is not supported" in t ||
                "not supported for" in t || "unsupported" in t || "no allowed providers" in t -> FailKind.MODEL
            status == 408 || "timed out" in t || "timeout" in t || "deadline" in t -> FailKind.TIMEOUT
            status != null && status >= 500 || "overloaded" in t || "high demand" in t || "unavailable" in t || "internal error" in t ||
                "try again later" in t -> FailKind.BUSY
            "unable to resolve host" in t || "failed to connect" in t || "connection reset" in t || "no connection" in t -> FailKind.NETWORK
            status != null && status in 400..499 -> FailKind.REQUEST
            else -> FailKind.BUSY
        }
        return AiError(shown, kind, status, retryMs)
    }

    /** "Please retry in 23.5s" / "retry after 10 seconds" in the words. */
    private fun inlineDelay(m: String): Long? = Regex("""retry(?:\s+(?:in|after))?\s+(\d+(?:\.\d+)?)\s*(s|sec|seconds)\b""", RegexOption.IGNORE_CASE)
        .find(m)?.groupValues?.get(1)?.toDoubleOrNull()?.let { (it * 1000).toLong() }

    /** The first sentence or so: the API's words, without the documentation links. */
    private fun shorten(m: String): String = m.replace(Regex("""https?://\S+"""), "").replace(Regex("""\s+"""), " ").trim().let {
        if (it.length <= 220) it else it.take(217).trimEnd() + "…"
    }
}
