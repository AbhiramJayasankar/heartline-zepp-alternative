package dev.heartline.analytics

import java.time.*
import kotlin.math.*

internal val sleepStates = setOf("sleep_light", "sleep_deep", "sleep_rem")
internal val noDataStates = setOf("not_worn", "charging", "unknown")
internal fun dayOf(ts: Long, zone: ZoneId): LocalDate = Instant.ofEpochSecond(ts).atZone(zone).toLocalDate()

internal class SleepBlock(val start: Long) {
    var end = start
    val rows = ArrayList<MinuteSample>()
    var deep = 0
    var rem = 0
    var light = 0
    var awake = 0
    val asleep: Int get() = deep + rem + light
}

internal fun sleepBlocks(minutes: List<MinuteSample>): List<SleepBlock> {
    val blocks = ArrayList<SleepBlock>()
    var cur: SleepBlock? = null
    val pending = ArrayList<MinuteSample>(30)
    var gap = 0
    for (m in minutes) {
        if (m.state in sleepStates) {
            val b = cur ?: SleepBlock(m.ts).also { cur = it }
            b.rows.addAll(pending)
            pending.clear()
            b.rows.add(m)
            b.end = m.ts
            gap = 0
        } else if (cur != null) {
            gap++
            if (m.state in noDataStates || gap > 30) {
                pending.clear()
                blocks.add(cur)
                cur = null
                gap = 0
            } else pending.add(m)
        }
    }
    cur?.let { blocks.add(it) }
    for (b in blocks) {
        b.deep = b.rows.count { it.state == "sleep_deep" }
        b.rem = b.rows.count { it.state == "sleep_rem" }
        b.light = b.rows.count { it.state == "sleep_light" }
        b.awake = b.rows.size - b.asleep
    }
    return blocks
}

/** The night ending on [d]: its main block, minutes asleep across all blocks, and whether it may still be going on. */
internal data class Night(val main: SleepBlock?, val asleep: Int, val ongoing: Boolean = false)

/**
 * Sleep that can be a night: three hours or more, or any part of it between midnight and 8:00. An evening or afternoon
 * nap is neither, so it no longer becomes "last night" or adds to the next night's total.
 */
internal fun nightLike(b: SleepBlock, zone: ZoneId): Boolean {
    if (b.asleep >= 180) return true
    return listOf(b.start, b.end).any { ts ->
        val mid = dayOf(ts, zone).atStartOfDay(zone).toEpochSecond()
        b.start < mid + 8 * 3600 && b.end >= mid
    }
}

/** Minutes between two blocks, 0 when they overlap. */
private fun gapS(a: SleepBlock, b: SleepBlock): Long = maxOf(0L, maxOf(a.start - b.end, b.start - a.end))

/** Blocks within two hours of the main one belong to the same night (a long wake in the small hours), chained outwards. */
private const val NIGHT_JOIN_S = 2 * 3600L

internal fun nightFor(blocks: List<SleepBlock>, d: LocalDate, lastTs: Long?, zone: ZoneId): Night {
    // daily.night_for uses midnight + 18 hours and a fixed 86400-second window, even across DST.
    val lo = d.minusDays(1).atStartOfDay(zone).toEpochSecond() + 18 * 3600
    val hi = lo + 86400
    val inside = blocks.filter { it.end >= lo && it.end < hi }
    val main = inside.filter { nightLike(it, zone) }.maxByOrNull { it.asleep } ?: return Night(null, 0)
    if (main.asleep < 60) return Night(null, 0)
    val part = arrayListOf(main)
    var grew = true
    while (grew) {
        grew = false
        for (b in inside) if (b !in part && part.any { gapS(it, b) <= NIGHT_JOIN_S }) { part.add(b); grew = true }
    }
    // daily.night_for held a night back until 30 min after the last sleep minute (or 3 h asleep) before noon. Heartline
    // shows it straight away instead, flagged as still going on, and the next sync updates it.
    val ongoing = lastTs != null && lastTs >= lo && lastTs < hi && Instant.ofEpochSecond(lastTs).atZone(zone).hour < 12 &&
        (main.asleep < 180 || lastTs - main.end < 1800)
    return Night(main, part.sumOf { it.asleep }, ongoing)
}

