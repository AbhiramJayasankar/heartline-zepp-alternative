package dev.heartline.analytics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GaitTest {
    /** Walk 60 s, run 120 s, walk 90 s, run 120 s, walk 60 s: the shape of an easy run-walk session. */
    private fun session(): Triple<DoubleArray, IntArray, DoubleArray> {
        val plan = listOf(60 to 100.0, 120 to 145.0, 90 to 98.0, 120 to 148.0, 60 to 96.0)
        val cad = ArrayList<Double>(); val hr = ArrayList<Int>(); val speed = ArrayList<Double>()
        var h = 105.0
        for ((len, c) in plan) repeat(len) { k ->
            cad += c + (if (k % 2 == 0) 2 else -2)
            val target = if (c > 125) 165.0 else 120.0
            h += (target - h) / (if (c > 125) 30.0 else 45.0)
            hr += h.toInt(); speed += if (c > 125) 2.8 else 1.4
        }
        return Triple(cad.toDoubleArray(), hr.toIntArray(), speed.toDoubleArray())
    }

    @Test fun runsAndWalksAreFoundWithTheirTimesAndDistances() {
        val (cad, hr, speed) = session()
        val s = assertNotNull(gaitSummary(gaitFromCadence(cad), hr, cad, speed))
        assertEquals(listOf(false, true, false, true, false), s.bouts.map { it.running })
        assertEquals(240.0, s.runS.toDouble(), 6.0)
        assertEquals(210.0, s.walkS.toDouble(), 6.0)
        assertEquals(240 * 2.8, s.runDistanceM!!, 20.0)
        assertTrue(s.intervals)
        assertEquals(2, s.recoveries)
        // Heart rate falls over the first minute of each walk after a run.
        assertTrue(s.recovery60!! in 10..40, "recovery ${s.recovery60}")
        assertTrue(s.runHr!! > s.walkHr!!)
        assertEquals(146.0, s.runCadence!!.toDouble(), 2.0)
        assertEquals(357.0, s.runPaceSPerKm!!, 5.0)
    }

    @Test fun aDropoutOrAStumbleDoesNotSplitARun() {
        val cad = DoubleArray(200) { if (it in 90..95) Double.NaN else if (it in 140..146) 100.0 else 150.0 }
        val s = assertNotNull(gaitSummary(gaitFromCadence(cad), IntArray(200) { 160 }, cad))
        assertEquals(1, s.bouts.size)
        assertTrue(s.bouts.single().running)
    }

    @Test fun aFewFastStridesInAWalkAreNotARun() {
        val cad = DoubleArray(300) { if (it in 100..110) 140.0 else 100.0 }
        val s = assertNotNull(gaitSummary(gaitFromCadence(cad), IntArray(300), cad))
        assertEquals(1, s.bouts.size)
        assertTrue(!s.bouts.single().running)
        assertNull(s.recovery60)
        assertTrue(!s.intervals)
    }

    @Test fun aBriskWalkOnTheBoundaryStaysAWalkAndAJogStaysARun() {
        // Hysteresis: 120 spm never starts a run; once running, 118 spm keeps it one.
        assertTrue(gaitFromCadence(DoubleArray(120) { 120.0 }).all { it == Gait.WALK })
        val k = gaitFromCadence(DoubleArray(120) { if (it < 40) 135.0 else 118.0 })
        assertTrue(k.all { it == Gait.RUN })
    }

    @Test fun standingStillBreaksTheSessionIntoSeparateBouts() {
        val cad = DoubleArray(400) { if (it in 150..249) 0.0 else 145.0 }
        val s = assertNotNull(gaitSummary(gaitFromCadence(cad), IntArray(400) { 150 }, cad))
        assertEquals(2, s.runs.size)
        assertEquals(100.0, s.stillS.toDouble(), 6.0)
    }

    @Test fun driftComparesTheLastThirdOfRunningWithTheFirst() {
        val n = 1200
        val cad = DoubleArray(n) { 150.0 }
        val hr = IntArray(n) { 150 + it * 15 / n }
        val s = assertNotNull(gaitSummary(gaitFromCadence(cad), hr, cad))
        assertEquals(10.0, s.driftBpm!!.toDouble(), 1.5)
        assertEquals(0, s.driftCadence)
    }

    @Test fun samplesAreHeldUntilTheNextForAFewSecondsOnly() {
        val v = perSecond(listOf(0 to 1.0, 3 to 2.0, 20 to 3.0), 26)
        assertEquals(listOf(1.0, 1.0, 1.0, 2.0, 2.0, 2.0, 2.0, 2.0), v.take(8).toList())
        assertTrue(v[8].isNaN() && v[19].isNaN())
        assertEquals(3.0, v[24]); assertTrue(v[25].isNaN())
    }
}
