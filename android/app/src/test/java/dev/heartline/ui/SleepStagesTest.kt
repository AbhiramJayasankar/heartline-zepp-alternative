package dev.heartline.ui

import dev.heartline.app.MinuteState
import dev.heartline.app.MinuteUi
import dev.heartline.ui.components.smoothStretches
import dev.heartline.ui.components.stageStretches
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepStagesTest {
    private val t0 = 1_795_000_000L

    /** A night from runs of (state, minutes). */
    private fun night(vararg runs: Pair<MinuteState, Int>): List<MinuteUi> {
        var t = t0
        return runs.flatMap { (s, n) -> (0 until n).map { MinuteUi(t, s, 0, null).also { t += 60 } } }
    }

    @Test
    fun aMinuteOfLightInsideRemIsFoldedInButAWakingStays() {
        val m = night(MinuteState.SLEEP_LIGHT to 20, MinuteState.SLEEP_REM to 6, MinuteState.SLEEP_LIGHT to 1, MinuteState.SLEEP_REM to 8,
            MinuteState.SLEEP_LIGHT to 15, MinuteState.IDLE to 1, MinuteState.SLEEP_LIGHT to 10)
        val raw = stageStretches(m, t0, t0 + 61 * 60)
        assertEquals(7, raw.size)
        val s = smoothStretches(raw)
        // Light, REM (15 min with the folded minute), light, the one-minute waking, light.
        assertEquals(listOf(2, 1, 2, 0, 2), s.map { it.lane })
        assertEquals(15, s[1].minutes)
        assertEquals(1, s[3].minutes)
    }

    @Test
    fun aGapInTheRecordingBreaksAStretch() {
        val a = night(MinuteState.SLEEP_DEEP to 10)
        val b = night(MinuteState.SLEEP_DEEP to 10).map { it.copy(ts = it.ts + 30 * 60) }
        assertEquals(2, stageStretches(a + b, t0, t0 + 3600).size)
    }
}
