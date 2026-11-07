package dev.heartline.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Vo2maxTest {
    private val now = 1_800_000_000L

    @Test fun outdoorRunGivesAnEstimate() {
        // 5 km in 25 min = 200 m/min: VO2 = 0.2 * 200 + 3.5 = 43.5. At 75% of reserve: 3.5 + 40 / 0.75 = 56.8.
        val run = WorkoutEffort(now - 86_400, "Outdoor running", 1500, 5000, (60 + 0.75 * 130).toInt())
        val (est, why) = vo2maxFromWorkout(run, 60.0, 190)
        assertNull(why)
        assertEquals(56.9, est!!.vo2max, 0.3)
    }

    @Test fun gymAndTreadmillAndEasyWalksAreSkipped() {
        val gym = WorkoutEffort(now, "Strength training", 4000, 0, 112)
        val tread = WorkoutEffort(now, "Treadmill", 612, 683, 166)
        val stroll = WorkoutEffort(now, "Walking", 1800, 2000, 85)
        val r = vo2max(listOf(gym, tread, stroll), now, 70.0, 193, "male")
        assertFalse(r.fromWorkouts)
        assertEquals(3, r.skipped.size)
        assertTrue(r.skipped.first { it.first === tread }.second.contains("treadmill"))
        assertTrue(r.skipped.first { it.first === stroll }.second.contains("too easy"))
        // Stand-in: 15.3 x 193 / 70.
        assertEquals(42.2, r.value!!, 0.05)
    }

    @Test fun usesMedianOfNewestRuns() {
        val runs = (1..7).map { WorkoutEffort(now - it * 86_400L, "Outdoor running", 1800, 6000 + it * 100, 150) }
        val r = vo2max(runs, now, 60.0, 190, "male")
        assertTrue(r.fromWorkouts)
        assertEquals(5, r.used.size)
        assertEquals(runs[0].start, r.used[0].start)
    }

    @Test fun inclineWalkUsesTheGradeEquation() {
        // 3.6 km/h = 60 m/min at 20%: VO2 = 6 + 21.6 + 3.5 = 31.1. Held 172 bpm, rest 71, max 193: 82.8% of reserve.
        val (est, why) = vo2maxFromTreadmill(TreadmillEffort(now, 600.0, 3.6, 20.0, 172), 71.0, 193)
        assertNull(why)
        assertEquals(36.8, est!!.vo2max, 0.1)
        // The logged treadmill replaces the watch's treadmill workout (no "no GPS" skip for it).
        val watch = WorkoutEffort(now - 60, "Treadmill", 612, 683, 166)
        val r = vo2max(listOf(watch), now + 10, 71.0, 193, "male", listOf(TreadmillEffort(now, 600.0, 3.6, 20.0, 172)))
        assertTrue(r.fromWorkouts); assertTrue(r.skipped.isEmpty()); assertEquals(36.8, r.value!!, 0.1)
    }

    @Test fun activeMinutesCountWalksAndWorkingSetsButNotSitting() {
        fun m(i: Int, steps: Int, hr: Int?, state: String = "active") = MinuteSample(now + i * 60L, 1, 50, steps, hr, 0, 0, 0, state)
        val ms = listOf(
            m(0, 0, 80), m(1, 0, 92),                // sitting with the workout already running: out (under 20% of reserve)
            m(2, 0, 97), m(3, 3, 112),               // between sets, heart rate up: in (inside the workout, 20% or more)
            m(4, 0, 100, "idle"),                    // after the workout ends, still 100: out (under 30% of reserve)
            m(5, 70, 85), m(6, 25, 84), m(7, 15, 80), // a short walk: 70 in, 25 next to it in, 15 out
            m(8, 0, 110),                            // 110 bpm anywhere: in (30% of reserve or more)
            m(9, 200, null, "sleep_light"),          // asleep: never
        )
        // Awake rest 69, max 193: reserve 124, so 20% = 93.8 and 30% = 106.2.
        assertEquals(5, activeMinutesOf(ms, 193, 69.0, listOf(now to now + 4 * 60L)))
    }
}
