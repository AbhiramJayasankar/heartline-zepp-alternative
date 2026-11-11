package dev.heartline.app

import dev.heartline.data.model.ActivityRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendsTest {
    private fun row(ts: Long, kind: Int?, steps: Int = 10, hr: Int? = 70) = ActivityRow(ts, kind, 20, steps, hr, null, null, null, null)
    private val capture = FriendMark(FriendMark.Kind.CAPTURE, "cap1", 1_000, 1_179)

    @Test fun marksRoundTripThroughJson() {
        val f = Friends(listOf(capture, FriendMark(FriendMark.Kind.CHECK, "1700000000.5", 5, 9)), setOf(42L))
        assertEquals(f, Friends.parse(f.json()))
        assertEquals(Friends(), Friends.parse(null))
        assertEquals(Friends(), Friends.parse("not json"))
    }

    @Test fun withAndWithoutReplaceTheSameRecording() {
        val f = Friends().with(capture, true).with(capture.copy(to = 2_000), true)
        assertEquals(listOf(capture.copy(to = 2_000)), f.marks)
        assertTrue(f.isFriend(FriendMark.Kind.CAPTURE, "cap1"))
        assertFalse(f.with(capture, false).isFriend(FriendMark.Kind.CAPTURE, "cap1"))
        assertTrue(f.without(FriendMark.Kind.CAPTURE, "cap1").marks.isEmpty())
    }

    @Test fun coversAndDropsWorkouts() {
        val f = Friends(listOf(capture), setOf(5_000L))
        assertTrue(f.covers(1_000)); assertTrue(f.covers(1_179)); assertFalse(f.covers(1_180)); assertFalse(f.covers(999))
        assertTrue(f.overlaps(900, 1_000)); assertFalse(f.overlaps(1_180, 1_300))
        assertTrue(f.dropsWorkout(1_100)); assertTrue(f.dropsWorkout(5_000)); assertFalse(f.dropsWorkout(6_000))
    }

    @Test fun friendMinutesBecomeNotWorn() {
        // Minutes at 940 (ends 999, just before), 1000, 1060, 1120 (friend's), then 1180 and 1240 (the owner's again).
        val rows = listOf(row(940, 1), row(1_000, 1), row(1_060, 0), row(1_120, 1), row(1_180, 0), row(1_240, 10), row(1_300, 2))
        val out = friendFree(rows, Friends(listOf(capture)))
        assertEquals(1, out[0].kind); assertEquals(10, out[0].steps)
        for (i in 1..3) { assertEquals(3, out[i].kind); assertEquals(0, out[i].steps); assertNull(out[i].hr) }
        // "Same as before" codes after the span carry on the owner's last state, not "not worn".
        assertEquals(1, out[4].kind); assertEquals(1, out[5].kind)
        assertEquals(2, out[6].kind)
    }

    @Test fun noMarksLeavesRowsAlone() {
        val rows = listOf(row(0, 1), row(60, 0))
        assertTrue(friendFree(rows, Friends()) === rows)
    }
}
