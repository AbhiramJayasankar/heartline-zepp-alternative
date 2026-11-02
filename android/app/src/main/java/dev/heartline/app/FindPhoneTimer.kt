package dev.heartline.app

/** Use Android's monotonic elapsedRealtime: duplicate start events must not extend the alarm. */
class FindPhoneTimer {
    private var deadline: Long? = null
    fun start(nowMs: Long): Boolean {
        if (deadline != null) return false
        deadline = nowMs + TIMEOUT_MS
        return true
    }
    fun remaining(nowMs: Long): Long = deadline?.let { (it - nowMs).coerceIn(0, TIMEOUT_MS) } ?: 0
    fun stop() { deadline = null }
    companion object { const val TIMEOUT_MS = 60_000L }
}
