package dev.heartline.ble

/**
 * Tells when the phone's Bluetooth connection object has died under the app. When Android's Bluetooth service
 * restarts, the old connection never reports a disconnect: every request fails with a DeadObjectException, or
 * can't even start, and the app waited on it for 31 hours (22 to 24 Nov 2026) with nothing synced. A dead binder, or
 * [LIMIT] requests in a row that couldn't start, means the connection must be closed and made again.
 */
class DeadLink {
    private var failedStarts = 0

    /** Records whether a request could start; true when that makes the link dead. */
    fun started(ok: Boolean): Boolean {
        if (ok) { failedStarts = 0; return false }
        return ++failedStarts >= LIMIT
    }

    companion object {
        /** Requests in a row that can't start before the link counts as dead (the queue runs one at a time, so a
         * healthy link refuses to start one only rarely). */
        const val LIMIT = 3

        /** Whether [e] (or anything that caused it) says the Bluetooth service behind the connection is gone. */
        fun isDeadBinder(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(8).any { t ->
            t.javaClass.name in DEAD || DEAD.any { t.message?.contains(it) == true }
        }

        private val DEAD = setOf("android.os.DeadObjectException", "android.os.DeadSystemException")
    }
}
