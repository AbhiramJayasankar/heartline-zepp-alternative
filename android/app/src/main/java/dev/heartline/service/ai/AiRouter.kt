package dev.heartline.service.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Who a request goes to: [level] 0 is the section's main model, 1 and 2 its fallbacks; [keyIndex] counts from 0. */
data class AiTarget(val provider: AiProvider, val model: String, val key: String, val keyIndex: Int, val level: Int, val timeoutMs: Int) {
    val ref get() = ModelRef(provider.id, model)
}

/** One request and how it went, for the activity log. [keyEnd] is the key's last 4 characters, never the key. */
data class AiAttempt(val at: Long, val section: String, val provider: String, val model: String, val keyIndex: Int, val keyEnd: String, val level: Int,
                     val ms: Long, val kind: FailKind? = null, val error: String? = null, val waitedMs: Long = 0, val note: String? = null) {
    val ok get() = kind == null && error == null && note == null

    fun json(): JSONObject = JSONObject().put("at", at).put("section", section).put("provider", provider).put("model", model).put("key", keyIndex)
        .put("key_end", keyEnd).put("level", level).put("ms", ms).put("kind", kind?.name ?: "").put("error", error ?: "").put("waited_ms", waitedMs)
        .put("note", note ?: "")

    companion object {
        const val KEY = "ai_activity"
        const val KEEP = 200
        fun parse(o: JSONObject) = AiAttempt(o.optLong("at"), o.optString("section"), o.optString("provider"), o.optString("model"), o.optInt("key"),
            o.optString("key_end"), o.optInt("level"), o.optLong("ms"), runCatching { FailKind.valueOf(o.optString("kind")) }.getOrNull(),
            o.optString("error").ifEmpty { null }, o.optLong("waited_ms"), o.optString("note").ifEmpty { null })
        fun parseAll(text: String?): List<AiAttempt> = runCatching {
            val a = JSONArray(text ?: "[]"); (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::parse) }
        }.getOrDefault(emptyList())
        /** [old] with [new] in front, newest first, [KEEP] long. */
        fun added(old: String?, new: List<AiAttempt>) = JSONArray((new.reversed() + parseAll(old)).take(KEEP).map { it.json() }).toString()
    }
}

class AiOutcome<T>(val value: T, val target: AiTarget, val attempts: List<AiAttempt>)

/** No model in the chain answered; [attempts] says what each request got. */
class AiFailed(val section: AiSection, val attempts: List<AiAttempt>) : Exception(
    attempts.lastOrNull { it.error != null }?.let { a -> "${a.model}: ${a.error}" }
        ?: attempts.lastOrNull()?.note ?: "no model is set for ${section.title} (choose one under AI models)") {
    /** The last failure's kind, to word what the user sees. */
    val kind get() = attempts.lastOrNull { it.kind != null }?.kind
}

/**
 * Runs a request for a section down its chain: the main model, then fallback 1, then fallback 2, each through its
 * provider's keys, following the section's [RetryPolicy]. A key that ran out of quota (or was refused) for a model rests
 * for that model for a while ([QUOTA_REST_MS]) so the next request doesn't spend time on it; the key that worked last is
 * tried first.
 */
