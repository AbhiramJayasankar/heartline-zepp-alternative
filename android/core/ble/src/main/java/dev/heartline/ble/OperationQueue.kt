package dev.heartline.ble

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException

/** Cancellation of an in-flight ATT operation poisons the link: its late callback must not finish a new one. */
class OperationQueue(private val invalidate: () -> Unit) {
    private val mutex = Mutex()
    suspend fun <T> run(timeoutMs: Long, operation: suspend () -> T): T = mutex.withLock {
        try { withTimeout(timeoutMs) { operation() } }
        catch (error: CancellationException) { invalidate(); throw error }
    }
}
