package dev.heartline.analytics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Still stretches between movements, numbered by which way the watch faced; going back to a facing reuses its number. */
class SleepPositionsTest {
    private val t0 = 1_762_300_000L - 1_762_300_000L % 60
    private fun at(min: Int) = t0 + 60L * min

    /** A 3-hour night, still except movements at the given minutes (intensity 30) and twitches (intensity 5). */
    private fun night(moves: Set<Int>, twitches: Set<Int> = emptySet()) = (0 until 180).map { i ->
        MinuteSample(at(i), 9, if (i in moves) 30 else if (i in twitches) 5 else 0, 0, 55, 0, 0, 0, "sleep_light")
    }
    private val faceDown = doubleArrayOf(0.05, 0.0, -1.0)
    private val onSide = doubleArrayOf(-0.95, 0.3, 0.0)
    private fun pose(min: Int, v: DoubleArray) = WristPose(at(min), v[0], v[1], v[2])

    @Test fun stretchesSplitAtMovementsNotTwitches() {
        val m = sleepMovement(night(setOf(60, 61, 120), twitches = setOf(30, 90)), at(0), at(180), emptyList())
        assertEquals(2, m.moves.size) // 60-61 is one move
        assertEquals(listOf(60, 58, 59), m.stretches.map { it.minutes })
        assertEquals(60, m.longestStillMin)
        assertEquals(3, m.restlessMin)
        assertNull(m.stretches.first().position)
    }

    @Test fun goingBackToAFacingReusesItsNumber() {
        // Face down, then on the side, then face down again: positions 1, 2, 1.
        val poses = listOf(pose(20, faceDown), pose(80, onSide), pose(150, doubleArrayOf(0.1, 0.1, -0.99)))
        val m = sleepMovement(night(setOf(60, 120)), at(0), at(180), poses)
        assertEquals(listOf(1, 2, 1), m.stretches.map { it.position })
        assertEquals(2, m.positions.size)
        assertEquals("Watch face down", m.positions[0].words)
        assertEquals("On its side", m.positions[1].words)
        assertEquals(2, m.turns)
        assertEquals(119, m.positions[0].minutes) // 60 + 59
    }

    @Test fun aStretchWithoutARecordingIsUnmeasured() {
        val m = sleepMovement(night(setOf(60, 120)), at(0), at(180), listOf(pose(20, faceDown)))
        assertEquals(listOf(1, null, null), m.stretches.map { it.position })
        assertEquals(0, m.turns)
    }

    @Test fun gravityNeedsToBeNearOneG() {
        val still = Array(100) { floatArrayOf(0f, 0f, -1f) }
        assertNotNull(gravityOf(still, 0)).also { assertEquals(-1.0, it.z, 1e-9) }
        assertNull(gravityOf(Array(100) { floatArrayOf(0f, 0f, -0.3f) }, 0)) // moving: the mean isn't gravity
    }
}
