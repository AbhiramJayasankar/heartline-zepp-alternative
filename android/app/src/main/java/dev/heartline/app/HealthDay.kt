package dev.heartline.app

import java.time.LocalDate
import kotlin.math.roundToInt

/** One stored health reading (type, epoch seconds, value, extra JSON) as the day builder needs it. */
internal data class DayHealthValue(val type: String, val ts: Long, val value: Double, val extra: String?)

private val STRESS_BANDS = listOf(Triple("Relaxed", 1, 39), Triple("Normal", 40, 59), Triple("Medium", 60, 79), Triple("High", 80, 100))
private val SPO2_BANDS = listOf(Triple("Normal", 95, 100), Triple("Slightly low", 90, 94), Triple("Low", 70, 89))
private const val MAX_HR_POINTS = 6000

/**
 * Builds the day detail from what the database holds. Live HR readings replace the per-minute history inside the
 * minutes they cover; values are bucketed for display only when there are more than [MAX_HR_POINTS], keeping each
 * bucket's min and max so spikes survive.
 */
internal fun buildHealthDay(
    date: LocalDate, dayStart: Long, day: ActivityDayUi, live: List<HrPoint>, health: List<DayHealthValue>,
    maxHr: Int, restingHr: Int?, caloriesKcal: Int?, strideM: Double, distanceToday: Int?,
): HealthDayUi {
    val dayEnd = dayStart + 86_400
    val liveDay = live.filter { it.ts in dayStart until dayEnd }.sortedBy { it.ts }
    val liveMinutes = liveDay.map { it.ts / 60 }.toHashSet()
    val merged = (day.minutes.filter { it.hr != null && it.ts / 60 !in liveMinutes }.map { SeriesPoint(it.ts.toDouble(), it.hr!!.toDouble()) } +
        liveDay.distinctBy { it.ts }.map { SeriesPoint(it.ts.toDouble(), it.bpm.toDouble()) }).sortedBy { it.t }
    val hr = thin(merged, MAX_HR_POINTS)
    // Zones and hourly stats weight each value by the time it stands for (a minute row = 60 s, a live reading = until the next one, max 60 s).
    val weighted = merged.mapIndexed { i, p ->
        val next = merged.getOrNull(i + 1)?.t ?: (p.t + 60)
        p to (next - p.t).coerceIn(1.0, 60.0)
    }
    val zones = (1..5).map { z ->
        val lo = kotlin.math.ceil(maxHr * (z + 4) / 10.0).toInt(); val hi = if (z == 5) null else kotlin.math.ceil(maxHr * (z + 5) / 10.0).toInt()
        ZoneTimeUi(z, zoneName(z), lo, hi, weighted.filter { (p, _) -> p.v >= lo && (hi == null || p.v < hi) }.sumOf { it.second }.roundToInt())
    }
    val inWorkout = { t: Double -> day.workouts.any { t >= it.start && t < it.start + it.durationS } }
    val zone4 = zones[3].lowBpm
    val highMinutes = (weighted.filter { (p, _) -> p.v >= zone4 && !inWorkout(p.t) }.sumOf { it.second } / 60).roundToInt()

    fun series(type: String) = health.filter { it.type == type && it.ts in dayStart until dayEnd }.sortedBy { it.ts }
    val stressRows = series("stress").filter { it.value in 1.0..100.0 }
    val stress = stressRows.map { SeriesPoint(it.ts.toDouble(), it.value) }
    val spo2Rows = (series("spo2_sleep") + series("spo2")).filter { it.value in 70.0..100.0 }.sortedBy { it.ts }
    val spo2 = spo2Rows.map { SeriesPoint(it.ts.toDouble(), it.value) }
    val worn = day.minutes.filter { it.state != MinuteState.NOT_WORN && it.state != MinuteState.CHARGING }.map { it.ts / 60 }.toHashSet()
    val tempRows = series("temperature").filter { it.value in 15.0..45.0 }
    val temperature = tempRows.map { SeriesPoint(it.ts.toDouble(), it.value) }
    val temperature2 = tempRows.mapNotNull { r -> sensor2(r.extra)?.takeIf { it in 5.0..45.0 }?.let { SeriesPoint(r.ts.toDouble(), it) } }
    val tempWorn = tempRows.filter { worn.isEmpty() || it.ts / 60 in worn }.map { it.value }
    val steps = day.minutes.filter { it.steps > 0 }.map { SeriesPoint(it.ts.toDouble(), it.steps.toDouble()) }
    val pai = series("pai").lastOrNull()

    return HealthDayUi(
        date, dayStart, hr, liveDay.size, restingHr,
        merged.minOfOrNull { it.v }?.roundToInt(), merged.takeIf { it.isNotEmpty() }?.map { it.v }?.average()?.roundToInt(), merged.maxOfOrNull { it.v }?.roundToInt(),
        zones, hourStats(merged, dayStart), highMinutes,
        stress, stress.takeIf { it.isNotEmpty() }?.map { it.v }?.average()?.roundToInt(), stress.maxOfOrNull { it.v }?.roundToInt(),
        bands(stress, STRESS_BANDS), hourStats(stress, dayStart),
        spo2, spo2.takeIf { it.isNotEmpty() }?.map { it.v }?.average(), spo2.minOfOrNull { it.v }, bands(spo2, SPO2_BANDS),
        temperature, temperature2, tempWorn.takeIf { it.isNotEmpty() }?.average(), tempWorn.minOrNull(), tempWorn.maxOrNull(),
        steps, day.steps, hourStats(steps, dayStart, sum = true), distanceToday ?: (day.steps * strideM).roundToInt().takeIf { day.steps > 0 }, caloriesKcal,
        day.activeMinutes, day.wornMinutes,
        pai?.value, pai?.extra?.let { extraNumber(it, "total") },
        day.minutes, day.sleep, day.workouts,
    )
}

