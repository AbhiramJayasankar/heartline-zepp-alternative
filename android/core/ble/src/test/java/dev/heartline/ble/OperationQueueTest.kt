package dev.heartline.ble

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OperationQueueTest {
    @Test fun operationsCompleteInOrderAndNeverOverlap() = runBlocking {
        val queue = OperationQueue { fail("No invalidation expected") }; var running = 0
        val order = mutableListOf<Int>()
        val jobs = (0..9).map { n -> launch(start = CoroutineStart.UNDISPATCHED) {
            queue.run(2000) { assertEquals(0, running++); delay(5); order.add(n); assertEquals(1, running--) }
        } }
        jobs.joinAll(); assertEquals((0..9).toList(), order)
    }
    @Test fun timeoutInvalidatesExactlyOnce() = runBlocking {
        var invalidated = 0; val queue = OperationQueue { invalidated++ }
        try { queue.run(20) { awaitCancellation() }; fail("Expected timeout") } catch (_: TimeoutCancellationException) { }
        assertEquals(1, invalidated)
    }
    @Test fun cancellingQueuedRequestDoesNotInvalidateActiveOperation() = runBlocking {
        var invalidated = 0; val queue = OperationQueue { invalidated++ }; val release = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { queue.run(2000) { release.await() } }
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) { queue.run(2000) { fail("Cancelled request must not start") } }
        waiting.cancelAndJoin(); assertEquals(0, invalidated); release.complete(Unit); first.join()
    }
    @Test fun cancellingActiveOperationInvalidatesLink() = runBlocking {
        var invalidated = 0; val queue = OperationQueue { invalidated++ }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { queue.run(2000) { awaitCancellation() } }
        job.cancelAndJoin(); assertEquals(1, invalidated)
    }
    @Test fun completedRejectionDoesNotPoisonQueue() = runBlocking {
        var invalidated = 0; val queue = OperationQueue { invalidated++ }
        try { queue.run(1000) { error("ATT rejected") }; fail() } catch (_: IllegalStateException) { }
        assertEquals(0, invalidated)
        assertEquals(42, queue.run(1000) { 42 })
    }
}
