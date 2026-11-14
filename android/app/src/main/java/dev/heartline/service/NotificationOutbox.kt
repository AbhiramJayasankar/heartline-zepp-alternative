package dev.heartline.service

import dev.heartline.ble.rethrowIfCancelled
import dev.heartline.protocol.NotificationKind
import dev.heartline.protocol.WatchIcon
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class WatchNotification(val title: String, val body: String, val kind: NotificationKind = NotificationKind.MESSAGE,
                             val app: String = "Heartline", val icon: Int = WatchIcon.GENERIC)

/** Application-owned FIFO: reconnects and new Alexa turns must not discard a partly delivered answer. The age is
 * monotonic; a bounded queue avoids replaying an evening's messages the next morning. Only the failed part retries. */
class NotificationOutbox(private val clock: () -> Long, private val available: () -> Boolean,
                         private val send: suspend (WatchNotification, () -> Boolean) -> Unit,
                         private val log: suspend (String, String) -> Unit, private val capacity: Int = 200) {
    private class Pending(val message: WatchNotification, val expires: Long, var attempts: Int = 0)
    private val lock = Mutex()
    private val consumer = Mutex()
    private val pending = ArrayList<Pending>()
    private val waiting = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Suspends until something is queued, so the delivery loop sleeps instead of polling an empty queue all day. */
    suspend fun awaitWork() { waiting.first { it > 0 } }

    suspend fun enqueue(messages: List<WatchNotification>, ageMs: Long = 10 * 60_000L): Int {
        val accepted = lock.withLock {
            if (pending.size + messages.size > capacity) false
            else { val until = clock() + ageMs; pending.addAll(messages.map { Pending(it, until) }); waiting.value = pending.size; true }
        }
        if (!accepted) log("warn", "Dropped ${messages.size} watch notification(s): delivery queue full ($capacity)")
        else if (messages.isNotEmpty()) log("info", "Queued ${messages.size} watch notification(s) for delivery")
        return if (accepted) messages.size else 0
    }

    /** One consumer step; false means disconnected/busy/empty. The service supplies a fresh client inside its lock. */
    suspend fun deliverNext(): Boolean = consumer.withLock {
        val expired = lock.withLock {
            val old = pending.filter { clock() >= it.expires }; pending.removeAll(old.toSet()); waiting.value = pending.size; old.size
        }
        if (expired > 0) log("warn", "Dropped $expired watch notification(s): delivery age limit expired")
        val p = lock.withLock { pending.firstOrNull() } ?: return@withLock false
        if (!available()) return@withLock false
        try {
            send(p.message) { clock() < p.expires }
            lock.withLock { pending.remove(p); waiting.value = pending.size }
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            val reason = if (clock() >= p.expires) "delivery age limit expired" else if (++p.attempts >= 2) "write failed twice: ${e.message}" else null
            if (reason != null) {
                lock.withLock { pending.remove(p); waiting.value = pending.size }
                log("warn", "Dropped watch notification: $reason")
            } else log("warn", "Watch notification write failed; queued for one retry: ${e.message}")
        }
        true
    }
}
