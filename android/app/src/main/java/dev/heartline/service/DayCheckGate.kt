package dev.heartline.service

import dev.heartline.analytics.LiveCheck

/**
 * Decides, while a daytime check records, whether to keep going. The wrist moves most of a daytime check (median 83%
 * of the time above the movement limit on 191 checks, 21 to 27 Nov 2026), and a fixed 2-minute check kept recording
 * through typing it could never get clean beats from. So a check now:
 *
 * - **Scouts first.** After [SCOUT_S] seconds it stops unless the wrist was still for at least [SCOUT_STILL_S] of them
 *   ([Verdict.MOVING]). On those 191 checks this would have ended 131 after 20 s and lost 3 of the 29 that passed.
 * - **Stops when it has enough**: [TARGET_S] seconds of clean beats, 5 s over the 45 s a clean check needs, since the
 *   live sum and the finished check's can differ a little ([Verdict.ENOUGH]). Checks that passed got there at a median
 *   74 s (64 to 88 s), not 120.
 * - **Stops when it can't get there**: when even clean beats from every second left would not reach the need
 *   ([Verdict.CANT_REACH]).
 *
 * The finished recording is still judged by the usual rule (45 s of clean beats, 70% quiet-time quality); this only
 * decides how long it records. Research: research/heartcheck-motion.md.
 */
object DayCheckGate {
    enum class Verdict { CONTINUE, MOVING, ENOUGH, CANT_REACH }

    const val SCOUT_S = 20.0
    const val SCOUT_STILL_S = 6.0
    const val TARGET_S = 50.0

    /**
     * [live] is the check so far (null until it has signal, or when nothing computes it: then the check just runs its
     * full length). [lengthS] is the longest it may record and [needS] the clean time the finished check needs.
     */
    fun decide(live: LiveCheck?, lengthS: Int, needS: Int): Verdict {
        if (live == null) return Verdict.CONTINUE
        val t = live.durationS
        if (live.cleanS >= TARGET_S) return Verdict.ENOUGH
        if (t < SCOUT_S) return Verdict.CONTINUE
        if (stillS(live, SCOUT_S) < SCOUT_STILL_S) return Verdict.MOVING
        if (live.cleanS + (lengthS - t) < needS) return Verdict.CANT_REACH
        return Verdict.CONTINUE
    }

    /** Seconds of the first [untilS] the wrist was at or under the movement limit (the motion grid is 0.5 s). */
    fun stillS(live: LiveCheck, untilS: Double): Double {
        val t = live.motionT; val g = live.motionG
        if (t.size < 2) return 0.0
        val step = t[1] - t[0]
        var n = 0
        for (i in t.indices) if (t[i] <= untilS && g[i] <= LiveCheck.MOVING_G) n++
        return n * step
    }
}
