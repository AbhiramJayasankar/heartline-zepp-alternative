package dev.heartline.app

import dev.heartline.data.model.ActivityRow
import dev.heartline.data.model.HealthPoint
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

    // ---- ownHealth: health values as if the friend never wore the watch ----
    private val utc = java.time.ZoneOffset.UTC
    private val day = 86_400L
    /** A friend wore the watch from 10:00 to 11:00 on day 3 (UTC). */
    private val day3 = Friends(listOf(FriendMark(FriendMark.Kind.WORKOUT, "w", 3 * day + 36_000, 3 * day + 39_600)))

    @Test fun readingsInsideTheSpanGoOthersStay() {
        val pts = listOf(HealthPoint("stress", 3 * day + 37_000, 40.0), HealthPoint("stress", 3 * day + 40_000, 30.0), HealthPoint("spo2", 3 * day + 36_000, 97.0))
        assertEquals(listOf(30.0), ownHealth(pts, day3, utc).map { it.value })
        assertEquals(pts, ownHealth(pts, Friends(), utc))
    }

    @Test fun theWatchsRestingRateForAFriendsDayGoes() {
        val pts = listOf(HealthPoint("resting_hr", 3 * day, 52.0), HealthPoint("resting_hr", 4 * day, 54.0))
        assertEquals(listOf(4 * day), ownHealth(pts, day3, utc).map { it.ts })
    }

    /** PAI is a running count through the day; the friend's 10:00 to 11:00 added 8 on day 3. */
    @Test fun paiLosesWhatAFriendsTimeAdded() {
        fun pai(ts: Long, v: Double, total: Double) = HealthPoint("pai", ts, v, """{"total":$total}""")
        fun total(p: HealthPoint) = org.json.JSONObject(p.extra!!).getDouble("total")
        val pts = listOf(pai(3 * day + 32_400, 1.0, 30.0), pai(3 * day + 43_200, 9.0, 38.0), pai(3 * day + 82_800, 10.0, 39.0),
            pai(5 * day + 36_000, 3.0, 42.0), pai(11 * day, 1.0, 20.0))
        val out = ownHealth(pts, day3, utc)
        assertEquals(listOf(1.0, 1.0, 2.0, 3.0, 1.0), out.map { it.value })
        assertEquals(listOf(30.0, 30.0, 31.0, 34.0, 20.0), out.map { total(it) })
        // Day 3's records passed in separately still count against day 5's total.
        val later = ownHealth(listOf(pai(5 * day + 36_000, 3.0, 42.0)), day3, utc, earlierPai = pts.take(2))
        assertEquals(34.0, total(later.single()), 1e-9)
    }

    @Test fun aNightsHrvIsTheMedianOfYourOwnSamples() {
        val night = """{"night":"1970-01-04"}"""
        val friendNight = Friends(listOf(FriendMark(FriendMark.Kind.WORKOUT, "w", 3 * day + 3_000, 3 * day + 4_000)))
        val pts = listOf(HealthPoint("hrv_sleep_sample", 3 * day + 1_000, 40.0, night), HealthPoint("hrv_sleep_sample", 3 * day + 2_000, 50.0, night),
            HealthPoint("hrv_sleep_sample", 3 * day + 3_500, 90.0, night), HealthPoint("hrv_sleep", 3 * day + 3_500, 50.0, """{"night":"1970-01-04","samples":3}"""))
        val n = ownHealth(pts, friendNight, utc).single { it.type == "hrv_sleep" }
        assertEquals(45.0, n.value!!, 1e-9); assertEquals(3 * day + 2_000, n.ts)
        // Every sample a friend's: no night value at all.
        val all = Friends(listOf(FriendMark(FriendMark.Kind.WORKOUT, "w", 3 * day, 3 * day + 5_000)))
        assertTrue(ownHealth(pts, all, utc).none { it.type == "hrv_sleep" })
    }

    @Test fun twoFriendsWorkoutsInOneGapCountTheRiseOnce() {
        val two = Friends(listOf(FriendMark(FriendMark.Kind.WORKOUT, "a", 3 * day + 28_500, 3 * day + 30_100),
            FriendMark(FriendMark.Kind.WORKOUT, "b", 3 * day + 30_360, 3 * day + 31_400)))
        val pts = listOf(HealthPoint("pai", 3 * day + 25_260, 0.34, """{"total":36.67}"""), HealthPoint("pai", 3 * day + 33_780, 15.88, """{"total":52.21}"""))
        val out = ownHealth(pts, two, utc)
        assertEquals(0.34, out[1].value!!, 1e-9)
        assertEquals(36.67, org.json.JSONObject(out[1].extra!!).getDouble("total"), 1e-9)
    }
}
