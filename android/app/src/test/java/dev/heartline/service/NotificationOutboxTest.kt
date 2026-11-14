package dev.heartline.service

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NotificationOutboxTest {
    private var now = 0L
    private var ready = false
    private val sent = mutableListOf<String>()
    private val logs = mutableListOf<String>()
    private fun queue(capacity: Int = 200, write: suspend (WatchNotification, () -> Boolean) -> Unit = { n, valid ->
        check(valid()); sent += n.body
    }) = NotificationOutbox({ now }, { ready }, write, { _, m -> logs += m }, capacity)
    private fun messages(vararg bodies: String) = bodies.map { WatchNotification("Example", it) }

    @Test fun disconnectedAndBusyWaitThenDeliverBatchesInOrder() = runBlocking {
        val q = queue(); q.enqueue(messages("one", "two")); q.enqueue(messages("three"))
        assertFalse(q.deliverNext()); assertTrue(sent.isEmpty())
        now = 5000; ready = true; repeat(3) { assertTrue(q.deliverNext()) }
        assertEquals(listOf("one", "two", "three"), sent); assertFalse(q.deliverNext())
    }
    @Test fun failedMiddlePartRetriesOnceWithoutRepeatingEarlierParts() = runBlocking {
        var attempts = 0
        val q = queue { n, _ -> if (n.body == "two" && attempts++ == 0) error("link lost") else sent += n.body }
        q.enqueue(messages("one", "two", "three")); ready = true
        repeat(4) { q.deliverNext() }
        assertEquals(listOf("one", "two", "three"), sent); assertEquals(2, attempts)
        assertTrue(logs.any { "one retry" in it })
    }
    @Test fun twiceFailedPartIsLoggedAndDoesNotDiscardRemainingParts() = runBlocking {
        val q = queue { n, _ -> if (n.body == "two") error("write rejected") else sent += n.body }
        q.enqueue(messages("one", "two", "three")); ready = true; repeat(4) { q.deliverNext() }
        assertEquals(listOf("one", "three"), sent); assertTrue(logs.any { "write failed twice" in it })
    }
    @Test fun ageLimitDropsEvenWhileDisconnectedAtExactBoundary() = runBlocking {
        val q = queue(); q.enqueue(messages("old"), 100); now = 100
        assertFalse(q.deliverNext()); ready = true; assertFalse(q.deliverNext())
        assertTrue(logs.any { "age limit expired" in it }); assertTrue(sent.isEmpty())
    }
    @Test fun capacityRejectsWholeBatchAndLogsReason() = runBlocking {
        val q = queue(2); assertEquals(1, q.enqueue(messages("one")))
        assertEquals(0, q.enqueue(messages("two", "three"))); ready = true; q.deliverNext()
        assertEquals(listOf("one"), sent); assertTrue(logs.any { "queue full" in it })
    }
    @Test fun expiryIsCheckedAgainAfterWaitingForCommandLock() = runBlocking {
        val q = queue { n, valid -> now = 100; check(valid()); sent += n.body }
        q.enqueue(messages("old"), 100); ready = true; q.deliverNext()
        assertTrue(sent.isEmpty()); assertTrue(logs.any { "age limit expired" in it })
    }
    @Test fun cancellationPreservesPendingPartWithoutConsumingRetry() = runBlocking {
        var block = true
        val q = queue { n, _ -> if (block) awaitCancellation() else sent += n.body }
        q.enqueue(messages("one")); ready = true
        val job = launch(start = CoroutineStart.UNDISPATCHED) { q.deliverNext() }; job.cancelAndJoin()
        block = false; q.deliverNext(); assertEquals(listOf("one"), sent)
        assertFalse(logs.any { "retry" in it || "Dropped" in it })
    }
    @Test fun operationTimeoutRetriesInsteadOfKillingWorker() = runBlocking {
        var attempts = 0
        val q = queue { n, _ -> if (attempts++ == 0) withTimeout(1) { awaitCancellation() } else sent += n.body }
        q.enqueue(messages("one")); ready = true; q.deliverNext(); q.deliverNext()
        assertEquals(listOf("one"), sent); assertEquals(2, attempts)
    }
}
