package dev.heartline.analytics

import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
import kotlin.math.*

class WorkoutCaptureTest {
    private val epoch = 1_700_000_000.0
    private fun recording(duration: Int, movement: (Double) -> FloatArray, gyro: (Double) -> FloatArray = { floatArrayOf(0f, 0f, 0f) }): Recording {
        val t = DoubleArray(duration * 25) { it / 25.0 }
        return Recording(epoch, doubleArrayOf(), doubleArrayOf(), t, Array(t.size) { movement(t[it]) },
            t, Array(t.size) { gyro(t[it]) }, captureDurationS = duration.toDouble())
    }
    private fun hr(duration: Int, bpm: (Int) -> Int) = (0 until duration).map { epoch + it to bpm(it) }
    private fun still(t: Double) = floatArrayOf(0f, 0f, 1f)

    @Test fun csvRetainsGyroAndMagAxesAndOffsetsWithoutChangingPpgClock() {
        val rec = readRecordingCsv(StringReader("ts,kind,ppg,ax,ay,az\n10,ppg,100,,,\n11,ppg,200,,,\n10.5,gyro,,-1,2,3\n12,mag,,4,5,-6\n"))
        assertArrayEquals(doubleArrayOf(0.0, 1.0), rec.ppgT, 0.0)
        assertArrayEquals(doubleArrayOf(.5), rec.gyroT, 0.0)
        assertArrayEquals(floatArrayOf(-1f, 2f, 3f), rec.gyro.single(), 0f)
        assertArrayEquals(doubleArrayOf(2.0), rec.magT, 0.0)
        assertEquals(-6f, rec.mag.single()[2])
    }

    @Test fun motionOnlyCsvIsRetainedEvenWithoutPpg() {
        val rec = readRecordingCsv(StringReader("ts,kind,ppg,ax,ay,az\n10,accel,,0,0,1\n11,gyro,,1,2,3\n"))
        assertEquals(10.0, rec.start, 0.0); assertEquals(1, rec.acc.size); assertEquals(1, rec.gyro.size)
    }

    @Test fun fiveKnownZonesProduceEdwardsMinutesAndFiveSecondHrBins() {
        val r = analyzeCapture(recording(300, ::still), hr(300) { 100 + it / 60 * 20 }, Profile(maxHr = 200), "zones")
        assertEquals(listOf(60, 60, 60, 60, 60), r.zones.map { it.seconds })
        assertEquals(listOf(100, 120, 140, 160, 180), r.zones.map { it.lowBpm })
        assertEquals(15.0, r.load!!, 1e-8); assertEquals(140, r.avgHr); assertEquals(100, r.minHr); assertEquals(180, r.maxHr)
        assertEquals(60, r.hr.size); assertEquals(5.0, r.hr[1].t, 0.0); assertNull(r.calories)
        assertEquals(0, r.steps); assertTrue(r.sets.isEmpty()); assertTrue(r.segments.all { it.kind == "rest" })
    }

    @Test fun maxHrUses220MinusAgeThen190Fallback() {
        val rec = recording(60, ::still)
        assertEquals(100, analyzeCapture(rec, hr(60) { 100 }, Profile(age = 20), "").zones.first().lowBpm)
        assertEquals(95, analyzeCapture(rec, hr(60) { 100 }, Profile(), "").zones.first().lowBpm)
    }

    @Test fun keytelSexSpecificKilojoulesPerMinuteAreConvertedToKcal() {
        for (sex in listOf("male", "female")) {
            val p = Profile(age = 30, sex = sex, weightKg = 70.0)
            val r = analyzeCapture(recording(120, ::still), hr(120) { 150 }, p, "")
            val kj = if (sex == "male") -55.0969 + .6309 * 150 + .1988 * 70 + .2017 * 30 else -20.4022 + .4472 * 150 - .1263 * 70 + .074 * 30
            assertEquals((kj / 4.184 * 2).roundToInt(), r.calories)
        }
        assertNull(analyzeCapture(recording(60, ::still), hr(60) { 100 }, Profile(30, "unknown", 70.0), "").calories)
    }

    @Test fun hrGapsAndInvalidValuesAreNotFilledWithPpgOrCountedAsLoad() {
        val r = analyzeCapture(recording(60, ::still), listOf(epoch to 150, epoch + 20 to 0, epoch + 30 to 255, epoch + 55 to 150), Profile(maxHr = 200), "gap")
        assertEquals(10, r.zones.sumOf { it.seconds }); assertEquals(.5, r.load!!, 1e-8)
        assertEquals(2, r.hr.size); assertTrue(r.notes.any { "missing intervals" in it })
        val empty = analyzeCapture(recording(60, ::still), emptyList(), Profile(), "")
        assertNull(empty.avgHr); assertNull(empty.load); assertNull(empty.calories); assertTrue(empty.hr.isEmpty())
    }

    @Test fun walkingAt108SpmProducesStepsAndNoLiftingSets() {
        val r = analyzeCapture(recording(90, { t -> floatArrayOf(0f, 0f, (1 + .25 * sin(2 * PI * 1.8 * t)).toFloat()) }), hr(90) { 110 }, Profile(), "walk")
        assertTrue(r.segments.toString(), r.segments.any { it.kind == "walking" && abs(it.cadenceSpm!! - 108) < 5 })
        assertEquals(162.0, r.steps!!.toDouble(), 10.0); assertTrue(r.sets.isEmpty())
    }

    @Test fun runningAt168SpmIsDistinctFromWalking() {
        val r = analyzeCapture(recording(60, { t -> floatArrayOf(0f, 0f, (1 + .5 * sin(2 * PI * 2.8 * t)).toFloat()) }), hr(60) { 160 }, Profile(), "run")
        assertTrue(r.segments.toString(), r.segments.all { it.kind == "running" })
        assertEquals(168.0, r.steps!!.toDouble(), 10.0)
    }

