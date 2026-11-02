package dev.heartline.app

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*

internal data class AgendaRefresh(val alarms: Boolean, val reminders: Boolean)

internal suspend fun runAgendaSchedule(
    ready: Flow<Boolean>, changes: ReceiveChannel<Unit>, intervalMillis: Long = 30 * 60_000L,
    retryMillis: Long = 30_000L, refresh: suspend (forceAlarms: Boolean) -> AgendaRefresh,
) {
    ready.distinctUntilChanged().collectLatest { connected ->
        if (!connected) return@collectLatest
        var forceAlarms = true
        while (currentCoroutineContext().isActive) {
            val result = refresh(forceAlarms)
            if (result.alarms) forceAlarms = false
            withTimeoutOrNull(if (result.alarms && result.reminders) intervalMillis else retryMillis) { changes.receive() }
        }
    }
}