/** Minutes in each band; each reading stands for the gap to the next one (at most 10 minutes). */
private fun bands(points: List<SeriesPoint>, defs: List<Triple<String, Int, Int>>): List<BandTimeUi> {
    val minutes = points.mapIndexed { i, p -> p to (((points.getOrNull(i + 1)?.t ?: (p.t + 60)) - p.t) / 60).coerceIn(1.0, 10.0) }
    return defs.map { (name, lo, hi) -> BandTimeUi(name, lo, hi, minutes.filter { (p, _) -> p.v >= lo && p.v < hi + 1 }.sumOf { it.second }.roundToInt()) }
}

private fun hourStats(points: List<SeriesPoint>, dayStart: Long, sum: Boolean = false): List<HourStatUi> {
    val byHour = points.groupBy { ((it.t - dayStart) / 3600).toInt() }
    return (0 until 24).map { h ->
        val v = byHour[h]?.map { it.v }
        if (v.isNullOrEmpty()) HourStatUi(h, null, null, null, if (sum) 0.0 else null)
        else HourStatUi(h, v.min(), v.average(), v.max(), if (sum) v.sum() else null)
    }
}

/** Keeps every point when few; otherwise each bucket's min and max (in time order) so spikes stay visible. */
internal fun thin(points: List<SeriesPoint>, limit: Int): List<SeriesPoint> {
    if (points.size <= limit) return points
    val buckets = limit / 2; val t0 = points.first().t; val span = (points.last().t - t0).coerceAtLeast(1.0)
    return points.groupBy { ((it.t - t0) / span * (buckets - 1)).toInt() }.toSortedMap().values.flatMap { b ->
        val lo = b.minBy { it.v }; val hi = b.maxBy { it.v }
        if (lo === hi) listOf(lo) else listOf(lo, hi).sortedBy { it.t }
    }
}

private fun sensor2(extra: String?): Double? = extra?.let { Regex("\"sensor2\"\\s*:\\s*\"?(-?[0-9.]+)").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
private fun extraNumber(extra: String, key: String): Double? = Regex("\"$key\"\\s*:\\s*(-?[0-9.]+)").find(extra)?.groupValues?.get(1)?.toDoubleOrNull()
