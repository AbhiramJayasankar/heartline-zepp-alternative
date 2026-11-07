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
    }
}
