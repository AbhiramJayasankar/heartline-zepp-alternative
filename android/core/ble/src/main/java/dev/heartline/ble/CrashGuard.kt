package dev.heartline.ble

import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * Catches what a background coroutine failed with instead of letting it end the app: an unhandled failure in a
 * launched job (a watch write after the link dropped, say) used to crash the whole process (audit, 2026-11-12).
 * [report] is set by the app to put it in the activity log.
 */
object CrashGuard {
    @Volatile var report: (Throwable) -> Unit = {}
    val handler = CoroutineExceptionHandler { _, e ->
        android.util.Log.e("Heartline", "Background task failed", e)
        BleLog.note("error: background task failed: $e")
        runCatching { report(e) }
    }
}
