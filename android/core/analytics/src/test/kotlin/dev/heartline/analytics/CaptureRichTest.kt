package dev.heartline.analytics

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class CaptureRichTest {
    @Test fun exponentialFitsRecoverKnownConstantsAndRejectUnidentifiableFits() {
        val rise = (0..600 step 2).filter { it !in 180..200 }.map { it.toDouble() to 90 + 90 * (1 - exp(-it / 160.0)) }
        val recovery = (0..240).map { it.toDouble() to 100 + 80 * exp(-it / 73.0) }
        assertEquals(160, exponentialTau(rise, true)); assertEquals(73, exponentialTau(recovery, false))
        assertNull(exponentialTau(rise, false)); assertNull(exponentialTau(recovery, true))
        assertNull(exponentialTau((0..100).map { it.toDouble() to 100.0 }, true))
        assertNull(exponentialTau(rise.take(10), true))
    }

    @Test fun cardioMergesGaitAndRespectsWatchBoundsMissingHrAndNextBlock() {
        val gait = listOf(CaptureSegment(10.0, 100.0, "walking", null, 90), CaptureSegment(120.0, 250.0, "walking", null, 100),
            CaptureSegment(320.0, 520.0, "running", null, 150))
        val watch = CaptureActivity(20.0, 240.0, "Indoor WALK", 200, 500, null, null, null, null)
        val hr = IntArray(700) { 100 + (80 * (1 - exp(-it / 100.0))).roundToInt() }
        val blocks = captureCardio(gait, listOf(watch), hr)
        assertEquals(2, blocks.size)
        val b = blocks.first()
        // The watch was active for 200 s: the block ends there (plus 5 s), not when its record closed.
        assertEquals(20.0, b.start, 0.0); assertEquals(225.0, b.end, 0.0); assertEquals("Indoor WALK", b.name)
        assertEquals(500, b.distanceM); assertEquals(9.0, b.speedKmh!!, 0.0)
        assertNotNull(b.drop60); assertNull(b.drop120)
        hr[285] = 0
        assertNull(captureCardio(gait, listOf(watch), hr).first().drop60)
        val truncated = captureCardio(listOf(gait.first().copy(end = 240.0)), emptyList(), hr.copyOf(240)).single()
        assertNull(truncated.drop60); assertNull(truncated.drop120); assertNull(truncated.recoveryTauS)
        assertNull(truncated.name); assertNull(truncated.speedKmh)
    }

    @Test fun recoveryKeepsObservedRestSamplesAndMinimumSupport() {
        val sets = (0..3).map { CaptureSet(it * 100.0, it * 100.0 + 20, 5, null, null, 4.0) }
        val hr = IntArray(400) { 110 }
        for (s in sets) hr[s.end.toInt() + 10] = 130
        val curve = captureRecovery(sets, emptyList(), hr)
        assertEquals(CaptureRecoveryPoint(0, 0.0, 3), curve.first())
        assertEquals(CaptureRecoveryPoint(60, -20.0, 3), curve.single { it.t == 60 })
        assertTrue(curve.none { it.t >= 70 })
        hr[90] = 0
        assertTrue(captureRecovery(sets, emptyList(), hr).none { it.t == 60 })
        assertTrue(captureRecovery(sets.take(3), emptyList(), hr).isEmpty())
    }

    @Test fun stillWindowsRequireGyroCoverageAndThreeConsecutiveSeconds() {
        val time = DoubleArray(120 * 25) { it / 25.0 }
        fun still(t: Double) = t < 30 || t in 55.0..<85.0
        val acc = Array(time.size) { i -> floatArrayOf(0f, 0f, if (still(time[i])) 1f else (1 + .2 * sin(i.toDouble())).toFloat()) }
        val gyro = Array(time.size) { i -> floatArrayOf(if (still(time[i])) 0f else if (i % 2 == 0) 100f else -100f, 0f, 0f) }
        val rec = Recording(0.0, doubleArrayOf(), doubleArrayOf(), time, acc, time, gyro)
        assertEquals(listOf(1 to 29, 56 to 84), captureStillWindows(rec, 120))
        assertTrue(captureStillWindows(rec.copy(gyro = emptyArray(), gyroT = doubleArrayOf()), 120).isEmpty())
        val clock = capturePpgClock(doubleArrayOf(0.0, .04, .08, 10.0, 10.04, 10.08))
        assertArrayEquals(doubleArrayOf(0.0, .04, .08, 10.0, 10.04, 10.08), clock, 1e-9)
    }

    @Test fun emptyCaptureHasNoInventedObservations() {
        val r = Recording(1000.0, doubleArrayOf(), doubleArrayOf(), doubleArrayOf(), emptyArray(), captureDurationS = 600.0)
        val result = analyzeCapture(r, emptyList(), Profile(), "empty")
        assertTrue(result.floor.isEmpty()); assertTrue(result.recovery.isEmpty()); assertTrue(result.cardio.isEmpty())
        assertNull(result.pulse); assertNull(result.workS); assertNull(result.restS)
    }

    @Test fun pooledCleanTimingRetainsEctopicCandidateStatusAndDisclaimer() {
        val rr = DoubleArray(60) { .6 }
        val clean = Intervals(DoubleArray(60) { it * .6 }, rr, BooleanArray(60), BooleanArray(60))
        assertEquals("regular", assessRhythm(clean, null).status)
        val result = assessRhythm(clean, null, ectopicBeats = 3)
        assertEquals("irregular", result.status); assertEquals(3, result.ectopicBeats)
        assertTrue(result.notes.any { it.contains("not a medical diagnosis") })
    }
}
