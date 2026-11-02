package dev.heartline.ble

/** Monotonic times only: wall-clock changes must not turn a short idle drop into a failure. */
internal class QuietReconnects {
    private var droppedAt: Long? = null
    private var warned = false
    private var quiet = 0
    fun dropped(now: Long) { droppedAt = now; warned = false }
    fun overdue(now: Long): String? {
        val start = droppedAt ?: return null
        if (warned || now - start <= 30_000) return null
        warned = true
        return "Connection lost, reconnecting"
    }
    fun authenticated(now: Long): List<String> {
        val start = droppedAt
        val messages = if (start != null && !warned && now - start <= 30_000) {
            quiet++; emptyList()
        } else listOfNotNull(overdue(now), "Connected and authenticated")
        clearPending()
        return messages
    }
    fun clearPending() { droppedAt = null; warned = false }
    fun summary(): String? {
        if (quiet == 0) return null
        return "Reconnected quietly $quiet times in the last hour".also { quiet = 0 }
    }
}
