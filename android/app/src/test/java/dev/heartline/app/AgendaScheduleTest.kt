package dev.heartline.app

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class AgendaScheduleTest {
    @Test fun connectionReplaysAlarmsAndChangesDoNotForceReplay() = runBlocking {
        withTimeout(5000) {
            val ready = MutableSharedFlow<Boolean>()
            val changes = Channel<Unit>(Channel.CONFLATED)
            val calls = Channel<Boolean>(Channel.UNLIMITED)
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                runAgendaSchedule(ready, changes) { force -> calls.send(force); AgendaRefresh(true, true) }
            }
            try {
                ready.subscriptionCount.firstPositive()
                assertFalse(calls.tryReceive().isSuccess)
                ready.emit(true); assertTrue(calls.receive())
                changes.send(Unit); assertFalse(calls.receive())
                ready.emit(false); yield()
                ready.emit(true); assertTrue(calls.receive())
            } finally { job.cancelAndJoin() }
        }
    }
    @Test fun connectedTimerRefreshesWithoutProviderChanges() = runBlocking {
        withTimeout(5000) {
            val changes = Channel<Unit>(Channel.CONFLATED)
            val ready = MutableSharedFlow<Boolean>()
            val calls = Channel<Boolean>(Channel.UNLIMITED)
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                runAgendaSchedule(ready, changes, intervalMillis = 20) { force -> calls.send(force); AgendaRefresh(true, true) }
            }
            try {
                ready.subscriptionCount.firstPositive()
                ready.emit(true); assertTrue(calls.receive()); assertFalse(calls.receive())
            } finally { job.cancelAndJoin() }
        }
    }
    @Test fun failuresRetryAndOnlyFailedAlarmReplayStaysForced() = runBlocking {
        withTimeout(5000) {
            for (alarmsOk in listOf(true, false)) {
                val changes = Channel<Unit>(Channel.CONFLATED)
                val ready = MutableSharedFlow<Boolean>()
                val calls = Channel<Boolean>(Channel.UNLIMITED)
                val job = launch(start = CoroutineStart.UNDISPATCHED) {
                    runAgendaSchedule(ready, changes, retryMillis = 20) { force -> calls.send(force); AgendaRefresh(alarmsOk, false) }
                }
                try {
                    ready.subscriptionCount.firstPositive()
                    ready.emit(true); assertTrue(calls.receive()); assertEquals(!alarmsOk, calls.receive())
                } finally { job.cancelAndJoin() }
            }
        }
    }
    @Test fun disconnectCancelsInflightRefreshAndReconnectStartsFresh() = runBlocking {
        withTimeout(5000) {
            val changes = Channel<Unit>(Channel.CONFLATED)
            val ready = MutableSharedFlow<Boolean>()
            val calls = Channel<Boolean>(Channel.UNLIMITED)
            val cancelled = CompletableDeferred<Unit>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                runAgendaSchedule(ready, changes) { force ->
                    calls.send(force)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }
            }
            try {
                ready.subscriptionCount.firstPositive()
                ready.emit(true); assertTrue(calls.receive())
                ready.emit(false); cancelled.await()
                changes.send(Unit); yield(); assertFalse(calls.tryReceive().isSuccess)
                ready.emit(true); assertTrue(calls.receive())
            } finally { job.cancelAndJoin() }
        }
    }
}

private suspend fun kotlinx.coroutines.flow.StateFlow<Int>.firstPositive() = first { it > 0 }
