package dev.heartline.analytics

import kotlin.math.*

/**
 * A set from the user's gym log, in seconds from the capture start. Logged times are ground truth for when a set
 * happened (they include a few seconds of typing either side); the wrist supplies reps, tempo and arc.
 */
data class LoggedCaptureSet(
    val start: Double, val end: Double, val exercise: String, val label: String,
    val loadKg: Double?, val reps: Double?, val warmUp: Boolean,
)

/** One set after merging: [set] carries the logged boundaries, [log] the logged values, [detected] the watch's own find. */
internal data class MergedSet(val set: RhythmSet, val log: LoggedCaptureSet?, val detected: RhythmSet?)

/** Seconds a logged set may sit outside the detected run and still be the same set (typing before and after it). */
private const val LOG_SLACK_S = 10.0
/** Lowest wrist-rhythm score at which reps inside a logged set the detector missed are counted. */
private const val WINDOW_RHYTHM_MIN = 0.3

/**
 * Pairs logged sets with detected rhythm sets by overlap, each used at most once, largest overlap first. A logged set
 * the detector missed gets its reps counted inside the logged window. Detected sets with no logged set are returned
 * apart: with a complete log they are either sets left out of it or motion that only looked like reps.
 */
internal fun mergeLoggedSets(rec: Recording, detected: List<RhythmSet>, logged: List<LoggedCaptureSet>): Pair<List<MergedSet>, List<RhythmSet>> {
    val pairs = ArrayList<Triple<Int, Int, Double>>()
    logged.forEachIndexed { li, l ->
        detected.forEachIndexed { di, d ->
            val overlap = min(l.end + LOG_SLACK_S, d.end) - max(l.start - LOG_SLACK_S, d.start)
            if (overlap > 0) pairs += Triple(li, di, overlap)
        }
    }
    val logTaken = HashMap<Int, Int>(); val detTaken = HashSet<Int>()
    for ((li, di, _) in pairs.sortedByDescending { it.third }) {
        if (li in logTaken || di in detTaken) continue
        logTaken[li] = di; detTaken += di
    }
    val merged = logged.mapIndexed { li, l ->
        val d = logTaken[li]?.let { detected[it] }
        val set = d?.copy(start = l.start, end = l.end)
            ?: captureWindowReps(rec, l.start, l.end)
            ?: RhythmSet(l.start, l.end, 0, Double.NaN)
        MergedSet(set, l, d)
    }.sortedBy { it.set.start }
    return merged to detected.filterIndexed { i, _ -> i !in detTaken }
}

/**
 * Reps inside a known window, with the detector's own filters and peak counter but no search for where the set is and
 * no padding past the window. Returns null across a stream gap (never interpolate a reconnect into a set), when the
 * window is too short to hold a rep, or when the rhythm is too weak to count.
 */
internal fun captureWindowReps(rec: Recording, start: Double, end: Double): RhythmSet? {
    if (end - start < 8) return null
    val clock = if (rec.watchClock) rec.accT else rec.accArrivalT
    val from = start - 10; val to = end + 10
    val idx = clock.indices.filter { clock[it].isFinite() && clock[it] in from..to && rec.acc[it].all { v -> v.isFinite() } }
    if (idx.size < 250) return null
    if (idx.zipWithNext().any { (a, b) -> clock[b] - clock[a] > .5 }) return null
    val origin = clock[idx.first()]
    val count = ceil((clock[idx.last()] - origin) * 25).toInt()
    val axes = List(3) { DoubleArray(count) }
    var j = 0
    for (i in 0 until count) {
        val at = origin + i / 25.0
        while (j < idx.size - 2 && clock[idx[j + 1]] <= at) j++
        val a = idx[j]; val b = idx[min(j + 1, idx.lastIndex)]
        val mix = if (clock[b] <= clock[a]) 0.0 else ((at - clock[a]) / (clock[b] - clock[a])).coerceIn(0.0, 1.0)
        for (k in 0..2) axes[k][i] = rec.acc[a][k] + mix * (rec.acc[b][k].toDouble() - rec.acc[a][k])
    }
    val filtered = axes.map { zeroPhase(it, butterworth(25.0, .12, 1.0)) }
    val gravity = axes.map { zeroPhase(it, butterworth(25.0, 1.5)) }
    val a = max(0, floor(start - origin).toInt()); val b = min(count / 25, ceil(end - origin).toInt())
    if (b - a < 8) return null
    val peaks = rhythmReps(filtered, a, b, pad = 0)
    // The detector passed over this set, so its rhythm is weak. Count only when it is still clearly periodic: on the
    // 2 and 3 Nov captures, windows below 0.3 (heavy bench, assisted pull-ups, leg raises) gave about half the real reps.
    val core = peaks.signal.copyOf().also { x -> val mu = mean(x); for (i in x.indices) x[i] -= mu }
    if (core.size < 60 || acfMaximum(core, 30, 200).second < WINDOW_RHYTHM_MIN) return null
    return RhythmSet(start, end, peaks.reps, peaks.tempoS, rhythmRepDetail(peaks, gravity, origin),
        DoubleArray(3) { mean(gravity[it].copyOfRange(a * 25, b * 25)) }, peaks.axis)
}
