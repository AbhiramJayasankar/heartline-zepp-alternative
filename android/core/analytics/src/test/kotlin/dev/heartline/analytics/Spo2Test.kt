package dev.heartline.analytics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Overnight SpO2: readings that step down with a change of position are set aside; a night that is low all through is kept. */
class Spo2Test {
    private val t0 = 1_762_300_000L - 1_762_300_000L % 60

    /** One reading a minute from minute [from] for [values], each measured over [seconds]. */
    private fun run(from: Int, values: List<Int>, seconds: Int = 60) = values.mapIndexed { i, v -> Spo2Reading(t0 + 60L * (from + i), v.toDouble(), seconds) }

    @Test fun aLowPlateauBetweenTwoMovementsIsSetAside() {
        // 97s, a move at minute 20, ten minutes at 81-83 (lying on the watch), a move at minute 30, back to 97s.
        val r = run(0, List(20) { 97 }) + run(20, listOf(82, 81, 83, 82, 82, 81, 82, 83, 82, 81)) + run(30, List(20) { 97 })
        val moved = setOf(t0 / 60 + 20, t0 / 60 + 30)
        val c = cleanSpo2(r, moved)
        assertEquals(10, c.shifted.size)
        assertTrue(c.shifted.all { it.value < 84 })
        assertEquals(40, c.kept.size)
        assertEquals(97.0, c.kept.minOf { it.value })
    }

    @Test fun aNightLowAllThroughIsKept() {
        // At altitude or with a lung condition the whole night can sit near 90: nothing to compare it with, so it stays.
        val r = run(0, List(60) { 89 + it % 3 })
        val c = cleanSpo2(r, setOf(t0 / 60 + 20, t0 / 60 + 40))
        assertEquals(60, c.kept.size)
    }

    @Test fun aSlightlyLowerStretchIsNotSetAside() {
        // 95 after a move with a reference of 98 is ordinary: only stretches under 92 can be set aside.
        val r = run(0, List(20) { 98 }) + run(20, List(10) { 95 }) + run(30, List(20) { 98 })
        val c = cleanSpo2(r, setOf(t0 / 60 + 20, t0 / 60 + 30))
        assertEquals(50, c.kept.size)
    }

    @Test fun aOneOffSpikeIsSetAside() {
        val r = run(0, List(10) { 97 } + listOf(84) + List(10) { 97 })
        val c = cleanSpo2(r, emptySet())
        assertEquals(listOf(84.0), c.spikes.map { it.value })
        assertEquals(20, c.kept.size)
    }

    @Test fun movementIsIntensityOrAChangeOfMinuteType() {
        val m = listOf(MinuteSample(t0, 240, 0, 0, 60, 0, 0, 0, "sleep_light"), MinuteSample(t0 + 60, 240, 0, 0, 60, 0, 0, 0, "sleep_light"),
            MinuteSample(t0 + 120, 249, 0, 0, 60, 0, 0, 0, "sleep_light"), MinuteSample(t0 + 180, 249, 12, 0, 60, 0, 0, 0, "sleep_light"))
        assertEquals(setOf(t0 / 60 + 2, t0 / 60 + 3), movedMinutes(m))
    }
}
