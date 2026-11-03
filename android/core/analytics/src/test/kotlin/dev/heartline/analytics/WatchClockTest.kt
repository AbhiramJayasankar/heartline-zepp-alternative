package dev.heartline.analytics

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class WatchClockTest {
    @Test fun oldCsvKeepsItsExactRetimingAndNewCsvUsesWatchTimeAndRemovesOnlyAnalysisWarmup() {
        val old = buildString {
            appendLine("ts,kind,ppg,ax,ay,az")
            repeat(100) { appendLine("${1000 + it * .04},ppg,27000,,,") }
        }
        val legacy = readRecordingCsv(old.reader())
        val expected = makeRecording(DoubleArray(100) { 1000 + it * .04 }, DoubleArray(100) { 27000.0 })
        assertArrayEquals(expected.ppgT, legacy.ppgT, 0.0)
        assertFalse(legacy.watchClock)
        val csv = buildString {
            appendLine("ts,kind,ppg,ax,ay,az,wts")
            repeat(200) { i ->
                val arrival = 1000 + i * .02 + if (i % 10 < 5) .06 else 0.0
                appendLine("$arrival,ppg,${if (i < 20) 3800 else 27000},,,,${200 + i * .02}")
                appendLine("$arrival,accel,,0,0,1,${200 + i / 26.16}")
            }
        }
        val rec = readRecordingCsv(csv.reader())
        assertTrue(rec.watchClock)
        assertEquals(180, rec.ppg.size)
        assertEquals(.4, rec.ppgT.first(), 1e-9)
        assertTrue(rec.ppg.all { it == 27000.0 })
        assertEquals(50.0, rec.fs, 1e-8)
        assertEquals(1 / 26.16, rec.accT[1] - rec.accT[0], 1e-9)
        assertArrayEquals(rec.accT, rec.accArrivalT, 0.0)
        assertEquals(200, csv.lines().drop(1).count { it.contains(",ppg,") })
    }

    @Test fun watchClockRetainsRateSwitchesGapsAndMissingStampFallback() {
        val csv = buildString {
            appendLine("ts,kind,ppg,ax,ay,az,wts")
            repeat(100) { i ->
                val t = if (i < 50) i * .04 else 2 + (i - 50) * .02
                appendLine("${1000 + t},ppg,27000,,,,${if (i in 40..44) "" else 200 + t}")
            }
            repeat(50) { i -> appendLine("${1010 + i * .02},ppg,27000,,,,${210 + i * .02}") }
        }
        val rec = readRecordingCsv(csv.reader())
        assertEquals(.04, rec.ppgT[25] - rec.ppgT[24], 1e-8)
        assertEquals(.02, rec.ppgT[65] - rec.ppgT[64], 1e-8)
        assertTrue(rec.ppgT.toList().zipWithNext().any { (a, b) -> b - a > 7 })
        assertTrue(rec.ppgT.toList().zipWithNext().all { (a, b) -> b > a })
    }

    @Test fun delayedUnstampedPacketsCannotReorderAuthoritativeWatchSamples() {
        val csv = buildString {
            appendLine("ts,kind,ppg,ax,ay,az,wts")
            repeat(200) { i ->
                val delayed = if (i in 80..89) .4 else 0.0
                appendLine("${1000 + i * .02 + delayed},ppg,27000,,,,${if (i in 80..89) "" else 200 + i * .02}")
            }
        }
        val rec = readRecordingCsv(csv.reader())
        assertEquals(180, rec.ppg.size)
        assertTrue(rec.ppgT.toList().zipWithNext().all { (a, b) -> b > a })
        assertEquals(1.8, rec.ppgT[70], 1e-8)
    }

    @Test fun fiftyHzCleanPulseHasSubTenMsBeatTimingAndImprovesOnTwentyFiveHz() {
        fun errors(fs: Double): DoubleArray {
            val t = DoubleArray((60 * fs).toInt()) { it / fs }
            val period = .837
            val phase = .213
            val signal = DoubleArray(t.size) { i ->
                val x = 2 * PI * (t[i] - phase) / period
                27000 + 800 * cos(x) + 250 * cos(3 * x)
            }
            val rec = makeRecording(t, signal, watchClock = true)
            assertEquals(fs, rec.fs, 1e-8)
            val beats = detectBeats(rec, Motion(doubleArrayOf(), doubleArrayOf()))
            return beats.times.filter { it in 5.0..55.0 }.map { time ->
                abs(time - (phase + round((time - phase) / period) * period))
            }.toDoubleArray().also { assertTrue(it.size > 50) }
        }
        val fast = errors(50.0)
        val slow = errors(25.0)
        println("synthetic peak error: 50 Hz max=${fast.max()} mean=${fast.average()}, 25 Hz max=${slow.max()} mean=${slow.average()}")
        assertTrue("50 Hz max error ${fast.max()}", fast.max() < .010)
        assertTrue("50 Hz should improve on 25 Hz", fast.average() < slow.average())
    }
}
