package dev.heartline.protocol

import org.junit.Test
import kotlin.test.*

class WorkoutsTest {
    @Test fun capturedSummaryAndTrack() {
        val w = parseWorkoutSummary(blob("workout_summary"))!!
        assertEquals(515, w.version)
        assertEquals("Strength training", w.type)
        assertEquals(listOf(1477, 498, 158, 109, 162), listOf(w.durationS, w.steps, w.calories, w.avgHr, w.maxHr))
        val track = parseWorkoutDetails(blob("workout_details"))
        assertEquals(505, track.size)
        assertEquals(TrackPoint(17, 72), track.first())
        assertEquals(162, track.maxOf { it.hr })
        assertTrue(track.zipWithNext().all { (a, b) -> b.t >= a.t })
        assertEquals(505, w.copy(track = track).track.size)
    }
    @Test fun everySummaryAndTrackFieldMatchesPython() {
        val expected = oracle["workout"].obj()
        val w = parseWorkoutSummary(blob("workout_summary"))!!
        val actual = mapOf("version" to w.version, "start" to w.start, "end" to w.end, "type" to w.type,
            "type_code" to w.typeCode, "duration_s" to w.durationS, "active_s" to w.activeS, "steps" to w.steps,
            "calories" to w.calories, "distance_m" to w.distanceM, "avg_hr" to w.avgHr, "max_hr" to w.maxHr,
            "avg_speed_ms" to w.avgSpeedMs, "avg_cadence" to w.avgCadence)
        expected.forEach { (k, v) -> if (v is Number) assertEquals(v.toDouble(), (actual[k] as Number).toDouble(), k) else assertEquals(v, actual[k], k) }
        assertEquals(oracle["track"].arr().map { it.obj() }.map { TrackPoint(it["t"].num().toInt(), it["hr"].num().toInt()) }, parseWorkoutDetails(blob("workout_details")))
    }
    @Test fun shortUnknownAndUnsupportedSummaries() {
        assertNull(parseWorkoutSummary(ByteArray(23)))
        for (version in listOf(511, 515, 519)) {
            val raw = buffer(24).putShort(version.toShort()).putShort(999).putInt(100).putInt(160).array()
            val w = parseWorkoutSummary(raw)!!
            assertEquals("Type 999", w.type)
            assertEquals(60, w.durationS)
            assertNull(w.activeS)
            assertNull(w.avgHr)
        }
        assertEquals(WORKOUT_TYPES.size, WORKOUT_TYPES.values.toSet().size)
    }
    @Test fun version516ExtensionZeroHrAndTiesToEven() {
        val raw = ByteArray(144)
        val b = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.putShort(0, 516).putShort(2, 1).putInt(4, 100).putInt(8, 200)
        b.putInt(28, 20).putInt(32, -1).putFloat(52, 158.5f).putFloat(56, 123.5f)
        b.putFloat(88, 1.234f).putFloat(112, 1.5f)
        val w = parseWorkoutSummary(raw)!!
        assertEquals(20, w.steps)
        assertEquals(-1, w.activeS)
        assertEquals(158, w.calories)
        assertEquals(124, w.distanceM)
        assertEquals(1.23, w.avgSpeedMs)
        assertEquals(90, w.avgCadence)
        assertNull(w.avgHr)
        assertNull(w.maxHr)
    }
    @Test fun detailsOffsetsWrapAndPackedTriplesAreAbsolute() {
        val raw = bytes(1, 250, 70, 0, 0, 0, 0, 0, 2, 255, 0, 0, 0, 0, 0, 0,
            1, 4, 80, 0, 0, 0, 0, 0, 1, 8, 10, 90, 20, 0, 30, 100, 1, 9, 0, 0, 0, 0, 0, 0)
        assertEquals(listOf(TrackPoint(250, 70), TrackPoint(259, 80), TrackPoint(10, 90), TrackPoint(30, 100)), parseWorkoutDetails(raw + bytes(1, 2, 3)))
        assertTrue(parseWorkoutDetails(ByteArray(7)).isEmpty())
    }
}
