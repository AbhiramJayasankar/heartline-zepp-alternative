package dev.heartline.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * For a `catch (e: Exception)`: rethrows when this coroutine itself was cancelled, and otherwise lets [e] be handled as
 * a failure. A timeout inside the work (a GATT operation, a scan, a request) is a [CancellationException] too, and
 * rethrowing every one of those ended the reconnect loop and the scheduled syncs for good (audit, 2026-11-12).
 */
suspend fun rethrowIfCancelled(e: Throwable) {
    if (e is CancellationException) currentCoroutineContext().ensureActive()
}
