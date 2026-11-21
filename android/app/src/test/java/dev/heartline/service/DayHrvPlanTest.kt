package dev.heartline.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DayHrvPlanTest {
    private val every = 1800L

    @Test fun cleanReadingWaitsTheInterval() {
        val p = DayHrvPlan().afterFail(1000, walked = true, every).afterClean(2000, every)
        assertEquals(3800L, p.nextAt)
        assertEquals(0, p.tries); assertFalse(p.backingOff)
    }

    @Test fun walkingFailuresBackOffThenTheThirdFailureWaitsAnHour() {
        var p = DayHrvPlan()
        p = p.afterFail(0, walked = true, every); assertEquals(120L, p.nextAt); assertTrue(p.backingOff)
        p = p.afterFail(0, walked = true, every); assertEquals(240L, p.nextAt)
        p = p.afterFail(1000, walked = true, every)
        assertTrue(p.gaveUp); assertEquals(1000 + 3600L, p.nextAt)
    }

    @Test fun stillFailuresRetryAtOnceThenWaitAnHourAfterThree() {
        var p = DayHrvPlan()
        for (i in 1..2) {
            p = p.afterFail(i * 150L, walked = false, every)
            assertEquals(i * 150L, p.nextAt)
            assertFalse(p.backingOff); assertFalse(p.gaveUp)
        }
        p = p.afterFail(450, walked = false, every)
        assertTrue(p.gaveUp)
        assertEquals(450 + 3600L, p.nextAt)
        // The next round starts afresh: three more tries.
        p = p.afterFail(5000, walked = false, every)
        assertEquals(5000L, p.nextAt); assertEquals(1, p.tries)
    }

    @Test fun walkingAndStillTriesCountTogether() {
        var p = DayHrvPlan().afterFail(0, walked = false, every).afterFail(0, walked = true, every)
        assertTrue(p.backingOff)
        p = p.afterFail(500, walked = false, every)
        assertTrue(p.gaveUp); assertEquals(4100L, p.nextAt)
    }

    @Test fun aStoppedCheckWaitsAnHour() {
        val p = DayHrvPlan().afterFail(0, walked = false, every).afterStop(900)
        assertEquals(4500L, p.nextAt); assertTrue(p.gaveUp); assertEquals(0, p.tries)
    }

    @Test fun stayingPutAfterWalkingEndsTheBackOff() {
        val p = DayHrvPlan().afterFail(0, walked = true, every).afterFail(500, walked = false, every)
        assertEquals(500L, p.nextAt)
        assertFalse(p.backingOff)
    }
}
