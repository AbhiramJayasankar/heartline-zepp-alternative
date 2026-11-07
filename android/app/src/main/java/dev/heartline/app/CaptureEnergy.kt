package dev.heartline.app

import kotlin.math.roundToInt

/**
 * Calories over a recorded session, every 5 seconds, from three sources:
 *  - heart rate: the share of heart-rate reserve taken as the share of VO2 reserve (Swain), with your VO2max and weight.
 *    Unlike a plain heart-rate formula this doesn't count the low heart rate of a rest as work;
 *  - a logged treadmill stretch: the ACSM walking (or running) equation from its speed, incline and your weight, which
 *    measures the work itself rather than the heart's response to it (not with the handrails held);
 *  - the watch: over a workout the watch recorded, the heart-rate part is scaled so the span lands on the average of
 *    the two estimates, the watch's and this one.
 */
data class CaptureEnergyUi(
    /** Kilocalories so far, at the start of each 5-second step from 0. */
    val cumulative: List<SeriesPoint>,
    val total: Double,
    /** The heart-rate estimate alone, before the treadmill equation and the watch were brought in. */
    val heartRateOnly: Double,
    val watchSpans: List<WatchEnergy>,
    val treadmill: List<TreadmillEnergy>,
    val exercises: List<ExerciseEnergy>,
    val vo2max: Double, val vo2FromFitness: Boolean,
) {
    /** Kilocalories between two times (seconds into the capture). */
    fun between(a: Double, b: Double): Double = at(b) - at(a)
    fun at(t: Double): Double {
        if (cumulative.isEmpty()) return 0.0
        val i = (t / STEP).toInt().coerceIn(0, cumulative.lastIndex)
        val next = cumulative.getOrNull(i + 1)?.v ?: total
        return cumulative[i].v + (next - cumulative[i].v) * ((t - i * STEP) / STEP).coerceIn(0.0, 1.0)
    }
}

/** A watch workout's own calories against this page's heart-rate estimate over the same span, and what was used. */
data class WatchEnergy(val type: String, val start: Double, val end: Double, val watch: Double, val heartRate: Double, val used: Double, val blended: Boolean)
/** A logged treadmill stretch: the equation's figure, what the machine's display said and what the watch said. */
data class TreadmillEnergy(val start: Double, val end: Double, val speedKmh: Double, val inclinePct: Double, val kcal: Double, val display: Double?)
/** One exercise: everything from its first set to the set after its last (its rests included), and the sets alone. */
data class ExerciseEnergy(val exercise: String, val group: Int, val kcal: Double, val inSets: Double)

private const val STEP = 5.0

/** The gym log's cardio row for a block: a timed row that overlaps it, else the evening's only untimed one of its kind. */
fun cardioLogRow(a: CaptureAnalysisUi, k: CardioUi, log: List<LoggedSet>, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): LoggedSet? {
    val from = a.start + k.start.toLong(); val to = a.start + k.end.toLong()
    val day = sessionDate(a.start, zone).toString()
    val rows = log.filter { it.cardio && it.completed && it.date == day }
    fun end(r: LoggedSet) = r.end ?: (r.start!! + (r.durationS ?: 600.0).toLong())
    return rows.filter { it.start != null && end(it) > from && it.start < to }.maxByOrNull { minOf(to, end(it)) - maxOf(from, it.start!!) }
        ?: rows.filter { it.start == null }.let { u ->
            if (k.name?.contains("treadmill", true) == true) u.filter { it.exercise.contains("treadmill", true) || it.speedKmh != null } else u
        }.singleOrNull()
}

/** VO2 in ml/kg/min for walking or running on a treadmill (ACSM metabolic equations). */
fun acsmVo2(speedKmh: Double, inclinePct: Double): Double {
    val v = speedKmh * 1000 / 60; val g = inclinePct / 100
    return if (speedKmh < 8.0) 3.5 + 0.1 * v + 1.8 * v * g else 3.5 + 0.2 * v + 0.9 * v * g
}

