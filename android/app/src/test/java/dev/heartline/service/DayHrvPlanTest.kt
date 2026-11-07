package dev.heartline.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DayHrvPlanTest {
    @Test fun cleanReadingWaitsTheInterval() {
        val p = DayHrvPlan().afterFail(1000, walked = true).afterClean(2000, 1800)
        assertEquals(3800L, p.nextAt)
        assertEquals(0, p.tries); assertFalse(p.backingOff)
    }

    @Test fun walkingFailuresBackOffExponentiallyToSixteenMinutes() {
        var p = DayHrvPlan()
        val waits = (1..6).map { p = p.afterFail(0, walked = true); p.nextAt }
        assertEquals(listOf(120L, 240L, 480L, 960L, 960L, 960L), waits)
        assertTrue(p.backingOff)
    }

    @Test fun failuresWithoutStepsAlwaysRetryAtOnce() {
        var p = DayHrvPlan()
        for (t in listOf(100L, 250L, 400L, 550L, 700L)) {
            p = p.afterFail(t, walked = false)
            assertEquals(t, p.nextAt)
            assertFalse(p.backingOff)
        }
        assertEquals(5, p.tries)
    }

    @Test fun stayingPutAfterWalkingEndsTheBackOff() {
        val p = DayHrvPlan().afterFail(0, walked = true).afterFail(0, walked = true).afterFail(500, walked = false)
        assertEquals(500L, p.nextAt)
        assertFalse(p.backingOff)
        assertEquals(3, p.tries)
        // Walking again starts the back-off from 2 minutes.
        assertEquals(620L, p.afterFail(500, walked = true).nextAt)
    }
}
