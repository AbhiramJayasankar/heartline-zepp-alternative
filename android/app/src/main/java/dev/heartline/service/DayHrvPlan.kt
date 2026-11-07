package dev.heartline.service

/**
 * When to try the next daytime HRV check.
 *
 * After a clean reading nothing runs for the chosen interval ([afterClean]). Then checks run back to back, each as soon
 * as the watch has counted no steps for [STEP_QUIET_S], until one is clean. A check that fails while you stayed put is
 * tried again straight away: some wrist movement is normal when sitting, and the next one may get enough clean beats.
 * After [STILL_TRIES] of those without a clean one (a loose strap, say), it gives up and waits the interval too.
 * Only a check you walked during (the watch counted steps) backs off, 2, 4, 8 then 16 minutes, so a walk or a workout
 * costs at most one recording every ~18 minutes. Nobody stays still for 10 minutes while awake, so the old rule of
 * waiting for 10 still minutes is gone.
 */
data class DayHrvPlan(val nextAt: Long = 0L, val walkingFails: Int = 0, val stillFails: Int = 0, val tries: Int = 0, val gaveUp: Boolean = false) {
    fun afterClean(at: Long, everyS: Long) = DayHrvPlan(nextAt = at + everyS)

    /** A check that wasn't clean, ending at [at]; [walked] when the watch counted steps during it. */
    fun afterFail(at: Long, walked: Boolean, everyS: Long): DayHrvPlan = when {
        walked -> (walkingFails + 1).let { n -> copy(nextAt = at + backoff(n), walkingFails = n, tries = tries + 1, gaveUp = false) }
        stillFails + 1 >= STILL_TRIES -> DayHrvPlan(nextAt = at + everyS, gaveUp = true)
        else -> copy(nextAt = at, walkingFails = 0, stillFails = stillFails + 1, tries = tries + 1, gaveUp = false)
    }

    /** Waiting out a back-off after walking (as opposed to the interval after a clean reading or giving up). */
    val backingOff: Boolean get() = walkingFails > 0

    companion object {
        /** The watch must have counted no new steps for this long before a check starts. */
        const val STEP_QUIET_S = 60L
        /** Checks without steps that may fail before it waits the interval. */
        const val STILL_TRIES = 7
        const val MAX_BACKOFF_S = 16 * 60L

        /** The n-th back-off: 2, 4, 8, then 16 minutes. */
        fun backoff(n: Int): Long = minOf(MAX_BACKOFF_S, 120L shl (n - 1).coerceIn(0, 8))
    }
}
