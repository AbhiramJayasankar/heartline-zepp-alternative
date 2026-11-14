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

/**
 * The night ending on [d]: its main block, minutes asleep across all blocks, whether it may still be going on, and the
 * watch's sleep blocks it is made of ([parts]).
 */
internal data class Night(val main: SleepBlock?, val asleep: Int, val ongoing: Boolean = false, val parts: List<SleepBlock> = emptyList())

/**
 * When this person usually sleeps: [lengthMin] minutes from [startMin] (minute of the local day), wrapping past midnight.
 * A night is chosen with it: of two sleeps in one day, the one in the usual hours is the night even when the other is
 * somewhat longer (a short night after a long evening nap is still the night, and the nap is still a nap).
 */
data class SleepHabit(val startMin: Int, val lengthMin: Int = HABIT_LENGTH_MIN) {
    fun covers(minuteOfDay: Int): Boolean = Math.floorMod(minuteOfDay - startMin, 1440) < lengthMin
    companion object {
        /** With too little history: 22:00 to 08:00. */
        val DEFAULT = SleepHabit(22 * 60)
    }
}
/** Ten hours: wide enough to hold a usual night with its early and late days, narrow enough to leave the evening out. */
const val HABIT_LENGTH_MIN = 600
/** Sleep outside the usual hours counts this much towards being the night. */
private const val OFF_HOURS_WEIGHT = 0.5
/** Days with sleep needed before the usual hours are trusted over [SleepHabit.DEFAULT]. */
private const val HABIT_MIN_DAYS = 7

/**
 * The usual sleep hours from the watch's own sleep minutes: the [HABIT_LENGTH_MIN] stretch of the day that held the
 * most sleep. It reads the minutes, not the chosen nights, so a night picked wrongly once can't teach it the wrong hours.
 */
fun sleepHabitOf(minutes: List<MinuteSample>, zone: ZoneId): SleepHabit {
    // Sleep minutes per 10-minute slot of the local day.
    val slots = IntArray(144)
    val days = HashSet<LocalDate>()
    for (m in minutes) if (m.state in sleepStates) {
        val t = Instant.ofEpochSecond(m.ts).atZone(zone)
        slots[(t.hour * 60 + t.minute) / 10]++
        days.add(t.toLocalDate())
    }
    if (days.size < HABIT_MIN_DAYS) return SleepHabit.DEFAULT
    val width = HABIT_LENGTH_MIN / 10
    var sum = (0 until width).sumOf { slots[it] }
    var best = sum; var bestStart = 0
    for (start in 1 until 144) {
        sum += slots[(start + width - 1) % 144] - slots[start - 1]
        if (sum > best) { best = sum; bestStart = start }
    }
    return SleepHabit(bestStart * 10)
}

/** How much a set of blocks counts as the night: minutes asleep in the usual hours, plus part of those outside them. */
private fun nightScore(part: List<SleepBlock>, habit: SleepHabit, zone: ZoneId): Double {
    var usual = 0; var other = 0
    for (b in part) for (m in b.rows) if (m.state in sleepStates) {
        val t = Instant.ofEpochSecond(m.ts).atZone(zone)
        if (habit.covers(t.hour * 60 + t.minute)) usual++ else other++
    }
    return usual + OFF_HOURS_WEIGHT * other
}

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
/**
 * ...but a short block (under [JOIN_MIN_ASLEEP] minutes) only joins when it is within [JOIN_NEAR_S] of the night: a few
 * minutes the watch marked as sleep an hour or two before bed is usually sitting still, not a first sleep.
 */
private const val JOIN_MIN_ASLEEP = 30
private const val JOIN_NEAR_S = 1800L
/** A short block after the night gets an hour: waking, then dozing off again, is how most nights end. */
private const val JOIN_AFTER_S = 3600L

