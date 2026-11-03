package dev.heartline.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class SpeechEndpointerTest {
    /** Feeds (level, frames) runs; returns the frame where it stopped, or -1. */
    private fun run(e: SpeechEndpointer, vararg runs: Pair<Float, Int>): Int {
        for ((level, n) in runs) repeat(n) { if (e.feed(level)) return e.frames }
        return -1
    }

    @Test fun stopsAfterSpeechThenSilence() {
        val e = SpeechEndpointer()
        // tone, room, a phrase, a 1 s pause (kept), another phrase, then quiet
        val at = run(e, -10f to 20, -63f to 30, -40f to 60, -62f to 50, -38f to 40, -63f to 200)
        assertEquals(SpeechEndpointer.State.DONE, e.state)
        assertEquals(20 + 30 + 60 + 50 + 40 + 75, at)
    }

    @Test fun givesUpWhenNothingIsSaid() {
        val e = SpeechEndpointer()
        run(e, -10f to 20, -63f to 400)
        assertEquals(SpeechEndpointer.State.NOTHING_SAID, e.state)
        assertEquals(350, e.frames)
    }

    @Test fun capsLongTalk() {
        val e = SpeechEndpointer()
        run(e, -10f to 20, -63f to 10, -40f to 900)
        assertEquals(SpeechEndpointer.State.TOO_LONG, e.state)
    }

    @Test fun ignoresTheStartTone() {
        val e = SpeechEndpointer()
        run(e, -5f to 20, -63f to 100)
        assertEquals(0, e.speechFrames)
    }
}
