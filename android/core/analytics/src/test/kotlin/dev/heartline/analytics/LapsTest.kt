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

class LapsTest {
    private val lat0 = 12.9225; private val lon0 = 77.6660
    private val mLat = 1 / 111_195.0; private val mLon = 1 / (111_195.0 * cos(lat0 * PI / 180))

    /** A point [x], [y] metres east and north of the lake's middle. */
    private fun at(t: Int, x: Double, y: Double) = GeoFix(t, lat0 + y * mLat, lon0 + x * mLon)

    /**
     * Laps of a circular loop [loopM] round, at [speed] m/s each second (a function of the second), with [jitterM] of
     * GPS noise, after [leadS] seconds standing at the start.
     */
    private fun loop(seconds: Int, speed: (Int) -> Double, loopM: Double = 745.0, jitterM: Double = 2.0, leadS: Int = 0, seed: Int = 1): List<GeoFix> {
        val r = loopM / (2 * PI)
        val rnd = Random(seed)
        var s = 0.0
        return (0 until seconds).map { t ->
            if (t >= leadS) s += speed(t)
            val a = s / r
            at(t, r * sin(a) + rnd.nextDouble(-jitterM, jitterM), -r * cos(a) + rnd.nextDouble(-jitterM, jitterM))
        }
    }

    @Test fun lapsOfALoopAreFoundWithTheirLengthAndTimes() {
        // 5 laps at 2.5 m/s (298 s a lap), then 100 m more.
        val track = loop(5 * 298 + 40, { 2.5 })
        val line = assertNotNull(findLapLine(track))
        assertEquals(6, line.passes.size)
        assertEquals(745.0, line.loopM, 15.0)
        line.passes.zipWithNext { a, b -> assertEquals(298.0, b - a, 3.0) }
    }

    @Test fun lapStatsSplitRunningFromWalking() {
        // Each lap: 150 s running at 3 m/s (450 m), then walking at 1.4 m/s for the rest (295 m, 211 s).
        val lapS = 150 + 211
        val speed = { t: Int -> if (t % lapS < 150) 3.0 else 1.4 }
        val n = 4 * lapS + 60
        val track = loop(n, speed)
        val line = assertNotNull(findLapLine(track))
        val kind = Array(n) { if (it % lapS < 150) Gait.RUN else Gait.WALK }
        val cadence = DoubleArray(n) { if (it % lapS < 150) 160.0 else 100.0 }
        val hr = IntArray(n) { if (it % lapS < 150) 165 else 130 }
        val s = lapSummary(line, n, trackMetresPerSecond(track, n), kind, cadence, hr)
        assertEquals(4, s.laps.size)
        for (lap in s.laps) {
            assertEquals(745.0, lap.distanceM!!, 25.0)
            assertEquals(150.0, lap.runS, 6.0)
            assertEquals(450.0, lap.runM!!, 25.0)
            assertEquals(1000 / 3.0, lap.runPaceSPerKm!!, 15.0)
            assertEquals(1000 / 1.4, lap.walkPaceSPerKm!!, 40.0)
            // 160 steps a minute for 150 s and 100 for 211 s: 400 + 352 steps.
            assertEquals(752.0, lap.steps!!.toDouble(), 15.0)
            assertEquals(745 / 752.0, lap.strideM!!, 0.05)
            assertEquals(1000 / 3.0, lap.bestPaceSPerKm!!, 15.0)
            assertTrue(lap.segments.count { it.gait == Gait.RUN } in 1..2)
        }
        assertTrue(s.spreadPct!! < 3)
        assertNotNull(s.tail)
    }

    @Test fun aFadingSessionShowsInTheFade() {
        // Six laps, each 5% slower than the last.
        var lap = 0; var into = 0.0
        val speeds = (0 until 6).map { 2.8 / Math.pow(1.05, it.toDouble()) }
        val n = speeds.sumOf { 745 / it }.toInt() + 10
        val track = loop(n, { _ -> val v = speeds[minOf(lap, 5)]; into += v; if (into >= 745) { into -= 745; lap++ }; v })
        val line = assertNotNull(findLapLine(track))
        val s = lapSummary(line, n, trackMetresPerSecond(track, n), Array(n) { Gait.RUN }, DoubleArray(n) { 160.0 }, IntArray(n) { 160 })
        assertEquals(1, s.fastest)
        assertTrue(s.fadePct!! > 15, "fade ${s.fadePct}")
    }

    @Test fun aWanderingTrackStillGivesItsLaps() {
        // 20 m of jitter, as a watch's GPS on a bad day: every lap is still found.
        val track = loop(5 * 298 + 40, { 2.5 }, jitterM = 20.0)
        val line = assertNotNull(findLapLine(track))
        assertEquals(6, line.passes.size)
    }

    @Test fun aWalkedLapWhoseTrackWanderedStillCounts() {
        // 21 Nov 2026: four laps traced about 860 m each, then a walked lap traced 1,278 m as the watch's GPS zigzagged.
        // Here: four laps at 2.5 m/s, a walked lap at 1.4 m/s whose track swings 7 m either side of the path every 20 s
        // (about 1.4 times the path), then 100 m more.
        val loopM = 745.0; val r = loopM / (2 * PI)
        val run = (4 * loopM / 2.5).toInt(); val walk = (loopM / 1.4).toInt()
        var s = 0.0
        val track = (0 until run + walk + 40).map { t ->
            s += when { t < run -> 2.5; t < run + walk -> 1.4; else -> 2.5 }
            val a = s / r
            val swing = if (t in run until run + walk) { val ph = (t % 20) / 20.0; 7.0 * (if (ph < 0.5) 4 * ph - 1 else 3 - 4 * ph) } else 0.0
            at(t, (r + swing) * sin(a), -(r + swing) * cos(a))
        }
        val line = assertNotNull(findLapLine(track))
        assertEquals(6, line.passes.size, "passes ${line.passes}")
        assertEquals(run + walk.toDouble(), line.passes[5], 10.0)
    }

    @Test fun anOutAndBackWalkIsNoLap() {
        // 600 m east and back along the same street.
        val track = (0 until 860).map { t -> val x = if (t < 430) t * 1.4 else (860 - t) * 1.4; at(t, x, 0.0) }
        assertNull(findLapLine(track))
    }

    @Test fun oneLoopIsNotYetLaps() {
        assertNull(findLapLine(loop(320, { 2.5 })))
    }

    @Test fun standingStillAddsNoDistance() {
        val track = (0 until 300).map { at(it, 0.0, 0.0) }
        val m = trackMetresPerSecond(track, 300)
        assertEquals(0.0, m.filter { it.isFinite() }.sum(), 0.01)
    }

    @Test fun boutsTakeTheirDistanceFromTheTrack() {
        val g = GaitSummary(listOf(GaitBout(0, 100, true, 160, 160, 170, 150, 160, null, null), GaitBout(100, 200, false, 100, 130, 140, 160, 125, null, null)),
            100, 100, 0, null, null, 160, 100, 160, 130, 170, null, 0, null, null)
        val metres = DoubleArray(200) { if (it < 100) 3.0 else 1.4 }
        val out = g.withMetres(metres)
        assertEquals(300.0, out.runDistanceM!!, 0.01)
        assertEquals(140.0, out.walkDistanceM!!, 0.01)
    }
}
