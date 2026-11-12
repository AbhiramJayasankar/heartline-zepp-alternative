package dev.heartline.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform

/**
 * The first value at once, then at most one every [periodMs], always ending on the latest. Database tables announce
 * every write, and a sync or live heart rate writes many times a second; the screens only need the latest state.
 */
fun <T> Flow<T>.throttleLatest(periodMs: Long): Flow<T> = conflate().transform { emit(it); delay(periodMs) }

/** Set in debug builds (HeartlineApp): [perfLog] writes to the log. */
@Volatile internal var perfLogging = false

/** Logs how long a piece of work took (debug builds), when it took long enough to notice; `adb logcat -s HLPerf`. */
internal fun perfLog(what: String, startNanos: Long) {
    val ms = (System.nanoTime() - startNanos) / 1_000_000
    if (perfLogging && ms >= 30) android.util.Log.d("HLPerf", "$what $ms ms")
}
