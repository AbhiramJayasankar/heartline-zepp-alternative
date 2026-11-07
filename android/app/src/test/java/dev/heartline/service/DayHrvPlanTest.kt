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

    @Test fun walkingFailuresBackOffExponentiallyToSixteenMinutes() {
        var p = DayHrvPlan()
        val waits = (1..6).map { p = p.afterFail(0, walked = true, every); p.nextAt }
        assertEquals(listOf(120L, 240L, 480L, 960L, 960L, 960L), waits)
        assertTrue(p.backingOff)
    }

    @Test fun stillFailuresRetryAtOnceThenWaitTheIntervalAfterSeven() {
        var p = DayHrvPlan()
        for (i in 1..6) {
            p = p.afterFail(i * 150L, walked = false, every)
            assertEquals(i * 150L, p.nextAt)
            assertFalse(p.backingOff); assertFalse(p.gaveUp)
        }
        p = p.afterFail(1050, walked = false, every)
        assertTrue(p.gaveUp)
        assertEquals(1050 + every, p.nextAt)
        // The next round starts afresh: seven more tries.
        p = p.afterFail(3000, walked = false, every)
        assertEquals(3000L, p.nextAt); assertEquals(1, p.stillFails)
    }

    @Test fun walkingTriesDontCountTowardsTheSeven() {
        var p = DayHrvPlan()
        repeat(6) { p = p.afterFail(0, walked = false, every) }
        p = p.afterFail(0, walked = true, every)
        assertEquals(6, p.stillFails)
        assertTrue(p.backingOff)
        p = p.afterFail(500, walked = false, every)
        assertTrue(p.gaveUp)
    }

    @Test fun stayingPutAfterWalkingEndsTheBackOff() {
        val p = DayHrvPlan().afterFail(0, walked = true, every).afterFail(0, walked = true, every).afterFail(500, walked = false, every)
        assertEquals(500L, p.nextAt)
        assertFalse(p.backingOff)
        assertEquals(620L, p.afterFail(500, walked = true, every).nextAt)
    }
}