fun captureEnergy(a: CaptureAnalysisUi, log: List<LoggedSet>, profile: ProfileUi, hrRest: Double?, vo2max: Double?): CaptureEnergyUi? {
    val weight = profile.weightKg?.takeIf { it in 25.0..300.0 } ?: return null
    val max = profileMaxHr(profile.maxHr, profile.age) ?: return null
    val rest = (profile.restingHr?.toDouble() ?: hrRest)?.takeIf { it in 30.0..110.0 && it < max - 20 } ?: return null
    // Without a VO2max from your walks and runs, the heart-rate ratio stand-in (Uth et al.).
    val vo2 = vo2max?.takeIf { it in 15.0..90.0 } ?: (15.3 * max / rest)
    val n = kotlin.math.ceil(a.durationS / STEP).toInt().coerceAtLeast(1)
    // Heart rate per step: the 5-second average, else the nearest within a minute; with none, resting.
    val byStep = HashMap<Int, Double>(); a.hr.forEach { byStep[(it.t / STEP).roundToInt()] = it.v }
    val hr = DoubleArray(n) { i ->
        byStep[i] ?: (1..12).asSequence().mapNotNull { d -> byStep[i - d] ?: byStep[i + d] }.firstOrNull() ?: rest
    }
    // kcal per step: VO2 (ml/kg/min) x kg / 1000 = L/min, x 5 kcal/L, x 5 s.
    fun kcal(vo2Ml: Double) = vo2Ml * weight / 1000.0 * 5.0 * STEP / 60.0
    val e = DoubleArray(n) { i -> kcal(3.5 + ((hr[i] - rest) / (max - rest)).coerceIn(0.0, 1.0) * (vo2 - 3.5)) }
    val heartRateOnly = e.sum()
    val fixed = BooleanArray(n)
    // Logged treadmill stretches: the equation over the row's own times, or the block when the row has none.
    val treadmill = ArrayList<TreadmillEnergy>()
    val rows = HashSet<LoggedSet>()
    a.cardio.forEach { k ->
        val day = sessionDate(a.start, java.time.ZoneId.systemDefault()).toString()
        val timed = log.filter { it.cardio && it.completed && it.date == day && it.start != null && it.speedKmh != null &&
            (it.end ?: (it.start!! + (it.durationS ?: 600.0).toLong())) > a.start + k.start && it.start < a.start + k.end }
        val parts = timed.ifEmpty { listOfNotNull(cardioLogRow(a, k, log)?.takeIf { it.speedKmh != null }) }
        parts.filter { rows.add(it) && it.handrails != "held" }.forEach { r ->
            val s = r.start?.let { (it - a.start).toDouble() } ?: k.start
            val t = r.end?.let { (it - a.start).toDouble() } ?: r.start?.let { s + (r.durationS ?: (k.end - k.start)) } ?: k.end
            val per = kcal(acsmVo2(r.speedKmh!!, r.inclinePct ?: 0.0))
            var sum = 0.0
            for (i in (s / STEP).toInt().coerceAtLeast(0) until (t / STEP).toInt().coerceAtMost(n)) { e[i] = per; fixed[i] = true; sum += per }
            treadmill += TreadmillEnergy(s, t, r.speedKmh, r.inclinePct ?: 0.0, sum, r.caloriesKcal)
        }
    }
    // The watch's workouts: meet it halfway. Only workouts that sit inside the recording (its calories are for the whole
    // workout), and not a treadmill walk the equation already covers.
    val watch = ArrayList<WatchEnergy>()
    val scaled = BooleanArray(n)
    a.activities.forEach { w ->
        val kcalW = w.calories?.takeIf { it > 0 }?.toDouble() ?: return@forEach
        val i0 = (w.start / STEP).toInt().coerceAtLeast(0); val i1 = (w.end / STEP).toInt().coerceAtMost(n)
        if (i1 <= i0) return@forEach
        val inside = w.activeS?.let { (w.end - w.start) >= 0.85 * it } ?: true
        val idx = (i0 until i1).filter { !scaled[it] }
        val ours = idx.sumOf { e[it] }
        val eq = idx.filter { fixed[it] }.sumOf { e[it] }; val free = ours - eq
        val covered = idx.count { fixed[it] }.toDouble() / idx.size.coerceAtLeast(1)
        if (!inside || covered > 0.5 || free <= 0) { watch += WatchEnergy(w.type, w.start, w.end, kcalW, ours, ours, false); return@forEach }
        val k = (((ours + kcalW) / 2 - eq) / free).coerceIn(0.6, 1.4)
        idx.filter { !fixed[it] }.forEach { e[it] *= k; scaled[it] = true }
        watch += WatchEnergy(w.type, w.start, w.end, kcalW, ours, idx.sumOf { e[it] }, true)
    }
    val cum = ArrayList<SeriesPoint>(n); var run = 0.0
    for (i in 0 until n) { cum += SeriesPoint(i * STEP, run); run += e[i] }
    val out = CaptureEnergyUi(cum, run, heartRateOnly, watch, treadmill, emptyList(), vo2, vo2max?.takeIf { it in 15.0..90.0 } != null)
    // Each set owns the time until the next set (up to 10 minutes, and not into cardio); the last one a usual rest.
    val sets = a.sets.sortedBy { it.start }
    val restGaps = sets.zipWithNext { p, q -> q.start - p.end }.filter { it in 0.0..600.0 }.sorted()
    val usualRest = restGaps.getOrNull(restGaps.size / 2) ?: 120.0
    val blocks = a.cardio.filter { it.name != null || it.end - it.start >= 300 }
    val per = LinkedHashMap<String, DoubleArray>(); val groups = HashMap<String, Int>()
    sets.forEachIndexed { i, s ->
        val name = s.exercise ?: "Exercise ${s.group}"
        var end = minOf(sets.getOrNull(i + 1)?.start ?: (s.end + usualRest), s.end + 600, a.durationS.toDouble())
        blocks.firstOrNull { it.start >= s.end && it.start < end }?.let { end = it.start }
        val acc = per.getOrPut(name) { DoubleArray(2) }; groups.putIfAbsent(name, s.group)
        acc[0] += out.between(s.start, maxOf(end, s.end)); acc[1] += out.between(s.start, s.end)
    }
    return out.copy(exercises = per.map { (k, v) -> ExerciseEnergy(k, groups[k] ?: 0, v[0], v[1]) })
}
