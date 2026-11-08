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

    @Test fun roomBlipsAfterSpeechDontKeepItListening() {
        val e = SpeechEndpointer()
        // A question at -30, then a room with 40 ms sounds at -45 (18 dB over its floor) every 0.6 s.
        val blips = Array(20) { listOf(-63f to 28, -45f to 2) }.flatMap { it }.toTypedArray()
        val at = run(e, -10f to 20, -63f to 30, -30f to 60, *blips)
        assertEquals(SpeechEndpointer.State.DONE, e.state)
        assertEquals(20 + 30 + 60 + 75, at)
    }

    @Test fun keepsListeningThroughAQuieterLastPhrase() {
        val e = SpeechEndpointer()
        // A loud start, a 1 s pause, and a last phrase 12 dB quieter: still speech, so it waits for the quiet after it.
        val at = run(e, -10f to 20, -63f to 30, -30f to 60, -63f to 50, -42f to 40, -63f to 200)
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
        // 32 s of talk: 200 ms syllables with 200 ms gaps, like speech, so it never goes quiet for 1.5 s.
        val talk = Array(80) { listOf(-35f to 10, -60f to 10) }.flatMap { it }.toTypedArray()
        run(e, -10f to 20, -63f to 10, *talk)
        assertEquals(SpeechEndpointer.State.TOO_LONG, e.state)
        assertEquals(1450, e.frames)
    }

    @Test fun ignoresTheStartTone() {
        val e = SpeechEndpointer()
        run(e, -5f to 20, -63f to 100)
        assertEquals(0, e.speechFrames)
    }
}
