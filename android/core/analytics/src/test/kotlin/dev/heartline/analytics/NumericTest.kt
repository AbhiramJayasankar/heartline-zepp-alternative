package dev.heartline.analytics

import java.io.StringReader
import java.time.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class NumericTest {
    @Test fun constantFilterRoundoffMatchesScipyDespiteUnspecifiedTiedPeakOrder() {
        val p = fixture("goldens/primitives.json").obj()
        val y = zeroPhase(DoubleArray(p["constant_length"].number().toInt()) { 40000.0 }, butterworth(100.0, .5, 4.0))
        val expected = p["constant_filtered"].array()
        for (i in y.indices) assertEquals("constant[$i]", expected[i].number(), y[i], 1e-18)
        val result = checkNotNull(javaClass.getResourceAsStream("/recordings/flat.csv")).bufferedReader().use {
            analyzeSession(readRecordingCsv(it), "flat.csv")
        }
        println("DIAGNOSTIC constant nonzero input: exact filtered samples; " +
            "NumPy's unstable equal-height peak order differs. Kotlin beats=${result.beats}, " +
            "HR=${result.hrBpm}, RMSSD=${result.hrv.rmssdMs}, rhythm=${result.rhythm.status}.")
    }
    @Test fun butterworthAndOddPaddingMatchScipy() {
        val p = fixture("goldens/primitives.json").obj()
        val x = p["x"].array().map { it.number() }.toDoubleArray()
        for (f in p["filters"].array()) {
            val c = f.obj()
            val sos = butterworth(c["fs"].number(), c["lo"].number(), c["hi"].nullableNumber())
            val expected = c["sos"].array()
            for (i in sos.indices) {
                val s = sos[i]
                val actual = listOf(s.b0, s.b1, s.b2, 1.0, s.a1, s.a2)
                for (j in actual.indices) assertEquals("SOS[$i][$j]", expected[i].array()[j].number(), actual[j], 2e-14)
            }
            val y = zeroPhase(x, sos)
            val want = c["y"].array()
            for (i in y.indices) assertEquals("filtered[$i]", want[i].number(), y[i], 1e-10)
        }
    }

    @Test fun burstRetimingMatchesPythonIncludingRateChange() {
        val p = fixture("goldens/primitives.json").obj()
        val times = retime(p["stamps"].array().map { it.number() }.toDoubleArray())
        val expected = p["retimed"].array()
        for (i in times.indices) assertEquals(expected[i].number(), times[i], 1e-12)
        assertEquals(50.0, 1 / median(DoubleArray(799) { times[it + 1] - times[it] }), 5.0)
        assertEquals(25.0, 1 / median(DoubleArray(799) { times[times.size - 799 + it] - times[times.size - 800 + it] }), 2.5)
    }

    @Test fun tinyAndEmptyRecordingsKeepReferenceShapes() {
        assertEquals(0.0, retime(doubleArrayOf()).size.toDouble(), 0.0)
        assertArrayEquals(doubleArrayOf(0.0, -1.0, 2.0), retime(doubleArrayOf(10.0, 9.0, 12.0)), 0.0)
        val one = makeRecording(doubleArrayOf(123.0), doubleArrayOf(42.0), doubleArrayOf(123.0), arrayOf(floatArrayOf(0f, 0f, 1f)))
        assertEquals(123.0, one.start, 0.0)
        assertTrue(one.acc.isEmpty())
        val result = analyzeSession(one, "one")
        assertNull(result.hrBpm); assertNull(result.hrv.rmssdMs); assertNull(result.respiratoryRate)
        assertEquals("insufficient_data", result.rhythm.status)
    }

    @Test fun csvIgnoresOtherSensorsAndSupportsQuotedFields() {
        val csv = "ts,kind,ppg,ax,ay,az\n10,gyro,,1,2,3\n10,\"ppg\",100,,,\n10,accel,,0,0,1\n11,ppg,200,,,\n11,mag,,1,2,3\n"
        val r = readRecordingCsv(StringReader(csv))
        assertArrayEquals(doubleArrayOf(100.0, 200.0), r.ppg, 0.0)
        assertArrayEquals(doubleArrayOf(0.0, 1.0), r.ppgT, 0.0)
        assertEquals(1, r.acc.size)
        assertEquals(0.0, r.accT[0], 0.0)
    }

    @Test fun droppedBeatBreaksIntervalChain() {
        val iv = intervals(doubleArrayOf(0.0, 1.0, 2.0, 3.0, 4.0), booleanArrayOf(true, true, false, true, true))
        assertArrayEquals(doubleArrayOf(1.0, 4.0), iv.t, 0.0)
        assertArrayEquals(doubleArrayOf(1.0, 1.0), iv.rr, 0.0)
    }

    @Test fun ectopicPairAndDetectionArtifactsDiffer() {
        val rr = DoubleArray(30) { 0.8 }
        rr[10] = 0.5; rr[11] = 1.1; rr[20] = 1.5
        val (ectopic, artifact) = flagEctopic(rr)
        assertEquals(2, ectopic.count { it }); assertTrue(ectopic[10] && ectopic[11])
        assertEquals(1, artifact.count { it }); assertTrue(artifact[20])
    }

    @Test fun timeDomainUsesSampleDeviationAndPercentagePnn50() {
        val rr = DoubleArray(40) { if (it % 2 == 0) .8 else .86 }
        val r = hrvMetrics(Intervals(DoubleArray(40) { it.toDouble() }, rr, BooleanArray(40), BooleanArray(40)))
        assertEquals(830.0, r.meanRrMs!!, 0.0)
        assertEquals(60.0, r.rmssdMs!!, 0.0)
        assertEquals(100.0, r.pnn50!!, 0.0)
        assertEquals(30.4, r.sdnnMs!!, 0.0)
        assertNull(r.hf)
    }

    @Test fun baevskyHistogramAndRandomnessConventions() {
        val nn = DoubleArray(50) { if (it < 40) .8 else .85 }
        assertEquals(969.7, stressIndex(nn)!!, 0.1)
        assertNull(stressIndex(DoubleArray(50) { .8 }))
        val (tpr, random) = turningPointRatio(DoubleArray(60) { it.toDouble() })
        assertEquals(0.0, tpr, 0.0); assertFalse(random)
    }

    @Test fun peaksApplyDistanceBeforeProminenceAndUsePlateauMidpoint() {
        assertArrayEquals(intArrayOf(2, 7), findPeaks(doubleArrayOf(0.0, 1.0, 2.0, 2.0, 0.0, 1.0, 0.0, 3.0, 0.0), 3, 1.5))
    }

    @Test fun healthUsesLocalDayMinimumForRestingHrMeanForOthersAndSince() {
        val zone = ZoneId.of("Asia/Kolkata")
        val date = LocalDate.of(2026, 11, 1)
        val t = date.atStartOfDay(zone).toEpochSecond()
        val values = listOf(HealthValue("resting_hr", t, 60.0), HealthValue("resting_hr", t + 1, 50.0),
            HealthValue("spo2", t, 90.0), HealthValue("spo2", t + 2, 98.0), HealthValue("spo2", t - 1, 99.0))
        val by = healthByDay(values, t, zone)
        assertEquals(mapOf(date to 50.0), by["resting_hr"])
        assertEquals(mapOf(date to 94.0), by["spo2"])
    }

    @Test fun hrvRequiresQualifiedChecksAndTakesDailyMedian() {
        val checks = listOf(CheckSummary(1000.0, 30.0, 60.0), CheckSummary(1001.0, 40.0, 60.0),
            CheckSummary(1002.0, 999.0, 60.0, .699, 60.0), CheckSummary(1003.0, 999.0, 60.0, .7, 44.9))
        assertEquals(35.0, hrvByDay(checks, ZoneOffset.UTC).values.single(), 0.0)
    }

    @Test fun sleepBridgeThresholdAndNoDataBreak() {
        fun m(i: Int, state: String) = MinuteSample(i * 60L, 0, 0, 0, 50, 0, 0, 0, state)
        val ms = listOf(m(0, "sleep_deep")) + (1..30).map { m(it, "idle") } + m(31, "sleep_rem") +
            m(32, "not_worn") + m(33, "sleep_light")
        val blocks = sleepBlocks(ms)
        assertEquals(2, blocks.size); assertEquals(30, blocks[0].awake)
        assertEquals(2, blocks[0].asleep); assertEquals(1, blocks[1].asleep)
    }

    @Test fun tanakaAndScoresUseNearestEvenRounding() {
        assertEquals(204, hrMaxOf(Profile(age = 5))) // 204.5 -> even
        assertEquals(100, sleepScore(480, 95.0, 63, 96))
        assertEquals(2.0, rounded(2.5), 0.0)
        assertEquals(2.67, rounded(2.675, 2), 0.0)
    }
}
