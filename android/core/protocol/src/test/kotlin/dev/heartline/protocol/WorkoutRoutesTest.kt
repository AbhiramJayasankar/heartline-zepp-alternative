package dev.heartline.protocol

import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class WorkoutRoutesTest {
    private fun summary(lon: Int = 216_000_000, lat: Int = 57_000_000, alt: Int = 100): ByteArray =
        ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putShort(515).putShort(1).putInt(1000).putInt(2000)
            .putInt(lon).putInt(lat).putInt(alt).array()
    private fun gps(t: Int, lon: Int, lat: Int, alt: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put(0).put(t.toByte()).putShort(lon.toShort()).putShort(lat.toShort()).putShort(alt.toShort()).array()

    @Test fun huamiSummaryBasePlusSignedGpsDeltasAndMetreAltitude() {
        // Summary offsets 12/16/20 are lon=72*3e6, lat=19*3e6, alt=100m. Type 0 deltas are LE i16.
        val route = parseWorkoutRoute(gps(0, 300, -600, 5) + gps(10, -150, 300, -9), summary())
        assertEquals(listOf(WorkoutRoutePoint(0, 18.9998, 72.0001, 105.0), WorkoutRoutePoint(10, 18.9999, 72.00005, 96.0)), route)
    }
    @Test fun huamiAltitudeMinus20000IsUnknownAndIgnoresDeltas() {
        assertNull(parseWorkoutRoute(gps(2, 1, 1, 12), summary(alt = -20000)).single().altM)
    }
    @Test fun negativeBaseCoordinatesAndSixPlaceHalfUpRounding() {
        val p = parseWorkoutRoute(gps(0, -2, 2, 0), summary(-216_000_000, -57_000_000)).single()
        assertEquals(-72.000001, p.lon)
        assertEquals(-18.999999, p.lat)
    }
    @Test fun huamiGetActivityPointMergesEqualRoundedCoordinatesAtSameTimestamp() {
        // GPSCoordinate.equals compares the converted six-place values: raw +2 and +3 both round to +0.000001.
        val route = parseWorkoutRoute(gps(0, 2, 2, 0) + gps(0, 1, 1, 0), summary())
        assertEquals(listOf(WorkoutRoutePoint(0, 19.000001, 72.000001, 100.0)), route)
    }
    @Test fun allRecordOffsetsAdvanceTimeAndWrapAddsNewOffset() {
        // parse: ff/unknown at offset 250 advances total; GPS offsets 255 then 4 yield 255 and 259, not 260.
        val route = parseWorkoutRoute(gps(0, 0, 0, 0) + bytes(255, 250, 0, 0, 0, 0, 0, 0) + gps(255, 3, 3, 0) + gps(4, 3, 3, 0), summary())
        assertEquals(listOf(0, 255, 259), route.map { it.t })
    }
    @Test fun huamiFixupMissingTimestampsSpreadsInitialGpsBurst() {
        val route = parseWorkoutRoute(gps(0, 3, 3, 0) + gps(0, 3, 3, 0) + gps(0, 3, 3, 0) + gps(8, 3, 3, 0), summary())
        assertEquals(listOf(0, 3, 5, 8), route.map { it.t }) // round(j * 8 / 3)
    }
    @Test fun hrRecordsAnchorTheInitialGpsBurstAndHrParserRemainsUnchanged() {
        val details = gps(0, 3, 3, 0) + gps(0, 3, 3, 0) + bytes(1, 6, 70, 0, 0, 0, 0, 0) + gps(10, 3, 3, 0)
        assertEquals(listOf(0, 3, 10), parseWorkoutRoute(details, summary()).map { it.t })
        assertEquals(listOf(TrackPoint(6, 70)), parseWorkoutDetails(details))
    }
    @Test fun missingBaseMissingGpsTruncationAndOutOfRangeCoordinates() {
        assertTrue(parseWorkoutRoute(gps(0, 3, 3, 0), summary(0, 0)).isEmpty())
        assertTrue(parseWorkoutRoute(gps(0, 3, 3, 0), ByteArray(23)).isEmpty())
        assertTrue(parseWorkoutRoute(bytes(1, 1, 70, 0, 0, 0, 0, 0), summary()).isEmpty())
        assertTrue(parseWorkoutRoute(gps(0, 3, 3, 0), summary(600_000_000)).isEmpty())
        assertEquals(1, parseWorkoutRoute(gps(0, 3, 3, 0) + bytes(0, 2, 1), summary()).size)
    }
    @Test fun allSevenReadOnlyWatchDatabaseCapturesHaveNoGpsAndUnchangedHr() {
        // Extracted with sqlite3 URI ?mode=ro from data/watch.db: all seven summaries have base lon=lat=0,
        // all details contain only ff headers, HR, speed and pause. GPS fixtures above are therefore synthetic.
        val captures = resource("workout_routes_captured.json").arr()
        assertEquals(7, captures.size)
        captures.forEach { capture ->
            val c = capture.obj(); val s = hex(c["summary"].str()); val d = hex(c["details"].str())
            assertEquals(0, s.i32(12)); assertEquals(0, s.i32(16))
            assertFalse((0 until d.size step 8).any { d.u8(it) == 0 })
            assertTrue(parseWorkoutRoute(d, s).isEmpty())
            assertTrue(parseWorkoutDetails(d).isNotEmpty())
        }
        assertEquals(505, parseWorkoutDetails(hex(captures.first().obj()["details"].str())).size)
    }
}
