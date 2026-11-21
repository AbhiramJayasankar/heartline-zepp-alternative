package dev.heartline.analytics

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpsQualityTest {
    /** Laps of a 780 m loop at [ms] m/s, one fix a second, each fix off by up to [jitter] metres. */
    private fun loop(seconds: Int, ms: Double, jitter: Double, seed: Int = 1): List<GeoFix> {
        val rng = Random(seed)
        val r = 780 / (2 * PI)
        val mPerDeg = 111_320.0
        return (0 until seconds).map { t ->
            val a = ms * t / r
            val x = r * cos(a) + rng.nextDouble(-jitter, jitter); val y = r * sin(a) + rng.nextDouble(-jitter, jitter)
            GeoFix(t, 12.93 + y / mPerDeg, 77.67 + x / (mPerDeg * cos(12.93 * PI / 180)))
        }
    }
    private fun speed(seconds: Int, ms: Double) = (0 until seconds).map { it to ms }
    private fun bout(start: Int, end: Int, running: Boolean, cadence: Int) =
        GaitBout(start, end, running, cadence, null, null, null, null, null, null)
    private fun gait(bouts: List<GaitBout>) = GaitSummary(bouts, bouts.filter { it.running }.sumOf { it.seconds },
        bouts.filter { !it.running }.sumOf { it.seconds }, 0, null, null, null, null, null, null, null, null, 0, null, null)
    private val lengths = StepLengths(walkM = 0.86, runM = 1.04)

    @Test fun aCleanTrackBarelyWanders() {
        val w = gpsWander(loop(1800, 2.0, 1.0))!!
        assertTrue(w < 1.1, "wander $w")
    }

    @Test fun aJitteryTrackWanders() {
        val w = gpsWander(loop(1800, 2.0, 6.0))!!
        assertTrue(w > 1.2, "wander $w")
    }

    @Test fun aDistanceTheStepsCannotCoverIsCorrectedToTheSteps() {
        // 21 Nov 2026: 5.00 km from the watch for 4,035 steps, about 3.85 km by laps of the lake.
        val g = gait(listOf(bout(0, 900, true, 148), bout(900, 2327, false, 76)))
        val check = assertNotNull(checkGpsDistance(5002, speed(2327, 1.9), loop(2327, 1.9, 6.0), g, lengths, 4035))
        assertTrue(check.fromSteps)
        val runSteps = 900 * 148 / 60.0; val walkSteps = 1427 * 76 / 60.0; val k = 4035 / (runSteps + walkSteps)
        assertEquals(k * (runSteps * 1.04 + walkSteps * 0.86), check.distanceM, 1.0)
        assertEquals(check.distanceM, check.gait!!.bouts.sumOf { it.distanceM!! }, 0.01)
    }

    @Test fun aDistanceTheStepsCanCoverHolds() {
        // 10 Nov 2026: the speed readings had gaps (1.1 km) but the steps cover the watch's 2.0 km, so it stands.
        val g = gait(listOf(bout(0, 1440, false, 95)))
        val gappy = speed(1440, 1.4).filterIndexed { i, _ -> i % 40 < 15 }
        assertNull(checkGpsDistance(2028, gappy, loop(1440, 1.4, 6.0), g, lengths, 2300))
    }

    @Test fun withoutStepLengthsTheSpeedIsTheCheck() {
        val check = assertNotNull(checkGpsDistance(5473, speed(2200, 1.9), loop(2200, 1.9, 6.0), null, StepLengths(), null))
        assertTrue(!check.fromSteps)
        assertEquals(4180.0, check.distanceM, 10.0)
    }

    @Test fun aWatchDistanceInLineWithItsSpeedHolds() {
        assertNull(checkGpsDistance(3890, speed(2185, 1.71), loop(2185, 1.71, 1.0), null, StepLengths(), null))
    }

    @Test fun stepLengthsComeOnlyFromCleanTracks() {
        val clean = gait(listOf(GaitBout(0, 600, false, 100, null, null, null, null, 860.0, null),
            GaitBout(600, 900, true, 140, null, null, null, null, 728.0, null)))
        val noisy = gait(listOf(GaitBout(0, 600, false, 100, null, null, null, null, 1300.0, null)))
        val l = learnStepLengths(listOf(clean to loop(900, 1.6, 0.3), noisy to loop(600, 2.2, 8.0)))
        assertEquals(0.86, l.walkM!!, 0.005)
        assertEquals(1.04, l.runM!!, 0.005)
    }
}
