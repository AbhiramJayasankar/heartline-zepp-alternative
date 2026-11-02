package dev.heartline.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test
import kotlin.test.*

class WorkoutExtTest {
    private fun summary(type: Int = 1) = ByteBuffer.allocate(200).order(ByteOrder.LITTLE_ENDIAN)
        .putShort(0, 515).putShort(2, type.toShort()).putInt(4, 1000).putInt(8, 1100).putInt(20, -20000)

    @Test fun outdoorTerrainExtensionUsesFloatDistancesAndIntMillisecondsAt140() {
        // HuamiActivitySummaryParser: unknown int @140, (float metres, int milliseconds) ×3.
        val b = summary().putInt(140, 0x12345678).putFloat(144, 12.5f).putInt(148, 12500)
            .putFloat(152, 4.25f).putInt(156, 4321).putFloat(160, 80f).putInt(164, 80123)
        for (type in listOf(1, 4, 10, 15)) {
            b.putShort(2, type.toShort())
            assertEquals(WorkoutTerrain(12.5, 12, 4.25, 4, 80.0, 80), parseWorkoutSummaryExt(b.array())!!.terrain)
        }
        b.putShort(2, 2) // Gadgetbridge explicitly excludes treadmill: tail may contain garbage/pointers.
        assertNull(parseWorkoutSummaryExt(b.array())!!.terrain)
    }

    @Test fun swimmingExtensionUsesOffsets140164168176178180181() {
        // HuamiActivitySummaryParser swimming branch: 5 unknown ints after stroke distance; one after lap pace.
        val b = summary(6).putFloat(140, 1.75f).putFloat(164, 0.5f).putFloat(168, 129f)
            .putShort(176, 36).putShort(178, 92).put(180, 3).put(181, 12)
        for (type in listOf(6, 7)) {
            b.putShort(2, type.toShort())
            assertEquals(WorkoutSwimming(1.75, 0.5, 129.0, 36, 92, 3, 12), parseWorkoutSummaryExt(b.array())!!.swimming)
        }
        assertNull(parseWorkoutSummaryExt(b.array().copyOf(189))!!.swimming)
    }

    @Test fun floatCadenceAndStrideUseGadgetbridgeMathRoundNotPythonTiesToEven() {
        val b = summary().putFloat(100, 0.025f).putFloat(104, 0.025f).putFloat(108, 0.025f)
            .putFloat(112, 0.125f).putFloat(116, 0.125f).putFloat(120, 0.125f)
        val s = parseWorkoutSummaryExt(b.array())!!
        assertEquals(listOf(2, 2, 2), listOf(s.maxCadence, s.minCadence, s.avgCadence))
        assertEquals(listOf(13, 13, 13), listOf(s.maxStrideCm, s.minStrideCm, s.avgStride2Cm))
    }

    @Test fun negativeCoordinatesUnsignedEpochsAndSignedAltitudeArePreserved() {
        val b = summary().putInt(4, -1).putInt(8, -1).putInt(12, -200000).putInt(16, -300000).putInt(20, -15)
            .putInt(32, 40).putInt(36, -50).putInt(40, 60).putInt(44, -70)
            .putFloat(64, -10f).putFloat(68, -20f).putFloat(72, -15f)
        val s = parseWorkoutSummaryExt(b.array())!!
        assertEquals(4294967295L, s.start); assertEquals(s.start, s.end)
        assertEquals(listOf(-200000, -300000, -15, 40, -50, 60, -70),
            listOf(s.baseLongitude, s.baseLatitude, s.baseAltitudeM, s.maxLatitude, s.minLatitude, s.maxLongitude, s.minLongitude))
        assertEquals(listOf(-10.0, -20.0, -15.0), listOf(s.maxAltitudeM, s.minAltitudeM, s.avgAltitudeM))
    }

    @Test fun firmwareAltitudeAndPaceSentinelsAndNonFiniteValuesStayAbsent() {
        val b = summary().putFloat(64, -100000f).putFloat(68, 100000f).putFloat(72, -20000f)
            .putFloat(48, Float.NaN).putFloat(76, Float.POSITIVE_INFINITY).putFloat(88, 9999f)
            .putFloat(100, Float.NaN).putShort(132, 0).putShort(138, -1)
        val s = parseWorkoutSummaryExt(b.array())!!
        assertNull(s.baseAltitudeM); assertNull(s.maxAltitudeM); assertNull(s.minAltitudeM); assertNull(s.avgAltitudeM)
        assertNull(s.caloriesKcal); assertNull(s.maxSpeedMs); assertNull(s.minPaceSPerM); assertNull(s.maxCadence)
        assertNull(s.avgHr); assertNull(s.maxHr)
    }

