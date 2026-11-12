package dev.heartline.analytics

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class HealthByDayTest {
    private val d = LocalDate.of(1970, 1, 2)

    /** The watch's PAI climbs through the day; the day's PAI is where it ended, not the average of the climb. */
    @Test fun paiIsTheDaysLastRecord() {
        val h = listOf(HealthValue("pai", 86_400 + 3_600, 0.3), HealthValue("pai", 86_400 + 40_000, 15.9), HealthValue("pai", 86_400 + 86_000, 16.7),
            HealthValue("stress", 86_400 + 100, 20.0), HealthValue("stress", 86_400 + 200, 40.0),
            HealthValue("resting_hr", 86_400 + 100, 58.0), HealthValue("resting_hr", 86_400 + 900, 55.0))
        val out = healthByDay(h.shuffled(java.util.Random(1)), zone = ZoneOffset.UTC)
        assertEquals(16.7, out.getValue("pai").getValue(d), 1e-9)
        assertEquals(30.0, out.getValue("stress").getValue(d), 1e-9)
        assertEquals(55.0, out.getValue("resting_hr").getValue(d), 1e-9)
    }
}
