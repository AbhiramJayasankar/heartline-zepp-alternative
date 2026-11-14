package dev.heartline.analytics

import kotlin.math.*

/** What the feet were doing in one second. */
enum class Gait { NONE, STILL, WALK, RUN }

/** One stretch of running or walking. Times are whole seconds from the session's start, [end] exclusive. */
data class GaitBout(
    val start: Int, val end: Int, val running: Boolean, val cadenceSpm: Int?, val avgHr: Int?, val peakHr: Int?,
    val startHr: Int?, val endHr: Int?, val distanceM: Double?, val strideCm: Int?,
) {
    val seconds: Int get() = end - start
    /** Seconds per kilometre, from [distanceM] when there is enough of it to mean something. */
    val paceSPerKm: Double? get() = distanceM?.takeIf { it >= 50 }?.let { seconds * 1000.0 / it }
}

/**
 * A session's running and walking: the bouts in order and what they add up to.
 *
 * [recovery60] is how far heart rate fell over the first minute of the walks that follow a run (from the peak within
 * 15 s of slowing down, as wrist heart rate lags), averaged. [driftBpm] is the running heart rate of the last third of
 * the running time minus the first third, with [driftCadence] the change in cadence over the same thirds, so a rise
 * that came from running faster can be told from one that came from tiring (cardiac drift).
 */
data class GaitSummary(
    val bouts: List<GaitBout>, val runS: Int, val walkS: Int, val stillS: Int,
    val runDistanceM: Double?, val walkDistanceM: Double?,
    val runCadence: Int?, val walkCadence: Int?, val runHr: Int?, val walkHr: Int?, val runPeakHr: Int?,
    val recovery60: Int?, val recoveries: Int, val driftBpm: Int?, val driftCadence: Int?,
) {
    val runs: List<GaitBout> get() = bouts.filter { it.running }
    val walks: List<GaitBout> get() = bouts.filter { !it.running }
    val runPaceSPerKm: Double? get() = runDistanceM?.takeIf { it >= 100 }?.let { runS * 1000.0 / it }
    val walkPaceSPerKm: Double? get() = walkDistanceM?.takeIf { it >= 100 }?.let { walkS * 1000.0 / it }
    /** Run and walk alternate: two or more runs with walking between them. */
    val intervals: Boolean get() = runs.size >= 2 && walkS >= 60
}

/**
 * Steps per minute at which a stride becomes a run. People switch from walking to running between about 120 and 140
 * steps a minute; on the user's own outdoor workouts the watch's cadence sat at 88 to 103 walking and 131 to 151
 * running, so a run starts at [RUN_START_SPM] and lasts until cadence falls under [RUN_END_SPM] (hysteresis, so a
 * stride on the boundary doesn't flicker between the two).
 */
const val RUN_START_SPM = 125.0
const val RUN_END_SPM = 115.0
/** Under this many steps a minute the feet are taken to be still (standing, waiting at a crossing). */
const val MOVING_SPM = 50.0

/**
 * Labels each second from the watch's per-second cadence ([Double.NaN] where it has none) after a 9-second median, which
 * takes out single-second dropouts without blurring a change of pace by more than a few seconds.
 */
fun gaitFromCadence(cadence: DoubleArray): Array<Gait> {
    val smooth = DoubleArray(cadence.size) { i ->
        val w = (max(0, i - 4)..min(cadence.lastIndex, i + 4)).map { cadence[it] }.filter { it.isFinite() }
        if (w.size < 3) Double.NaN else median(w.toDoubleArray())
    }
    var running = false
    return Array(cadence.size) { i ->
        val v = smooth[i]
        when {
            !v.isFinite() -> { running = false; Gait.NONE }
            v < MOVING_SPM -> { running = false; Gait.STILL }
            else -> {
                running = if (running) v >= RUN_END_SPM else v >= RUN_START_SPM
                if (running) Gait.RUN else Gait.WALK
            }
        }
    }
}

/**
 * Bouts of running and walking from per-second labels, with heart rate ([hr], 0 where none), cadence and, where known,
 * speed (m/s) and stride (cm) per second.
 *
 * Labels change too readily second to second to read as bouts: a few seconds of anything between two stretches of the
 * same gait are bridged ([bridgeS]), then any bout shorter than [minBoutS] joins its longer neighbour, so a stumble or a
 * kerb doesn't split a run and a few fast strides don't make one.
 */
