package dev.heartline.analytics

import java.time.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Automatic HRV counts without heart checks: daytime samples as awake HRV, overnight samples in readiness and the sleep score. */
class AutoHrvTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val last = LocalDate.of(2026, 11, 8)

    /** Five nights 00:00-07:00 asleep, awake (and still) until midnight. */
    private fun minutes(): List<MinuteSample> {
        val out = ArrayList<MinuteSample>()
        var t = last.minusDays(4).atStartOfDay(zone)
        val end = last.atTime(12, 0).atZone(zone)
        while (t < end) {
            val asleep = t.hour < 7
            out += MinuteSample(t.toEpochSecond(), if (asleep) 9 else 1, 0, 0, if (asleep) 55 else 75, 0, 0, 0, if (asleep) "sleep_light" else "idle")
            t = t.plusMinutes(1)
        }
        return out
    }
    private fun at(d: LocalDate, h: Int) = d.atTime(h, 0).atZone(zone).toEpochSecond()
    private fun run(health: List<HealthValue>) = computeDaily(minutes(), health, emptyList(), Profile(), 5, last.atTime(12, 0).atZone(zone))

    @Test fun daytimeSamplesAreAwakeHrvAndCountWithoutAHeartCheck() {
        val health = (0..4).flatMap { i -> val d = last.minusDays(i.toLong()); listOf(40.0, 50.0, 60.0).mapIndexed { j, v ->
            HealthValue(DAY_HRV_SAMPLE, at(d, 8 + j), if (i == 0) v + 20 else v) } }
        val r = run(health)
        val today = r.days.last()
        assertEquals(70.0, today.hrvRmssd) // the median of today's 60, 70, 80
        val f = r.today.readinessFactors.single { it.name == "HRV" }
        assertTrue(f.detail.startsWith("Awake RMSSD 70 ms vs your usual 50"), f.detail)
        assertEquals(0.35, f.weight)
        assertEquals(2.0, f.z) // ln(70/50) / 0.1 = 3.4, counted as 2
        assertEquals(15 * 2 * 0.35 / r.today.readinessFactors.sumOf { it.weight }, f.points, 1e-9)
    }

    @Test fun overnightHrvCountsFromTheSecondNightAtAThirdOfItsWeight() {
        val health = listOf(HealthValue("hrv_sleep", at(last.minusDays(1), 7), 60.0), HealthValue("hrv_sleep", at(last, 7), 66.0))
        val r = run(health)
        val f = r.today.readinessFactors.single { it.name == "HRV" }
        assertTrue(f.detail.startsWith("Overnight RMSSD 66 ms vs your usual 60 from 1 earlier night"), f.detail)
        assertEquals(0.35 / 3, f.weight, 1e-9)
        assertEquals(Math.log(66.0 / 60.0) / 0.1, f.z, 1e-9) // one night: the spread is the 0.1 floor on the log scale
        // The first night has nothing to compare with.
        assertNull(r.days.single { it.date == last.minusDays(1).toString() }.sleepHrv)
    }

    @Test fun overnightHrvIsPartOfTheSleepScore() {
        val nights = listOf(70.0, 60.0, 65.0, 65.0)
        fun health(tonight: Double) = nights.mapIndexed { i, v -> HealthValue("hrv_sleep", at(last.minusDays((4 - i).toLong()), 7), v) } +
            HealthValue("hrv_sleep", at(last, 7), tonight)
        val usualNight = run(health(65.0)).days.last()
        val lowNight = run(health(52.0)).days.last()
        val part = assertNotNull(usualNight.sleepHrv)
        assertEquals(10.0, part.possible)
        assertEquals(10.0, part.points) // at the usual: full marks
        val base = sleepScore(usualNight.sleepMinutes!!, usualNight.sleepEfficiency!!, usualNight.deepMin!!, usualNight.remMin!!)
        // Full HRV marks: the four sleep parts fill 90 and HRV the other 10.
        assertEquals(Math.round(0.9 * sleepScoreExact(usualNight.sleepMinutes!!, usualNight.sleepEfficiency!!, usualNight.deepMin!!, usualNight.remMin!!) + 10).toInt(), usualNight.sleepScore)
        val low = assertNotNull(lowNight.sleepHrv)
        assertEquals(0.0, low.points) // 20% under the usual is two spreads down
        assertTrue(lowNight.sleepScore!! < usualNight.sleepScore!!)
        // Readiness reads the sleep score without its HRV part, so HRV is not counted twice.
        val sleep = run(health(52.0)).today.readinessFactors.single { it.name == "Sleep" }
        assertEquals(base.toDouble(), sleep.value)
    }
}
