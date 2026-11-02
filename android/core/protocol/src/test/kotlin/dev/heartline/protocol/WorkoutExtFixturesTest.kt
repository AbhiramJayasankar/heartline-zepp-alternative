package dev.heartline.protocol

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.*

@RunWith(Parameterized::class)
class WorkoutExtFixturesTest(private val index: Int, private val capture: Map<String, Any?>) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "capture {0}") fun captures(): List<Array<Any>> =
            resource("workout_ext_real.json").obj()["captures"].arr().mapIndexed { index, it -> arrayOf(index, it.obj()) }
    }
    private val summary = hex(capture["summary"].str())
    private val details = hex(capture["details"].str())

    @Test fun everyNamedV515FieldMatchesGadgetbridgeByteBufferReads() {
        val parsed = parseWorkoutSummaryExt(summary)!!
        val expected = capture["expected"].obj()
        // Check every property, and fail if a new field escapes the real-fixture oracle.
        val fields = parsed.javaClass.declaredFields.filter { !it.isSynthetic }.associateBy { it.name }
        assertEquals(expected.keys + setOf("terrain", "swimming"), fields.keys)
        expected.forEach { (name, value) ->
            val field = fields.getValue(name).apply { isAccessible = true }
            val actual = field.get(parsed)
            if (value is Number) assertEquals(value.toDouble(), (actual as Number).toDouble(), 0.00000001, "capture $index $name")
            else assertEquals(value, actual, "capture $index $name")
        }
        assertNull(parsed.terrain) // The real captures are treadmill and strength, not outdoor running.
        assertNull(parsed.swimming)
    }

    @Test fun originalSummaryStillMatchesAllStoredWorkoutJsonFields() {
        val j = capture["stored"].obj()
        val w = parseWorkoutSummary(summary)!!
        val actual = mapOf("start" to w.start, "end" to w.end, "version" to w.version, "type_code" to w.typeCode,
            "duration_s" to w.durationS, "active_s" to w.activeS, "avg_hr" to w.avgHr, "max_hr" to w.maxHr,
            "calories" to w.calories, "distance_m" to w.distanceM, "steps" to w.steps,
            "avg_speed_ms" to w.avgSpeedMs, "avg_cadence" to w.avgCadence, "type" to w.type)
        assertEquals(j.keys, actual.keys)
        j.forEach { (name, value) ->
            if (value is Number) assertEquals(value.toDouble(), (actual[name] as Number).toDouble(), 0.000001, name)
            else assertEquals(value, actual[name], name)
        }
    }

    @Test fun everyCapturedHrSampleAndRecordTypeMatchesGadgetbridgeAndStoredTrack() {
        val expected = capture["detailsExpected"].obj()
        val d = parseWorkoutDetailsExt(details, summary)
        val hr = expected["hr"].arr().map { it.arr().let { pair -> TrackPoint(pair[0].num().toInt(), pair[1].num().toInt()) } }
        assertEquals(hr, d.hr)
        assertEquals(expected["pauses"].arr().map { it.num().toInt() }, d.pauses)
        assertEquals(expected["resumes"].arr().map { it.num().toInt() }, d.resumes)
        val counts = expected["recordCounts"].obj().mapKeys { it.key.toInt() }.mapValues { it.value.num().toInt() }
        assertEquals(counts.filterKeys { it != 0 && it != 1 }, d.undecoded.groupingBy { it.type }.eachCount())
        assertEquals(0, d.trailingBytes)
        assertTrue(d.altitudeM.isEmpty()) // No GPS records, altitude is -20000 sentinel in all 14 captures.
        assertTrue(d.speedMs.isEmpty() && d.cadence.isEmpty() && d.steps.isEmpty() && d.distanceM.isEmpty())
        assertEquals(255, d.undecoded.first().type) // ff 00 08 20 01 00 00 00: unknown header, not speed.
        assertEquals(listOf(8, 32, 1, 0, 0, 0), d.undecoded.first().payload)
    }

    @Test fun treadmillSpeedAndCadenceArePhysicallyPlausibleWithoutRescalingGuesses() {
        val s = parseWorkoutSummaryExt(summary)!!
        if (s.typeCode == 2) {
            for (speed in listOf(s.minSpeedMs, s.avgSpeedMs, s.maxSpeedMs)) assertTrue(speed!! * 3.6 in 1.0..15.0)
            for (cadence in listOf(s.minCadence, s.avgCadence, s.maxCadence)) assertTrue(cadence!! in 60..200)
            assertTrue(s.minSpeedMs!! <= s.avgSpeedMs!! && s.avgSpeedMs!! <= s.maxSpeedMs!!)
            assertEquals(73, s.avgStrideCm)
            assertTrue(s.avgPaceSPerKm!! in 900..1000)
        } else {
            assertEquals(52, s.typeCode)
            assertEquals(0.0, s.distanceM)
        }
    }
}