fun gaitSummary(kind: Array<Gait>, hr: IntArray, cadence: DoubleArray? = null, speedMs: DoubleArray? = null,
                strideCm: DoubleArray? = null, minBoutS: Int = 20, bridgeS: Int = 8): GaitSummary? {
    val n = kind.size
    if (n == 0) return null
    data class Span(var start: Int, var end: Int, var kind: Gait)
    val spans = ArrayList<Span>()
    for (i in 0 until n) {
        val last = spans.lastOrNull()
        if (last != null && last.kind == kind[i]) last.end = i + 1 else spans += Span(i, i + 1, kind[i])
    }
    fun merge() {
        var i = 0
        while (i < spans.size - 1) {
            if (spans[i].kind == spans[i + 1].kind) { spans[i].end = spans[i + 1].end; spans.removeAt(i + 1) } else i++
        }
    }
    // Bridge short gaps between two stretches of the same gait.
    for (i in 1 until spans.lastIndex) {
        val s = spans[i]
        if (s.end - s.start <= bridgeS && spans[i - 1].kind == spans[i + 1].kind && spans[i - 1].kind in setOf(Gait.WALK, Gait.RUN))
            s.kind = spans[i - 1].kind
    }
    merge()
    // Short bouts join their longer neighbour, shortest first; long stillness and missing data stay as breaks.
    while (true) {
        val i = spans.indices.filter { spans[it].kind in setOf(Gait.WALK, Gait.RUN) && spans[it].end - spans[it].start < minBoutS }
            .minByOrNull { spans[it].end - spans[it].start } ?: break
        val before = spans.getOrNull(i - 1)?.takeIf { it.kind in setOf(Gait.WALK, Gait.RUN) }
        val after = spans.getOrNull(i + 1)?.takeIf { it.kind in setOf(Gait.WALK, Gait.RUN) }
        val into = listOfNotNull(before, after).maxByOrNull { it.end - it.start }
        spans[i].kind = into?.kind ?: Gait.STILL
        merge()
    }
    fun hrs(a: Int, b: Int) = (a until b).map { hr.getOrElse(it) { 0 } }.filter { it > 0 }
    fun meanOf(x: DoubleArray?, a: Int, b: Int): Double? = x?.let { arr ->
        (a until min(b, arr.size)).map { arr[it] }.filter { it.isFinite() && it > 0 }.takeIf { it.isNotEmpty() }?.average()
    }
    val bouts = spans.filter { it.kind == Gait.WALK || it.kind == Gait.RUN }.map { s ->
        val h = hrs(s.start, s.end)
        GaitBout(s.start, s.end, s.kind == Gait.RUN, meanOf(cadence, s.start, s.end)?.roundToInt(), h.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            h.maxOrNull(), hrs(s.start, min(s.end, s.start + 5)).firstOrNull(), hrs(max(s.start, s.end - 5), s.end).lastOrNull(),
            speedMs?.let { v -> (s.start until min(s.end, v.size)).sumOf { v[it].takeIf { x -> x.isFinite() && x >= 0 } ?: 0.0 } },
            meanOf(strideCm, s.start, s.end)?.roundToInt())
    }
    if (bouts.isEmpty()) return null
    fun weighted(list: List<GaitBout>, f: (GaitBout) -> Int?): Int? =
        list.mapNotNull { b -> f(b)?.let { it to b.seconds } }.takeIf { it.isNotEmpty() }
            ?.let { l -> (l.sumOf { it.first.toDouble() * it.second } / l.sumOf { it.second }).roundToInt() }
    val runs = bouts.filter { it.running }; val walks = bouts.filter { !it.running }
    // The first minute of each walk straight after a run: from the peak within 15 s of slowing, 60 s on.
    val drops = bouts.zipWithNext().mapNotNull { (a, b) ->
        if (!a.running || b.running || b.start - a.end > bridgeS) return@mapNotNull null
        val peakT = (max(0, b.start - 5)..min(b.end - 1, b.start + 15)).filter { hr.getOrElse(it) { 0 } > 0 }.maxByOrNull { hr[it] } ?: return@mapNotNull null
        val at = peakT + 60
        if (at >= b.end) return@mapNotNull null
        val later = hrs(at - 2, at + 3).takeIf { it.isNotEmpty() }?.average() ?: return@mapNotNull null
        hr[peakT] - later
    }
    // Drift: running seconds only, first third against last third.
    val runSeconds = runs.flatMap { b -> (b.start until b.end).toList() }
    var drift: Int? = null; var driftCadence: Int? = null
    if (runSeconds.size >= 600) {
        val third = runSeconds.size / 3
        val first = runSeconds.take(third); val last = runSeconds.takeLast(third)
        val h1 = first.map { hr.getOrElse(it) { 0 } }.filter { it > 0 }; val h2 = last.map { hr.getOrElse(it) { 0 } }.filter { it > 0 }
        if (h1.size >= third / 2 && h2.size >= third / 2) drift = (h2.average() - h1.average()).roundToInt()
        if (cadence != null) {
            val c1 = first.mapNotNull { cadence.getOrNull(it)?.takeIf { v -> v.isFinite() && v > 0 } }
            val c2 = last.mapNotNull { cadence.getOrNull(it)?.takeIf { v -> v.isFinite() && v > 0 } }
            if (c1.size >= third / 2 && c2.size >= third / 2) driftCadence = (c2.average() - c1.average()).roundToInt()
        }
    }
    val stillS = spans.filter { it.kind == Gait.STILL }.sumOf { it.end - it.start }
    return GaitSummary(bouts, runs.sumOf { it.seconds }, walks.sumOf { it.seconds }, stillS,
        if (speedMs == null) null else runs.sumOf { it.distanceM ?: 0.0 }, if (speedMs == null) null else walks.sumOf { it.distanceM ?: 0.0 },
        weighted(runs) { it.cadenceSpm }, weighted(walks) { it.cadenceSpm }, weighted(runs) { it.avgHr }, weighted(walks) { it.avgHr },
        runs.mapNotNull { it.peakHr }.maxOrNull(), drops.takeIf { it.isNotEmpty() }?.average()?.roundToInt(), drops.size, drift, driftCadence)
}

/** Holds each (second, value) sample until the next one, for at most [holdS] seconds, over [n] seconds. */
fun perSecond(points: List<Pair<Int, Double>>, n: Int, holdS: Int = 5): DoubleArray {
    val out = DoubleArray(n) { Double.NaN }
    val sorted = points.sortedBy { it.first }
    for ((k, p) in sorted.withIndex()) {
        val until = min(n, min(sorted.getOrNull(k + 1)?.first ?: n, p.first + holdS))
        for (t in max(0, p.first) until until) out[t] = p.second
    }
    return out
}