    @Test fun shortOrOtherSummaryVersionsAreNotInterpretedAs515() {
        for (length in 0..139) assertNull(parseWorkoutSummaryExt(summary().array().copyOf(length)))
        for (version in listOf(259, 511, 512, 514, 516, 519)) assertNull(parseWorkoutSummaryExt(summary().putShort(0, version.toShort()).array()))
        assertNull(parseWorkoutSummaryExt(summary().array().copyOf(167))!!.terrain)
    }

    @Test fun altitudeDeltasPauseResumeAndOffsetWrapFollowHuamiActivityDetailsParser() {
        // GPS: LE signed16 lon,lat,alt; wrap 250 -> 4 adds 4 (upstream does not add 256).
        val b = summary().putInt(20, 100).array()
        val raw = bytes(0, 250, 0, 0, 0, 0, 2, 0, 0, 4, 0, 0, 0, 0, 253, 255,
            2, 5, 0, 0, 255, 255, 255, 255, 3, 8, 1, 2, 3, 4, 5, 6)
        val d = parseWorkoutDetailsExt(raw, b)
        assertEquals(listOf(WorkoutValuePoint(250, 102.0), WorkoutValuePoint(254, 99.0)), d.altitudeM)
        assertEquals(listOf(255), d.pauses); assertEquals(listOf(258), d.resumes)
        assertEquals(listOf(0, 0, 255, 255, 255, 255), d.undecoded.first().payload)
        assertTrue(parseWorkoutDetailsExt(raw).altitudeM.isEmpty())
        assertTrue(parseWorkoutDetailsExt(raw, summary().array()).altitudeM.isEmpty())
    }

    @Test fun altitudeSeriesSharesExistingRouteInitialTimestampFixup() {
        val b = summary().putInt(12, 216000000).putInt(16, 57000000).putInt(20, 10).array()
        val raw = bytes(0, 0, 1, 0, 1, 0, 1, 0, 0, 0, 1, 0, 1, 0, 1, 0, 1, 10, 70, 0, 0, 0, 0, 0)
        val d = parseWorkoutDetailsExt(raw, b)
        assertEquals(listOf(WorkoutValuePoint(0, 11.0), WorkoutValuePoint(5, 12.0)), d.altitudeM)
        assertEquals(parseWorkoutRoute(raw, b).map { it.t }, d.altitudeM.map { it.t })
    }

    @Test fun packedHrTriplesRemainCompatibleAndDoNotBecomeSpeedOrCadence() {
        val raw = bytes(1, 10, 1, 70, 2, 80, 3, 90, 1, 20, 100, 0, 0, 0, 0, 0)
        assertEquals(listOf(TrackPoint(1, 70), TrackPoint(2, 80), TrackPoint(3, 90), TrackPoint(20, 100)), parseWorkoutDetailsExt(raw).hr)
    }

    @Test fun unimplementedSpeedSwimmingUnknownRecordsAndPartialTailRemainOpaque() {
        // consumeSpeed4/5/6, consumeSwimming upstream return 6 without decoding ANY payload fields.
        val raw = listOf(4, 5, 6, 8, 255).flatMap { listOf(it, 1, 10, 20, 30, 40, 50, 60) }.map { it.toByte() }.toByteArray()
        val d = parseWorkoutDetailsExt(raw + bytes(1, 2, 3))
        assertEquals(listOf(4, 5, 6, 8, 255), d.undecoded.map { it.type })
        assertTrue(d.undecoded.all { it.payload == listOf(10, 20, 30, 40, 50, 60) })
        assertTrue(d.speedMs.isEmpty() && d.cadence.isEmpty() && d.steps.isEmpty() && d.distanceM.isEmpty())
        assertEquals(3, d.trailingBytes)
        for (length in 0..7) assertEquals(length, parseWorkoutDetailsExt(ByteArray(length)).trailingBytes)
    }
}