/**
 * Naps: sleep of 10 minutes or more that isn't night-like and isn't within two hours of night-like sleep, by the day
 * it started. The watch marks these minutes as sleep on its own; this only keeps them apart from the nights.
 */
internal fun napsOf(blocks: List<SleepBlock>, zone: ZoneId): Map<LocalDate, List<Nap>> =
    blocks.filter { b -> b.asleep >= 10 && !nightLike(b, zone) && blocks.none { o -> o !== b && nightLike(o, zone) && gapS(o, b) <= NIGHT_JOIN_S } }
        .map { b ->
            val hr = b.rows.filter { it.state in sleepStates }.mapNotNull { it.hr?.takeIf { h -> h in 30..220 } }
            Nap(b.start, b.end, b.asleep, b.deep, b.light, b.rem, hr.minOrNull(), hr.takeIf { it.isNotEmpty() }?.average()?.roundToInt())
        }.groupBy { dayOf(it.start, zone) }

internal fun sleepScore(asleep: Int, efficiency: Double, deep: Int, rem: Int): Int {
    if (asleep <= 0) return 0
    var s = 50 * min(1.0, asleep / 480.0)
    s += 20 * ((efficiency - 70) / 25).coerceIn(0.0, 1.0)
    s += 15 * min(1.0, deep.toDouble() / asleep / 0.13)
    s += 15 * min(1.0, rem.toDouble() / asleep / 0.20)
    return rounded(s).toInt()
}

internal fun restingHrFrom(rows: List<MinuteSample>): Double? {
    val hr = rows.filter { it.hr != null && it.hr != 0 }
    if (hr.size < 15) return null
    var best: Double? = null
    var j = 0
    // Reuse one scratch window rather than constructing a sequence for each minute.
    val scratch = DoubleArray(hr.size)
    for (i in hr.indices) {
        while (hr[i].ts - hr[j].ts >= 1800) j++
        if (i - j + 1 >= 15) {
            for (k in j..i) scratch[k - j] = hr[k].hr!!.toDouble()
            java.util.Arrays.sort(scratch, 0, i - j + 1)
            val n = i - j + 1
            val v = if (n % 2 == 0) (scratch[n / 2 - 1] + scratch[n / 2]) / 2 else scratch[n / 2]
            best = if (best == null) v else min(best, v)
        }
    }
    return best?.let { rounded(it) }
}

/**
 * Awake resting heart rate: the lowest 10-minute median of heart rate while awake, worn and still (no steps, movement
 * intensity 30 or less), from runs of consecutive still minutes with at least 8 readings in the window. Closer to the
 * medical definition (awake, still) than the sleeping value, which is usually a few beats lower.
 */
internal fun awakeRestingHrFrom(rows: List<MinuteSample>): Double? {
    // The watch's minute kind repeats the last code, so most awake minutes read "active" even when sitting; its movement
    // intensity (0-100) is the reliable stillness signal.
    val still = rows.filter { it.state !in sleepStates && it.state !in noDataStates && it.steps == 0 && it.intensity <= 30 && it.hr != null && it.hr in 30..220 }
    var best: Double? = null
    var runStart = 0
    for (i in still.indices) {
        // A run of still minutes ends at a gap of more than 2 minutes (movement, sleep or the watch off).
        if (i > 0 && still[i].ts - still[i - 1].ts > 120) runStart = i
        var j = i
        while (j > runStart && still[i].ts - still[j - 1].ts < 600) j--
        if (i - j + 1 >= 8) {
            val v = median(DoubleArray(i - j + 1) { still[j + it].hr!!.toDouble() })
            best = if (best == null) v else min(best, v)
        }
    }
    return best?.let { rounded(it) }
}

internal fun sleepRegularity(minutes: List<MinuteSample>): Double? {
    val states = HashMap<Long, Boolean>()
    for (m in minutes) if (m.state !in noDataStates) states[Math.floorDiv(m.ts, 60)] = m.state in sleepStates
    var same = 0
    var total = 0
    for ((k, s) in states) {
        val next = states[k + 1440] ?: continue
        total++
        if (s == next) same++
    }
    return if (total < 360) null else rounded(-100 + 200.0 * same / total, 1)
}
