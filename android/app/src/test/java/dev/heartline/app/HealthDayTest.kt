package dev.heartline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HealthDayTest {
    private val date = LocalDate.of(2026, 11, 2)
    private val start = 1_793_557_800L // 2026-11-02 00:00 IST

    private fun day(minutes: List<MinuteUi>) = ActivityDayUi(date, minutes, minutes.sumOf { it.steps }, 0, 0, minutes.size, null, null, null, null, emptyList())

    @Test fun liveReadingsReplaceTheMinuteTheyCover() {
        val minutes = (0 until 3).map { MinuteUi(start + it * 60L, MinuteState.IDLE, 0, 70) }
        val live = listOf(HrPoint(start + 61, 90), HrPoint(start + 90, 95))
        val d = buildHealthDay(date, start, day(minutes), live, emptyList(), 200, 55, null, 0.72, null)
        assertEquals(listOf(70.0, 90.0, 95.0, 70.0), d.hr.map { it.v })
        assertEquals(2, d.hrLiveSamples); assertEquals(70, d.hrMin); assertEquals(95, d.hrMax)
    }

    @Test fun zonesUseFiftyToNinetyPercentOfMax() {
        val minutes = listOf(110, 130, 150, 170, 190).mapIndexed { i, hr -> MinuteUi(start + i * 60L, MinuteState.ACTIVE, 0, hr) }
        val d = buildHealthDay(date, start, day(minutes), emptyList(), emptyList(), 200, null, null, 0.72, null)
        assertEquals(listOf(100, 120, 140, 160, 180), d.hrZones.map { it.lowBpm })
        assertEquals(listOf(60, 60, 60, 60, 60), d.hrZones.map { it.seconds })
    }

    @Test fun stressBandsCountTheTimeEachReadingStandsFor() {
        val stress = listOf(20.0, 45.0, 65.0, 85.0).mapIndexed { i, v -> DayHealthValue("stress", start + 3600 + i * 300L, v, null) }
        val d = buildHealthDay(date, start, day(emptyList()), emptyList(), stress, 190, null, null, 0.72, null)
        assertEquals(listOf("Relaxed", "Normal", "Medium", "High"), d.stressBands.map { it.name })
        assertEquals(listOf(5, 5, 5, 1), d.stressBands.map { it.minutes })
        assertEquals(54, d.stressAvg); assertEquals(85, d.stressMax)
    }

    @Test fun temperatureKeepsTheSecondWord() {
        val t = listOf(DayHealthValue("temperature", start + 120, 32.5, "{\"sensor2\":\"30.25\"}"))
        val d = buildHealthDay(date, start, day(emptyList()), emptyList(), t, 190, null, null, 0.72, null)
        assertEquals(32.5, d.temperature.single().v, 0.0); assertEquals(30.25, d.temperature2.single().v, 0.0)
    }

    @Test fun emptyDayIsValid() {
        val d = buildHealthDay(date, start, day(emptyList()), emptyList(), emptyList(), 190, null, null, 0.72, null)
        assertTrue(d.hr.isEmpty()); assertNull(d.hrAvg); assertNull(d.stressAvg); assertEquals(24, d.hrByHour.size); assertNull(d.distanceM)
    }

    @Test fun thinningKeepsSpikes() {
        val points = (0 until 20_000).map { SeriesPoint(it.toDouble(), if (it == 12_345) 200.0 else 70.0) }
        val thinned = thin(points, 6000)
        assertTrue(thinned.size <= 6000); assertEquals(200.0, thinned.maxOf { it.v }, 0.0)
    }
}