class AiRouter(
    private val setup: suspend () -> AiSetup,
    private val record: suspend (List<AiAttempt>) -> Unit = {},
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val resting = ConcurrentHashMap<String, Long>()
    private val lastGood = ConcurrentHashMap<String, String>()

    /** Keys rest per model: Gemini's quotas are per model, and a paid OpenRouter model still works when the free ones are spent. */
    private fun keyId(p: AiProvider, key: String, model: String) = "${p.id}|${key.hashCode()}|$model"

    /** When [key] can be used for [model] again, if it is resting. */
    fun restingUntil(p: AiProvider, key: String, model: String): Long? = resting[keyId(p, key, model)]?.takeIf { it > clock() }

    /**
     * [from] starts further down the chain (a conversation that already fell back stays there). [deadlineMs] (a clock
     * time) stops trying once passed. [block] makes the request; throw [AiError] (or [badAnswer]) to have it sorted.
     */
    suspend fun <T> run(section: AiSection, from: Int = 0, deadlineMs: Long? = null, block: suspend (AiTarget) -> T): AiOutcome<T> {
        val s = setup()
        val sec = s.section(section)
        val pol = sec.policy
        val attempts = ArrayList<AiAttempt>()
        try {
            val seen = HashSet<ModelRef>()
            val levels = (0..2).mapNotNull { l -> sec.at(l)?.takeIf { seen.add(it) }?.let { l to it } }.let { all -> all.filter { it.first >= from }.ifEmpty { all } }
            for ((level, ref) in levels) {
                fun note(n: String) { attempts += AiAttempt(clock(), section.key, ref.provider, ref.model, -1, "", level, 0, note = n) }
                val p = s.provider(ref.provider) ?: run { note("provider ${ref.provider} was removed"); null } ?: continue
                if (p.keys.isEmpty()) { note("no key for ${p.name}"); continue }
                val now = clock()
                val ordered = p.keys.withIndex().sortedBy { if (it.value == lastGood[p.id]) 0 else 1 }
                val awake = ordered.filter { (resting[keyId(p, it.value, ref.model)] ?: 0) <= now }
                val keys = (if (pol.otherKeys) awake else awake.take(1))
                if (keys.isEmpty()) { note("every ${p.name} key is resting for this model after running out of quota"); continue }
                var k = 0; var busy = 0; var bad = 0; var waited = 0L
                while (k < keys.size) {
                    if (deadlineMs != null && clock() > deadlineMs) throw AiFailed(section, attempts)
                    val (index, key) = keys[k]
                    val target = AiTarget(p, ref.model, key, index, level, pol.timeoutS * 1000)
                    val t0 = clock()
                    val err: AiError = try {
                        val v = withTimeout(pol.timeoutS * 1000L) { block(target) }
                        attempts += AiAttempt(t0, section.key, p.id, ref.model, index, key.takeLast(4), level, clock() - t0, waitedMs = waited)
                        lastGood[p.id] = key
                        return AiOutcome(v, target, attempts)
                    } catch (e: TimeoutCancellationException) { AiError("no answer within ${pol.timeoutS} s", FailKind.TIMEOUT) }
                    catch (e: Exception) { dev.heartline.ble.rethrowIfCancelled(e); AiErrors.of(e) }
                    attempts += AiAttempt(t0, section.key, p.id, ref.model, index, key.takeLast(4), level, clock() - t0, err.kind, err.message, waited)
                    waited = 0
                    fun nextKey(restMs: Long): Boolean {
                        resting[keyId(p, key, ref.model)] = clock() + restMs
                        k++; busy = 0; bad = 0
                        return k < keys.size
                    }
                    val goOn = when (err.kind) {
                        FailKind.QUOTA, FailKind.AUTH -> nextKey(QUOTA_REST_MS)
                        FailKind.RATE_LIMIT -> {
                            val after = err.retryAfterMs
                            if (after != null && after <= pol.waitForLimitS * 1000L && busy < maxOf(pol.busyRetries, 1)) {
                                busy++; waited = after + 500; pause(waited); true
                            } else nextKey(after ?: RATE_REST_MS)
                        }
                        FailKind.BUSY, FailKind.TIMEOUT, FailKind.NETWORK ->
                            if (busy < pol.busyRetries) { busy++; waited = pol.waitFor(busy); pause(waited); true } else false
                        FailKind.BAD_ANSWER -> if (bad < pol.badAnswerRetries) { bad++; true } else false
                        FailKind.MODEL, FailKind.CONTEXT, FailKind.REQUEST -> false
                    }
                    if (!goOn) break
                }
            }
            throw AiFailed(section, attempts)
        } finally {
            if (attempts.isNotEmpty()) runCatching { record(attempts.toList()) }
        }
    }

    companion object {
        /** How long a key that ran out of quota (or was refused) is left alone. */
        const val QUOTA_REST_MS = 30 * 60_000L
        /** How long a rate-limited key is left alone when the provider doesn't say. */
        const val RATE_REST_MS = 60_000L
    }
}
