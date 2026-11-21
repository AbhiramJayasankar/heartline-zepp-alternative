package dev.heartline.service

/**
 * When to try the next daytime HRV check.
 *
 * After a clean reading nothing runs for the chosen interval ([afterClean]). Then checks run, each as soon as the watch
 * has counted no steps for [STEP_QUIET_S], until one is clean. A check that fails while you stayed put is tried again
 * straight away: some wrist movement is normal when sitting, and the next one may get enough clean beats. One you
 * walked during (the watch counted steps) backs off 2, then 4 minutes. After [TRIES] in a row without a clean one, it
 * gives up for [GIVE_UP_S], an hour (since 0.7.2: seven tries back to back kept the watch recording through a run-walk).
 * A check you stop waits the hour too ([afterStop]).
 */
data class DayHrvPlan(val nextAt: Long = 0L, val walkingFails: Int = 0, val stillFails: Int = 0, val tries: Int = 0, val gaveUp: Boolean = false) {
    fun afterClean(at: Long, everyS: Long) = DayHrvPlan(nextAt = at + everyS)

    /** A check that wasn't clean, ending at [at]; [walked] when the watch counted steps during it. */
    fun afterFail(at: Long, walked: Boolean, @Suppress("UNUSED_PARAMETER") everyS: Long): DayHrvPlan = when {
        tries + 1 >= TRIES -> DayHrvPlan(nextAt = at + GIVE_UP_S, gaveUp = true)
        walked -> (walkingFails + 1).let { n -> copy(nextAt = at + backoff(n), walkingFails = n, tries = tries + 1, gaveUp = false) }
        else -> copy(nextAt = at, walkingFails = 0, stillFails = stillFails + 1, tries = tries + 1, gaveUp = false)
    }

    /** A check you stopped, at [at]: none for an hour. */
    fun afterStop(at: Long) = DayHrvPlan(nextAt = at + GIVE_UP_S, gaveUp = true)

    /** Waiting out a back-off after walking (as opposed to the interval after a clean reading or giving up). */
    val backingOff: Boolean get() = walkingFails > 0

    companion object {
        /** The watch must have counted no new steps for this long before a check starts. */
        const val STEP_QUIET_S = 60L
        /** Checks that may fail in a row before it waits [GIVE_UP_S]. */
        const val TRIES = 3
        const val GIVE_UP_S = 3600L
        const val MAX_BACKOFF_S = 16 * 60L

        /** The n-th back-off: 2, 4, 8, then 16 minutes. */
        fun backoff(n: Int): Long = minOf(MAX_BACKOFF_S, 120L shl (n - 1).coerceIn(0, 8))
    }
}
