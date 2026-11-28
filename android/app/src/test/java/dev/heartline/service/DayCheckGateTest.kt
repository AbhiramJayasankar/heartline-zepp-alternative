package dev.heartline.service

import dev.heartline.analytics.LiveCheck
import dev.heartline.service.DayCheckGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class DayCheckGateTest {
    /** A check [t] seconds in with [cleanS] of clean beats, the wrist at [g] (g) on every 0.5 s of the motion grid. */
    private fun live(t: Double, cleanS: Double, g: (Double) -> Double) = LiveCheck(t, 25.0, FloatArray(0), DoubleArray(0), BooleanArray(0),
        DoubleArray((t * 2).toInt() + 1) { it * 0.5 }, FloatArray((t * 2).toInt() + 1) { g(it * 0.5).toFloat() },
        null, 0, 0, cleanS, null, null)

    private val still = { _: Double -> 0.005 }
    private val typing = { _: Double -> 0.05 }

    @Test fun nothingLiveRunsTheFullLength() = assertEquals(Verdict.CONTINUE, DayCheckGate.decide(null, 120, 45))

    @Test fun theScoutWaitsTwentySeconds() = assertEquals(Verdict.CONTINUE, DayCheckGate.decide(live(15.0, 0.0, typing), 120, 45))

    @Test fun aMovingWristEndsAfterTheScout() = assertEquals(Verdict.MOVING, DayCheckGate.decide(live(20.0, 2.0, typing), 120, 45))

    @Test fun sixStillSecondsOfTwentyAreEnough() {
        val someStill = { t: Double -> if (t in 10.0..16.0) 0.005 else 0.05 }
        assertEquals(Verdict.CONTINUE, DayCheckGate.decide(live(21.0, 5.0, someStill), 120, 45))
        val tooLittle = { t: Double -> if (t in 10.0..14.0) 0.005 else 0.05 }
        assertEquals(Verdict.MOVING, DayCheckGate.decide(live(21.0, 5.0, tooLittle), 120, 45))
    }

    @Test fun fiftyCleanSecondsEndTheCheck() = assertEquals(Verdict.ENOUGH, DayCheckGate.decide(live(70.0, 50.0, still), 120, 45))

    @Test fun anUnreachableNeedEndsTheCheck() {
        // 90 s in with 10 s clean: 30 s left can't make 45.
        assertEquals(Verdict.CANT_REACH, DayCheckGate.decide(live(90.0, 10.0, still), 120, 45))
        // 60 s in with 10 s clean: 60 s left still can.
        assertEquals(Verdict.CONTINUE, DayCheckGate.decide(live(60.0, 10.0, still), 120, 45))
    }

    @Test fun stillSecondsCountOnlyTheScout() {
        val lateStill = { t: Double -> if (t > 25) 0.005 else 0.05 }
        assertEquals(0.0, DayCheckGate.stillS(live(40.0, 0.0, lateStill), 20.0), 1e-9)
        assertEquals(20.5, DayCheckGate.stillS(live(40.0, 0.0, still), 20.0), 1e-9)
    }
}
