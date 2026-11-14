package dev.heartline.analytics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MotionPulseTest {
    private val fs = 25.0
    private val seconds = 240
    private val stepRate = 165.0

    /** Running at [stepRate] steps/min: the arm swings at half that, each footfall jolts the wrist, and the jolt leaks into the PPG. */
    private fun run(pulseBpm: Double?, seed: Int = 1): Recording {
        val rng = Random(seed)
        val n = (seconds * fs).toInt()
        val t = DoubleArray(n) { it / fs }
        val jolt = DoubleArray(n) { 1.0 * sin(2 * PI * stepRate / 60 * t[it]) }
        val ppg = DoubleArray(n) { i ->
            30_000 + (pulseBpm?.let { 120 * sin(2 * PI * it / 60 * t[i]) } ?: 0.0) + 250 * jolt[i] + 40 * rng.nextDouble(-1.0, 1.0)
        }
        val acc = Array(n) { i -> floatArrayOf(0f, 0f, (1 + jolt[i]).toFloat()) }
        val gyro = Array(n) { i -> floatArrayOf((60 * sin(2 * PI * stepRate / 120 * t[i] + .3)).toFloat(), 0f, 0f) }
        return Recording(0.0, t, ppg, t, acc, t, gyro, watchClock = true, captureDurationS = seconds.toDouble())
    }

    private val segments = listOf(CaptureSegment(0.0, seconds.toDouble(), "running", null, stepRate.toInt()))

    @Test fun aWatchLockedOntoTheStepRateIsCorrectedFromThePulse() {
        val watch = IntArray(seconds) { if (it in 60 until 180) 165 else 150 }
        val fixes = motionHrFixes(run(150.0), watch, segments)
        assertEquals(1, fixes.size, fixes.toString())
        val (fix, values) = fixes.single()
        assertEquals(60.0, fix.start, 4.0); assertEquals(180.0, fix.end, 4.0)
        assertEquals(165, fix.watchBpm)
        assertTrue(abs(fix.fixedBpm - 150) <= 3, "fixed ${fix.fixedBpm}")
        assertTrue(values.all { abs(it - 150) <= 6 }, values.toList().toString())
        assertTrue(fix.evidence >= .5)
    }

    @Test fun aWatchThatIsOffTheStepRateIsLeftAlone() {
        assertTrue(motionHrFixes(run(150.0), IntArray(seconds) { 150 }, segments).isEmpty())
    }

    @Test fun withNoClearPulseTheWatchStands() {
        val watch = IntArray(seconds) { if (it in 60 until 180) 165 else 150 }
        assertTrue(motionHrFixes(run(null), watch, segments).isEmpty())
    }

    @Test fun aPulseThatAgreesWithALockedWatchChangesNothing() {
        // Heart rate really is the step rate: the pulse can't be told from the steps, so there is no case to change it.
        val watch = IntArray(seconds) { 165 }
        assertTrue(motionHrFixes(run(165.0), watch, segments).isEmpty())
    }

    @Test fun theAnalysisUsesTheCorrectedSecondsForItsFigures() {
        val rec = run(150.0)
        val hr = (0 until seconds).map { it + .2 to if (it in 60 until 180) 165 else 150 }
        val r = analyzeCapture(rec, hr, Profile(), "run")
        assertEquals(1, r.hrFixes.size, r.segments.toString())
        assertTrue(r.maxHr!! <= 156, "max ${r.maxHr}")
        assertTrue(r.hrSource.contains("corrected"))
    }

    @Test fun walkingHeartRateOnTheWalkingCadenceIsNoLock() {
        // Walking: cadence and heart rate are both near 100, so the watch matching the steps is no sign of a lock.
        val walk = listOf(CaptureSegment(0.0, seconds.toDouble(), "walking", null, 165))
        val watch = IntArray(seconds) { if (it in 60 until 180) 165 else 150 }
        assertTrue(motionHrFixes(run(150.0), watch, walk).isEmpty())
    }
}
