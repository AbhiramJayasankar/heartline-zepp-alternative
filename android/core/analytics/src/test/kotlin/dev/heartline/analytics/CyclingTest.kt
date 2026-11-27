package dev.heartline.analytics

import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CyclingTest {
    /** Metres each second from [speed] (m/s, a function of the second), with a little GPS wobble. */
    private fun metres(n: Int, seed: Int = 1, speed: (Int) -> Double): DoubleArray {
        val rnd = Random(seed)
        return DoubleArray(n) { t -> (speed(t) + (rnd.nextDouble() - 0.5) * 0.6).coerceAtLeast(0.0) }
    }

    private fun hr(n: Int, bpm: Int = 130) = IntArray(n) { bpm }

    @Test
    fun aSteadyRideWithOneStop() {
        // 10 min at 6 m/s, 2 min stopped at a light, 10 min at 6 m/s.
        val n = 22 * 60
        val r = assertNotNull(rideSummary(n, metres(n) { t -> if (t in 600 until 720) 0.0 else 6.0 }, hr(n)))
        assertEquals(7200.0, r.distanceM, 150.0)
        assertEquals(1, r.stops)
        assertTrue(abs(r.longestStopS - 120) <= 15, "${r.longestStopS}")
        assertTrue(abs(r.movingS - 1200) <= 20, "${r.movingS}")
        assertEquals(21.6, assertNotNull(r.avgKmh), 0.6)
        assertEquals(7, r.splits.size)
        assertTrue(r.splits.all { abs(it.kmh - 21.6) < 1.5 }, r.splits.toString())
        assertEquals(130, r.ridingHr)
        // 6 m/s at 130 bpm: 360 m a minute over 130 beats.
        assertEquals(360.0 / 130, assertNotNull(r.metresPerBeat), 0.1)
        assertTrue(assertNotNull(r.spreadPct) < 5)
    }

    @Test
    fun aWalkIsNotARide() {
        val n = 20 * 60
        assertNull(rideSummary(n, metres(n) { 1.4 }, hr(n)))
    }

    @Test
    fun theMaskKeepsTheWalksAroundTheRideOut() {
        // 5 min walking to the bike, 20 min riding at 5 m/s, 5 min walking back.
        val n = 30 * 60
        val speed = { t: Int -> if (t in 300 until 1500) 5.0 else 1.3 }
        val mask = BooleanArray(n) { it in 300 until 1500 }
        val r = assertNotNull(rideSummary(n, metres(n, speed = speed), hr(n), mask = mask))
        assertEquals(6000.0, r.distanceM, 150.0)
        assertTrue(abs(r.movingS - 1200) <= 20, "${r.movingS}")
        assertEquals(0, r.stops)
        // Times stay in session seconds.
        assertEquals(300, r.bouts.first().start)
        assertEquals(1500, r.bouts.last().end)
        assertTrue(r.splits.first().end in 490..520, r.splits.first().toString())
        assertEquals(6, r.splits.size)
    }

    @Test
    fun climbingCountsTheHillNotTheWobble() {
        // 20 min climbing 60 m then coming back down, the altitude wobbling by a metre each way.
        val n = 20 * 60
        val rnd = Random(3)
        val alt = DoubleArray(n) { t -> 800 + (if (t < 600) t / 10.0 else (1200 - t) / 10.0) + (rnd.nextDouble() - 0.5) * 2 + sin(t / 7.0) }
        val r = assertNotNull(rideSummary(n, metres(n) { 5.0 }, hr(n), alt))
        assertEquals(60.0, assertNotNull(r.climbM), 8.0)
        assertEquals(60.0, assertNotNull(r.descentM), 8.0)
        // Flat but wobbling: next to nothing.
        val flat = DoubleArray(n) { 820 + (rnd.nextDouble() - 0.5) * 2 }
        assertTrue(assertNotNull(assertNotNull(rideSummary(n, metres(n) { 5.0 }, hr(n), flat)).climbM) < 4)
    }

    @Test
    fun aRideThatSlowsShowsItsFade() {
        val n = 30 * 60
        val r = assertNotNull(rideSummary(n, metres(n) { t -> if (t < 900) 7.0 else 5.0 }, hr(n)))
        assertTrue(assertNotNull(r.fadePct) > 20, "${r.fadePct}")
        assertEquals(25.2, assertNotNull(r.top5MinKmh), 1.0)
    }
}
