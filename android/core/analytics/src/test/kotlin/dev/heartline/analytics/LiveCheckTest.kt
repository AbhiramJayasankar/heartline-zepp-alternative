package dev.heartline.analytics

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class LiveCheckTest {
    @Test fun stillCheckFindsTheBeatsAndMatchesTheFinishedAnalysis() {
        val s = SyntheticPpg(seconds = 60.0)
        val live = liveCheck(s.samples().recording())!!
        val done = analyzeSession(s.recording(), "x")
        // Same pipeline: the live figures are the finished check's.
        assertEquals(done.hrv.rmssdMs!!, live.rmssdMs!!, 1e-9)
        assertEquals(done.beats, live.beats)
        assertEquals(done.usableS, live.cleanS, .05)
        // And close to the truth the recording was made from.
        assertEquals(s.rmssdMs, live.rmssdMs!!, s.rmssdMs * .15)
        assertEquals(s.bpm, live.hrBpm!!, 3.0)
        assertTrue("found ${live.beats} of ${s.beats.size}", abs(live.beats - s.beats.size) <= 2)
        assertTrue(live.cleanBeats >= live.beats - 3)
        assertTrue(live.motionNowG!! < LiveCheck.MOVING_G)
        assertEquals(25.0, live.waveFs, 0.0)
        assertEquals(live.durationS * 25, live.wave.size.toDouble(), 2.0)
        // Beats point up: the wave is high at the found beat times.
        val atBeats = live.beatT.map { live.wave[(it * 25).roundToInt().coerceIn(0, live.wave.lastIndex)] }.average()
        assertTrue(atBeats > 0)
    }

    @Test fun earlySecondsShowAWaveButNoFiguresYet() {
        val s = SyntheticPpg(seconds = 60.0)
        val live = liveCheck(s.samples(upTo = 6.0).recording())!!
        assertTrue(live.wave.size > 100)
        assertEquals(0, live.beats)
        assertNull(live.rmssdMs); assertNull(live.hrBpm)
        assertNull(liveCheck(s.samples(upTo = .5).recording()))
    }

    @Test fun movingStretchShowsInTheMotionLevelAndItsBeatsAreSetAside() {
        val s = SyntheticPpg(seconds = 60.0, moving = listOf(20.0..35.0))
        val mid = liveCheck(s.samples(upTo = 30.0).recording())!!
        assertTrue("motion ${mid.motionNowG}", mid.motionNowG!! > LiveCheck.MOVING_G)
        val live = liveCheck(s.samples().recording())!!
        val during = live.beatT.indices.filter { live.beatT[it] in 21.0..34.0 }
        assertTrue(during.isNotEmpty() && during.none { live.beatGood[it] })
        assertTrue(live.motionNowG!! < LiveCheck.MOVING_G)
    }
}
