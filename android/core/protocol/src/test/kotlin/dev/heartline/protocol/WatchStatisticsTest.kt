package dev.heartline.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class WatchStatisticsTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // One frame: serial "abc", then event 0x0801 (wall 1793657633, session 1793657629) with param 80 = 52, built like
    // the 2026-11-02 22:13 strength-training start in the watch's real log.
    private val frame = hex("feef1c000a036162632a1508811010a196a4d706189d96a4d7062a0408501034")

    @Test fun parsesEvents() {
        val e = parseStatisticsEvents(frame).single()
        assertEquals(0x0801, e.id)
        assertEquals(1793657633L, e.wallTs)
        assertEquals(1793657629L, e.epoch)
        assertEquals(mapOf(80 to 52L), e.params)
    }

    @Test fun framesRepeat() {
        assertEquals(2, parseStatisticsEvents(frame + frame).size)
    }

    @Test fun workoutMarks() {
        val m = watchWorkoutMarks(parseStatisticsEvents(frame)).single()
        assertEquals("watch_workout", m.type)
        assertEquals(1793657629L, m.ts)
        assertEquals(52.0, m.value)
        assertEquals("start", m.extra["event"])
        val end = WatchStatEvent(0x0901, 0, 1793661731, mapOf(32 to 52L))
        assertEquals("end", watchWorkoutMarks(listOf(end)).single().extra["event"])
    }

    @Test fun statisticsKeepsRawAndAddsMarks() {
        // Through the fetch dispatcher, as the sync calls it (it used to skip the decoding).
        val rows = parseHealth(FetchType.STATISTICS, frame, null, 1793700000)
        assertEquals(listOf("statistics", "watch_workout"), rows.map { it.type })
    }
}
