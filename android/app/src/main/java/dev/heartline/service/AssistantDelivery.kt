package dev.heartline.service

import dev.heartline.ble.rethrowIfCancelled
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** The watch's screen expires independently of model work. A failed handoff or a new session must not lose the
 * answer; it still goes to the outbox. All screen writes check ownership and the monotonic deadline again. */
internal class AssistantDelivery(private val stoppedAt: Long, waitS: Int, private val clock: () -> Long,
                                 private val current: () -> Boolean) {
    private val deadline = stoppedAt + waitS.coerceIn(5, 25) * 1000L
    fun canReply() = current() && clock() < deadline

    suspend fun <T : Any> run(compute: suspend () -> T, waiting: suspend () -> Unit, screen: suspend (T) -> Unit,
                              notification: suspend (T) -> Unit, failure: suspend (Exception) -> Unit) = coroutineScope {
        val work = async { compute() }
        val quick = withTimeoutOrNull((deadline - clock()).coerceAtLeast(1)) { work.await() }
        if (quick == null && current()) {
            // The handoff text is useful only while the watch is still waiting. Leave a small write margin.
            if (clock() < stoppedAt + 28_000) try { waiting() }
            catch (e: Exception) { rethrowIfCancelled(e); failure(e) }
        }
        val result = quick ?: work.await()
        if (quick != null && canReply()) {
            try { screen(result); return@coroutineScope }
            catch (e: Exception) { rethrowIfCancelled(e); failure(e) }
        }
        notification(result)
    }
}
