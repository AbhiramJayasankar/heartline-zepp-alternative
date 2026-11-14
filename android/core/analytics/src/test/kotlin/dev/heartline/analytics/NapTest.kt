package dev.heartline.analytics

import java.time.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Naps are kept out of the nights: an evening nap is not the next night, nor added to its hours. */
class NapTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val day = LocalDate.of(2026, 11, 7)

    /** Minutes from [from] to [to] (exclusive) in [state]. */
    private fun span(from: ZonedDateTime, to: ZonedDateTime, state: String, hr: Int): List<MinuteSample> {
        val out = ArrayList<MinuteSample>()
        var t = from
        while (t < to) {
            out += MinuteSample(t.toEpochSecond(), if (state.startsWith("sleep")) 9 else 1, 0, 0, hr, 0, 0, 0, state)
            t = t.plusMinutes(1)
        }
        return out
    }
    private fun at(d: LocalDate, h: Int, m: Int) = d.atTime(h, m).atZone(zone)

    /** Last night 02:40-10:21, awake, a nap 19:20-20:10, awake, then tonight 02:00-09:00. */
    private fun week(withTonight: Boolean): List<MinuteSample> =
        span(at(day, 2, 40), at(day, 10, 21), "sleep_light", 57) +
            span(at(day, 10, 21), at(day, 19, 20), "idle", 80) +
            span(at(day, 19, 20), at(day, 20, 10), "sleep_light", 60) +
            span(at(day, 20, 10), at(day.plusDays(1), 2, 0), "idle", 85) +
            (if (withTonight) span(at(day.plusDays(1), 2, 0), at(day.plusDays(1), 9, 0), "sleep_light", 56) +
                span(at(day.plusDays(1), 9, 0), at(day.plusDays(1), 11, 0), "idle", 75) else emptyList())

    @Test fun eveningNapIsANapNotTonight() {
        val r = computeDaily(week(false), emptyList(), emptyList(), Profile(), 2, at(day, 23, 0)).days
        val today = r.last { it.date == day.toString() }
        assertEquals(461, today.sleepMinutes)
        assertEquals(1, today.naps.size)
        assertEquals(50, today.naps[0].asleepMin)
        assertEquals(60, today.naps[0].lowHr)
    }

    @Test fun napStaysOutOfTheNextNight() {
        val r = computeDaily(week(true), emptyList(), emptyList(), Profile(), 2, at(day.plusDays(1), 11, 0)).days
        val next = r.last { it.date == day.plusDays(1).toString() }
        assertEquals(420, next.sleepMinutes) // not 470: the 19:20 nap is not part of it
        assertTrue(r.first { it.date == day.toString() }.naps.isNotEmpty())
    }

    @Test fun aLongWakeInTheNightStaysOneNight() {
        // 01:00-03:00 asleep, 2 h 30 awake... no: 1 h awake, then 04:00-08:00: one night of 6 h.
        val m = span(at(day, 0, 0), at(day, 1, 0), "idle", 80) + span(at(day, 1, 0), at(day, 3, 0), "sleep_light", 58) +
            span(at(day, 3, 0), at(day, 4, 0), "idle", 75) + span(at(day, 4, 0), at(day, 8, 0), "sleep_light", 56) +
            span(at(day, 8, 0), at(day, 9, 0), "idle", 75)
        val d = computeDaily(m, emptyList(), emptyList(), Profile(), 2, at(day, 9, 0)).days.last()
        assertEquals(360, d.sleepMinutes)
        assertTrue(d.naps.isEmpty())
        // One stretch from first sleep to last: stages add up to the total, the window covers both, the hour awake is awake.
        assertEquals(360, (d.deepMin ?: 0) + (d.lightMin ?: 0) + (d.remMin ?: 0))
        assertEquals(at(day, 1, 0).toEpochSecond(), d.sleepStart)
        assertEquals(60, d.awakeMin)
    }

    @Test fun shortStillMomentsBeforeBedAreNotSleep() {
        // 8 Nov: 14 and 12 minutes the watch marked as sleep at 00:54 and 02:02 while sitting up, then 04:09-10:13.
        val d8 = day.plusDays(1)
        val m = span(at(d8, 0, 0), at(d8, 0, 54), "idle", 85) + span(at(d8, 0, 54), at(d8, 1, 8), "sleep_light", 70) +
            span(at(d8, 1, 8), at(d8, 2, 2), "idle", 85) + span(at(d8, 2, 2), at(d8, 2, 14), "sleep_light", 70) +
            span(at(d8, 2, 14), at(d8, 4, 9), "idle", 85) + span(at(d8, 4, 9), at(d8, 10, 14), "sleep_light", 57) +
            span(at(d8, 10, 14), at(d8, 12, 0), "idle", 80)
        val d = computeDaily(m, emptyList(), emptyList(), Profile(), 2, at(d8, 12, 0)).days.last()
        assertEquals(365, d.sleepMinutes) // not 391
        assertEquals(365, (d.deepMin ?: 0) + (d.lightMin ?: 0) + (d.remMin ?: 0))
        assertEquals(at(d8, 4, 9).toEpochSecond(), d.sleepStart)
    }

    // ---- A long evening sleep before a short, broken night (13-14 Nov 2026) ----
    private val fri = LocalDate.of(2026, 11, 13)
    private val sat = fri.plusDays(1)
    /** Fri 05:23-10:47 asleep; a 3 h 34 evening sleep 17:39-21:13; Sat 03:30-05:43 asleep, watch off 05:53-06:16, asleep 06:18-06:34. */
    private fun shortNightAfterLongNap(): List<MinuteSample> =
        span(at(fri, 5, 23), at(fri, 10, 47), "sleep_light", 55) + span(at(fri, 10, 47), at(fri, 17, 39), "idle", 80) +
            span(at(fri, 17, 39), at(fri, 21, 13), "sleep_light", 58) + span(at(fri, 21, 13), at(sat, 3, 30), "idle", 82) +
            span(at(sat, 3, 30), at(sat, 5, 43), "sleep_light", 56) + span(at(sat, 5, 43), at(sat, 5, 53), "idle", 70) +
            span(at(sat, 5, 53), at(sat, 6, 16), "not_worn", 0) + span(at(sat, 6, 16), at(sat, 6, 18), "idle", 70) +
            span(at(sat, 6, 18), at(sat, 6, 34), "sleep_light", 60) + span(at(sat, 6, 34), at(sat, 12, 0), "idle", 85)

    @Test fun aShortBrokenNightBeatsALongerEveningSleep() {
        val r = computeDaily(shortNightAfterLongNap(), emptyList(), emptyList(), Profile(), 3, at(sat, 12, 0)).days
        val night = r.last { it.date == sat.toString() }
        // The night is 03:30 to 06:34, both pieces, not the evening's 214 minutes.
        assertEquals(133 + 16, night.sleepMinutes)
        assertEquals(at(sat, 3, 30).toEpochSecond(), night.sleepStart)
        assertEquals(at(sat, 6, 33).toEpochSecond(), night.sleepEnd)
        // ...and the evening sleep is Friday's nap rather than nothing.
        val naps = r.first { it.date == fri.toString() }.naps
        assertEquals(listOf(214), naps.map { it.asleepMin })
        assertEquals(at(fri, 17, 39).toEpochSecond(), naps[0].start)
        assertEquals(324, r.first { it.date == fri.toString() }.sleepMinutes)
    }

    @Test fun aMuchLongerDaySleepIsStillTheNight() {
        // Awake all night, then 5 h 30 asleep from 18:30 (ends 00:00): with only 20 minutes dozed at 04:00, that is the sleep.
        val m = span(at(fri, 0, 0), at(fri, 4, 0), "idle", 80) + span(at(fri, 4, 0), at(fri, 4, 20), "sleep_light", 60) +
            span(at(fri, 4, 20), at(fri, 18, 30), "idle", 80) + span(at(fri, 18, 30), at(sat, 0, 0), "sleep_light", 55) +
            span(at(sat, 0, 0), at(sat, 9, 0), "idle", 80)
        val d = computeDaily(m, emptyList(), emptyList(), Profile(), 2, at(sat, 9, 0)).days.last { it.date == sat.toString() }
        assertEquals(330, d.sleepMinutes)
        assertTrue(d.naps.isEmpty())
    }

    @Test fun usualHoursComeFromTheSleepMinutes() {
        // Ten days asleep 03:00 to 10:00: the ten hours holding the most sleep start between 00:00 and 03:00.
        val m = (0L until 10).flatMap { i -> span(at(fri.minusDays(i), 3, 0), at(fri.minusDays(i), 10, 0), "sleep_light", 55) }.sortedBy { it.ts }
        val h = sleepHabitOf(m, zone)
        assertTrue(h.startMin in 0..180, "start ${h.startMin}")
        assertTrue(h.covers(3 * 60) && h.covers(9 * 60 + 59) && !h.covers(18 * 60) && !h.covers(21 * 60))
        // Too little history: 22:00 to 08:00.
        assertEquals(SleepHabit.DEFAULT, sleepHabitOf(m.takeLast(3 * 420), zone))
        assertTrue(SleepHabit.DEFAULT.covers(23 * 60) && SleepHabit.DEFAULT.covers(7 * 60 + 59) && !SleepHabit.DEFAULT.covers(8 * 60))
    }
}
