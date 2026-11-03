package dev.heartline.analytics

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class CaptureRhythmTest {
    @Test fun acfUsesFullEnergyRatherThanOverlapNormalizedCorrelation() {
        // np.correlate([1,-1,1,-1], same sequence, 'full')[3:] / 4 = [1,-.75,.5,-.25].
        val (lag, score) = acfMaximum(doubleArrayOf(1.0, -1.0, 1.0, -1.0), 1, 4)
        assertEquals(2, lag); assertEquals(.5, score, 0.0)
    }

    @Test fun smoothingUsesScipyReflectEdgesAndStrictThreshold() {
        assertTrue(rhythmRuns(DoubleArray(30) { .45 }).isEmpty())
        assertEquals(listOf(0 to 30), rhythmRuns(DoubleArray(30) { .45001 }))
        assertEquals(listOf(0 to 12), rhythmRuns(DoubleArray(12) { .9 }))
        assertTrue(rhythmRuns(DoubleArray(11) { .9 }).isEmpty())
    }

    @Test fun prototypeSixSampleLookaheadBridgesFiveOffSecondsButNotSix() {
        // 5-point smoothing preserves each central gap length for .9/0 plateaus (strict >.45).
        fun scores(gap: Int) = DoubleArray(50 + gap) { if (it in 25 until 25 + gap) 0.0 else .9 }
        assertEquals(listOf(0 to 55), rhythmRuns(scores(5)))
        // Six off-seconds end the run; the two runs are then joined as one set (<= 15 s apart), sixteen are not.
        assertEquals(listOf(0 to 56), rhythmRuns(scores(6)))
        assertEquals(listOf(0 to 65), rhythmRuns(scores(15)))
        assertEquals(listOf(0 to 25, 41 to 66), rhythmRuns(scores(16)))
    }

    @Test fun excludedGaitSecondsCannotBeBridgedIntoALiftingSet() {
        assertEquals(listOf(0 to 20, 23 to 50), rhythmRuns(DoubleArray(50) { .9 }, BooleanArray(50) { it in 20..22 }))
    }

    @Test fun pcaFindsTheDominantDirectionInARotatedCovariance() {
        val v = doubleArrayOf(1 / sqrt(3.0), 1 / sqrt(3.0), -1 / sqrt(3.0))
        val covariance = Array(3) { j -> DoubleArray(3) { k -> 5 * v[j] * v[k] + if (j == k) .1 else 0.0 } }
        val actual = dominantDirection(covariance)
        assertEquals(1.0, abs(actual.indices.sumOf { actual[it] * v[it] }), 1e-10)
    }

    @Test fun lowAmplitudeRhythmDoesNotNeedGyroOrMotionMagnitude() {
        val t = DoubleArray(90 * 25) { it / 25.0 }
        // A rotating wrist can be quiet in |a|, and machine sets can be far below the old .045 g gate.
        val xyz = Array(t.size) { i -> floatArrayOf((.008 * sin(2 * PI * t[i] / 4)).toFloat(), 0f, 1f) }
        val r = analyzeCapture(Recording(0.0, doubleArrayOf(), doubleArrayOf(), t, xyz), emptyList(), Profile(), "quiet machine")
        assertEquals(1, r.sets.size)
        assertEquals(22.0, r.sets.single().reps.toDouble(), 1.0)
        assertEquals(4.0, r.sets.single().tempoS!!, .08)
    }

    @Test fun noRepsAreCreatedAcrossPeriodicReconnectGap() {
        val t = ((0 until 1000).map { it / 25.0 } + (0 until 1000).map { 100 + it / 25.0 }).toDoubleArray()
        val xyz = Array(t.size) { i -> floatArrayOf((.2 * sin(2 * PI * t[i] / 4)).toFloat(), 0f, 1f) }
        val sets = captureRhythm(Recording(0.0, doubleArrayOf(), doubleArrayOf(), t, xyz), emptyList())
        assertEquals(2, sets.size)
        assertTrue(sets.first().end <= 40); assertTrue(sets.last().start >= 100)
        sets.forEach { assertEquals(10.0, it.reps.toDouble(), 1.0) }
    }

    @Test fun repCountingUsesExtendedWindowAndAcfTempoInsteadOfCorePeakCount() {
        // PCA projects a 4 s sinusoid identically in either orientation. Core=12..32; window=4..40 (peaks 5, 9 ... 37).
        val x = DoubleArray(50 * 25) { sin(2 * PI * it / (25 * 4)) }
        val (reps, period) = rhythmReps(listOf(x, DoubleArray(x.size), DoubleArray(x.size)), 12, 32)
        assertEquals(9, reps)
        assertEquals(4.0, period, .08)
    }

    @Test fun explicitCaptureDeadlineBoundsSetsEvenWhenSamplesContinue() {
        val t = DoubleArray(120 * 25) { it / 25.0 }
        val xyz = Array(t.size) { i -> floatArrayOf((.2 * sin(2 * PI * t[i] / 4)).toFloat(), 0f, 1f) }
        val sets = captureRhythm(Recording(0.0, doubleArrayOf(), doubleArrayOf(), t, xyz), emptyList(), 60.0)
        assertEquals(1, sets.size)
        assertTrue(sets.single().end <= 60)
        assertEquals(15.0, sets.single().reps.toDouble(), 1.0)
    }
}