    @Test fun twoTenRepSetsAtThreeSecondTempoHaveSixtySecondRecovery() {
        fun lifting(t: Double) = t in 10.0..<40.0 || t in 110.0..<140.0
        val rec = recording(150, { t -> floatArrayOf(if (lifting(t)) (.3 * sin(2 * PI * (t - 10) / 3)).toFloat() else 0f, 0f, 1f) },
            { t -> floatArrayOf(0f, 0f, if (lifting(t)) (500 + 150 * sin(2 * PI * (t - 10) / 3)).toFloat() else 500f) })
        val r = analyzeCapture(rec, hr(150) { if (it in 40..110) 150 - (it - 40) / 2 else 140 }, Profile(), "weights")
        assertEquals(r.segments.toString(), 2, r.sets.size)
        r.sets.forEach { assertEquals(10.0, it.reps.toDouble(), 1.0); assertEquals(3.0, it.tempoS!!, .25); assertNotNull(it.avgHr); assertNotNull(it.peakHr) }
        assertEquals(30.0, r.rests.single().drop60!!.toDouble(), 3.0)
    }

    @Test fun slowAndFastKnownRepetitionRatesAreCounted() {
        // The tuned ACF searches periods >=1.2 s; a 1 Hz signal aliases to its second harmonic.
        for (hz in listOf(.2, .5, .8)) {
            val rec = recording(60, { t -> floatArrayOf((.3 * sin(2 * PI * hz * t)).toFloat(), 0f, 1f) },
                { t -> floatArrayOf(0f, 0f, (500 + 100 * sin(2 * PI * hz * t)).toFloat()) })
            val r = analyzeCapture(rec, hr(60) { 130 }, Profile(), "")
            assertEquals("$hz: ${r.segments}", 1, r.sets.size)
            assertEquals(60 * hz, r.sets.single().reps.toDouble(), 2.0)
            assertEquals(1 / hz, r.sets.single().tempoS!!, .3)
        }
    }

    @Test fun reconnectMotionGapIsOtherAndNeverManufacturesRepsOrSteps() {
        val t = (0 until 500).map { it / 25.0 } + (0 until 500).map { 80 + it / 25.0 }
        val rec = Recording(epoch, doubleArrayOf(), doubleArrayOf(), t.toDoubleArray(), Array(t.size) { floatArrayOf(0f, 0f, 1f) }, captureDurationS = 100.0)
        val r = analyzeCapture(rec, emptyList(), Profile(), "gap")
        assertTrue(r.segments.any { it.kind == "other" && it.end - it.start >= 55 })
        assertTrue(r.sets.isEmpty()); assertEquals(0, r.steps)
    }

    @Test fun realGymRecordingStartsStillThenWalksAboutOneMinuteWithNoLifting() {
        val rec = javaClass.getResourceAsStream("/recordings/capture_gym_trimmed.csv")!!.bufferedReader().use(::readRecordingCsv)
        val r = analyzeCapture(rec, emptyList(), Profile(), "real gym")
        println("Real fixture segments: ${r.segments}")
        assertTrue(rec.gyro.size > 3000); assertTrue(rec.mag.size > 3000)
        assertTrue(r.segments.toString(), r.segments.filter { it.kind == "rest" && it.start < 40 }.sumOf { min(40.0, it.end) - it.start } >= 30)
        val walk = r.segments.filter { it.kind == "walking" && it.start < 125 && it.end > 50 }
        assertTrue(r.segments.toString(), walk.sumOf { it.end - it.start } in 45.0..85.0)
        assertTrue(walk.toString(), walk.all { it.cadenceSpm in 90..130 })
        assertTrue(r.sets.toString(), r.sets.isEmpty())
    }

    @Test fun isolatedImpactsAndUnstructuredMotionDoNotBecomeSetsOrSustainedGait() {
        val rng = kotlin.random.Random(7)
        val rec = recording(120, { t ->
            val amplitude = if (t in 10.0..11.0 || t in 70.0..71.0) .8 else .003
            floatArrayOf((rng.nextDouble(-1.0, 1.0) * amplitude).toFloat(), 0f, (1 + rng.nextDouble(-1.0, 1.0) * amplitude).toFloat())
        })
        val r = analyzeCapture(rec, emptyList(), Profile(), "impacts")
        assertTrue(r.sets.isEmpty()); assertEquals(0, r.steps)
    }

    @Test fun sustainedRhythmHasNoArbitraryTwoMinuteSetLimit() {
        val rec = recording(180, { t -> floatArrayOf((.3 * sin(2 * PI * .4 * t)).toFloat(), 0f, 1f) },
            { t -> floatArrayOf(0f, 0f, (500 + 100 * sin(2 * PI * .4 * t)).toFloat()) })
        val r = analyzeCapture(rec, emptyList(), Profile(), "long")
        // The session-tuned prototype has a minimum length, but no upper duration gate.
        assertEquals(1, r.sets.size)
        assertEquals(72.0, r.sets.single().reps.toDouble(), 2.0)
    }

    @Test fun tenSecondsOfPeriodicMovementDoesNotBecomeSustainedGait() {
        val rec = recording(60, { t -> floatArrayOf(0f, 0f,
            if (t in 20.0..<30.0) (1 + .25 * sin(2 * PI * 1.8 * t)).toFloat() else 1f) })
        val r = analyzeCapture(rec, emptyList(), Profile(), "brief movement")
        assertEquals(0, r.steps); assertTrue(r.segments.none { it.kind == "walking" || it.kind == "running" })
    }
}
