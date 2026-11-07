package dev.heartline.analytics

import java.time.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A night shows as soon as it syncs, flagged as possibly ongoing until 30 min of data follow it (or noon). */
class OngoingNightTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val day = LocalDate.of(2026, 11, 7)

    /** Light sleep 02:40 to 10:21 (kind 9), then awake idle minutes up to [awakeUntil]. */
    private fun minutes(awakeUntil: LocalTime): List<MinuteSample> {
        val out = ArrayList<MinuteSample>()
        var t = day.atTime(2, 40).atZone(zone)
        val wake = day.atTime(10, 22).atZone(zone)
        val end = day.atTime(awakeUntil).atZone(zone)
        while (t < end) {
            val asleep = t < wake
            out += MinuteSample(t.toEpochSecond(), if (asleep) 9 else 1, 0, 0, 60, 0, 0, 0, if (asleep) "sleep_light" else "idle")
            t = t.plusMinutes(1)
        }
        return out
    }
    private fun night(awakeUntil: LocalTime, hold: Boolean = false) =
        computeDaily(minutes(awakeUntil), emptyList(), emptyList(), Profile(), 2, day.atTime(awakeUntil).atZone(zone), hold).days.last()

    @Test fun showsTheNightStraightAway() {
        val soon = night(LocalTime.of(10, 33))
        assertEquals(462, soon.sleepMinutes)
        assertTrue(soon.sleepOngoing)
        assertNull(night(LocalTime.of(10, 33), hold = true).sleepMinutes) // Python's daily.night_for
    }

    @Test fun settlesHalfAnHourAfterWaking() {
        val later = night(LocalTime.of(10, 55))
        assertEquals(462, later.sleepMinutes)
        assertFalse(later.sleepOngoing)
    }
}