internal fun nightFor(blocks: List<SleepBlock>, d: LocalDate, lastTs: Long?, zone: ZoneId, habit: SleepHabit = SleepHabit.DEFAULT): Night {
    // daily.night_for uses midnight + 18 hours and a fixed 86400-second window, even across DST.
    val lo = d.minusDays(1).atStartOfDay(zone).toEpochSecond() + 18 * 3600
    val hi = lo + 86400
    val inside = blocks.filter { it.end >= lo && it.end < hi }
    // Every night-like block, with the blocks that chain onto it, is a candidate night. The longest single block used
    // to win outright, so a night the watch split in two (taken off for a while) lost to an unbroken evening nap, and
    // then showed as neither night nor nap (2026-11-14). Whole candidates are compared now, by [nightScore].
    fun chain(seed: SleepBlock): List<SleepBlock> {
        val part = arrayListOf(seed)
        var grew = true
        while (grew) {
            grew = false
            for (b in inside) if (b !in part && part.any { p ->
                    gapS(p, b) <= (if (b.asleep >= JOIN_MIN_ASLEEP) NIGHT_JOIN_S else if (b.start > p.end) JOIN_AFTER_S else JOIN_NEAR_S) }) { part.add(b); grew = true }
        }
        return part
    }
    // A candidate is built around its longest block: started from a few still minutes before bed, the chain would pull
    // the real night in from two hours away, which is the join those minutes are refused.
    val part = inside.filter { nightLike(it, zone) }.map { chain(it) }.filter { p -> p[0].asleep == p.maxOf { it.asleep } }
        .maxWithOrNull(compareBy<List<SleepBlock>> { nightScore(it, habit, zone) }.thenBy { p -> p.sumOf { it.asleep } }) ?: return Night(null, 0)
    val main = part.maxBy { it.asleep }
    if (main.asleep < 60) return Night(null, 0)
    // A night of several blocks is one stretch, first sleep to last: its window, stages and total agree, and the time
    // awake between blocks counts as awake in bed.
    val night = if (part.size == 1) main else SleepBlock(part.minOf { it.start }).also { n ->
        n.end = part.maxOf { it.end }
        part.sortedBy { it.start }.forEach { n.rows.addAll(it.rows) }
        n.deep = part.sumOf { it.deep }; n.rem = part.sumOf { it.rem }; n.light = part.sumOf { it.light }
        n.awake = ((n.end - n.start) / 60 + 1).toInt() - n.asleep
    }
    // daily.night_for held a night back until 30 min after the last sleep minute (or 3 h asleep) before noon. Heartline
    // shows it straight away instead, flagged as still going on, and the next sync updates it.
    val ongoing = lastTs != null && lastTs >= lo && lastTs < hi && Instant.ofEpochSecond(lastTs).atZone(zone).hour < 12 &&
        (main.asleep < 180 || lastTs - main.end < 1800)
    return Night(night, night.asleep, ongoing, part)
}

/**
 * Naps: sleep of 10 minutes or more that isn't night-like and isn't within two hours of night-like sleep, by the day
 * it started; and any sleep of three hours or more that wasn't the night of its day (a long evening sleep before a
 * short night). The watch marks these minutes as sleep on its own; this only keeps them apart from the nights.
 */
internal fun napsOf(blocks: List<SleepBlock>, zone: ZoneId, habit: SleepHabit = SleepHabit.DEFAULT): Map<LocalDate, List<Nap>> {
    // The night whose day window holds this block's end (see nightFor): 18:00 to 18:00.
    fun nightDay(b: SleepBlock): LocalDate = Instant.ofEpochSecond(b.end).atZone(zone).let { t -> if (t.hour >= 18) t.toLocalDate().plusDays(1) else t.toLocalDate() }
    fun lostTheNight(b: SleepBlock) = b.asleep >= 180 && nightFor(blocks, nightDay(b), null, zone, habit).parts.none { it === b }
    return blocks.filter { b -> b.asleep >= 10 && (lostTheNight(b) ||
            (!nightLike(b, zone) && blocks.none { o -> o !== b && nightLike(o, zone) && gapS(o, b) <= NIGHT_JOIN_S })) }
        .map { b ->
            val hr = b.rows.filter { it.state in sleepStates }.mapNotNull { it.hr?.takeIf { h -> h in 30..220 } }
            Nap(b.start, b.end, b.asleep, b.deep, b.light, b.rem, hr.minOrNull(), hr.takeIf { it.isNotEmpty() }?.average()?.roundToInt())
        }.groupBy { dayOf(it.start, zone) }
}

internal fun sleepScore(asleep: Int, efficiency: Double, deep: Int, rem: Int): Int = rounded(sleepScoreExact(asleep, efficiency, deep, rem)).toInt()
internal fun sleepScoreExact(asleep: Int, efficiency: Double, deep: Int, rem: Int): Double {
    if (asleep <= 0) return 0.0
    var s = 50 * min(1.0, asleep / 480.0)
    s += 20 * ((efficiency - 70) / 25).coerceIn(0.0, 1.0)
    s += 15 * min(1.0, deep.toDouble() / asleep / 0.13)
    s += 15 * min(1.0, rem.toDouble() / asleep / 0.20)
    return s
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
