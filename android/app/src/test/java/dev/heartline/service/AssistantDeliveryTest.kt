package dev.heartline.service

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AssistantDeliveryTest {
    private var clock = 0L
    private var current = true
    private val events = mutableListOf<String>()
    private suspend fun deliver(compute: suspend () -> String = { "answer" }, waiting: suspend () -> Unit = { events += "waiting" },
                                screen: suspend (String) -> Unit = { events += "screen:$it" }) {
        AssistantDelivery(0, 5, { clock }, { current }).run(compute, waiting, screen,
            { events += "notification:$it" }, { events += "failure" })
    }
    @Test fun quickAnswerUsesScreenOnce() = runBlocking {
        deliver(); assertEquals(listOf("screen:answer"), events)
    }
    @Test fun expiredScreenUsesNotificationEvenIfComputationDidNotSuspend() = runBlocking {
        deliver({ clock = 49_200; "answer" }); assertEquals(listOf("notification:answer"), events)
    }
    @Test fun newTurnOrClosedSessionCannotReceiveOldAnswer() = runBlocking {
        deliver({ current = false; "old answer" }); assertEquals(listOf("notification:old answer"), events)
    }
    @Test fun screenWriteFailureFallsBackOnce() = runBlocking {
        deliver(screen = { error("disconnected") }); assertEquals(listOf("failure", "notification:answer"), events)
    }
    @Test fun failedThinkingHandoffDoesNotLoseModelWork() = runBlocking {
        clock = 4999
        deliver({ delay(30); clock = 6000; "answer" }, waiting = { error("disconnected") })
        assertEquals(listOf("failure", "notification:answer"), events)
    }
    @Test fun slowAnswerUsesHandoffThenNotification() = runBlocking {
        clock = 4999
        deliver({ delay(30); clock = 6000; "answer" })
        assertEquals(listOf("waiting", "notification:answer"), events)
    }
    @Test fun waitIsCappedBelowWatchTimeoutAndDeadlineIsExclusive() {
        val d = AssistantDelivery(0, 99, { clock }, { current })
        clock = 24999; assertTrue(d.canReply()); clock = 25000; assertFalse(d.canReply())
        clock = 0; current = false; assertFalse(d.canReply())
    }
    @Test fun serviceCancellationStopsWorkWithoutFallback() = runBlocking {
        val job = launch(start = CoroutineStart.UNDISPATCHED) { deliver({ awaitCancellation() }) }
        job.cancelAndJoin(); assertTrue(events.isEmpty())
    }
}
