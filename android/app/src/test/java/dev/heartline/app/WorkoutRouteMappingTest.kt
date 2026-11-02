package dev.heartline.app

import dev.heartline.data.model.RawFetch
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WorkoutRouteMappingTest {
    private fun summary(start: Int): ByteArray = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        .putShort(515).putShort(1).putInt(start).putInt(start + 60).putInt(216_000_000).putInt(57_000_000).putInt(10).array()
    private fun raw(type: String, start: Double, data: ByteArray, ts: Double = 1.0) = RawFetch(ts, type, start, null, data)
    private val details = byteArrayOf(0, 1, 3, 0, 6, 0, 2, 0)

    @Test fun joinsDetailsToCorrectSummaryAndMapsRelativeSeconds() {
        val routes = storedWorkoutRoutes(listOf(raw("workouts", 100.0, summary(100)), raw("workouts", 200.0, summary(200)), raw("workout_details", 100.0, details)))
        assertEquals(listOf(RoutePoint(1, 19.000002, 72.000001, 12.0)), routes[100L])
        assertNull(routes[200L])
    }
    @Test fun missingSummaryMissingDetailsAndNoGpsProduceEmptyRoutes() {
        assertTrue(storedWorkoutRoutes(listOf(raw("workouts", 100.0, summary(100)))).isEmpty())
        assertEquals(emptyList<RoutePoint>(), storedWorkoutRoutes(listOf(raw("workout_details", 100.0, details)))[100L])
        val noGps = byteArrayOf(1, 1, 70, 0, 0, 0, 0, 0)
        assertEquals(emptyList<RoutePoint>(), storedWorkoutRoutes(listOf(raw("workouts", 100.0, summary(100)), raw("workout_details", 100.0, noGps)))[100L])
    }
    @Test fun latestFetchWinsRegardlessOfInputOrderAndOtherTypesAreIgnored() {
        val newer = details.copyOf().apply { this[6] = 5 }
        val routes = storedWorkoutRoutes(listOf(raw("workout_details", 100.0, newer, 3.0), raw("workouts", 100.0, summary(100)),
            raw("stress", 100.0, details), raw("workout_details", 100.0, details, 2.0)))
        assertEquals(15.0, routes[100L]!!.single().altM!!, 0.0)
    }
}
