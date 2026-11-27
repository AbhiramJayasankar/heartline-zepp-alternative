package dev.heartline.analytics

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Riding or stopped, from [start] until [end] (seconds from the session's start). */
data class RideBout(val start: Int, val end: Int, val riding: Boolean, val distanceM: Double, val avgHr: Int?) {
    val seconds: Int get() = end - start
    /** km/h over the bout, when it rode far enough to say. */
    val kmh: Double? get() = if (riding && seconds >= 10 && distanceM >= 30) distanceM / seconds * 3.6 else null
}

/** One kilometre of riding: when it was reached ([end]), its moving time, speed and heart rate. */
data class RideSplit(val km: Int, val end: Int, val movingS: Int, val kmh: Double, val avgHr: Int?)

/**
 * A ride: distance and moving time, speed (average while moving, the fastest 30 seconds and 5 minutes), stops, climbing
 * and descending, heart rate while riding, how far each heartbeat carried you, kilometre splits and the riding and
 * stopped stretches in order.
 */
data class RideSummary(
    val distanceM: Double, val movingS: Int, val stoppedS: Int, val coveredS: Int,
    val avgKmh: Double?, val top30Kmh: Double?, val top5MinKmh: Double?,
    val stops: Int, val longestStopS: Int,
    val climbM: Double?, val descentM: Double?,
    val ridingHr: Int?, val peakHr: Int?, val metresPerBeat: Double?,
    val splits: List<RideSplit>, val bouts: List<RideBout>,
) {
    /** Steadiness: how much the kilometre speeds varied (coefficient of variation, %), with 3 or more kilometres. */
    val spreadPct: Double? get() = splits.map { it.kmh }.takeIf { it.size >= 3 }?.let { v ->
        val m = v.average(); kotlin.math.sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1)) / m * 100
    }
    /** How much slower (positive) the last third of the kilometres were than the first, %. */
    val fadePct: Double? get() = splits.takeIf { it.size >= 3 }?.let { s ->
        val third = max(1, s.size / 3)
        (s.take(third).map { it.kmh }.average() / s.takeLast(third).map { it.kmh }.average() - 1) * 100
    }
}

/** Moving faster than this (m/s, over 10 seconds) is riding: a walking pace pushing the bike counts as stopped. */
const val RIDE_MIN_SPEED = 2.0
/** Stretches shorter than this (seconds) join their neighbours, so a slow corner isn't a stop. */
private const val RIDE_MIN_BOUT = 20

/**
 * A ride from per-second [metres] (NaN where the track can't say), [hr] (bpm, 0 where none) and [altM] (metres, NaN
 * where none, as a barometer or GPS gives it), over [n] seconds. With [mask], only the seconds it marks are the ride
 * (a session that walks to the bike and back): they are read as one ride, and every time in the result is still
 * seconds from the session's start. Null when it never rode a minute.
 */
fun rideSummary(n: Int, metres: DoubleArray, hr: IntArray, altM: DoubleArray? = null, mask: BooleanArray? = null): RideSummary? {
    if (mask == null) return rideOver(n, metres, hr, altM)
    val idx = (0 until n).filter { it < mask.size && mask[it] }.toIntArray()
    val m = idx.size
    val r = rideOver(m, DoubleArray(m) { metres[idx[it]] }, IntArray(m) { hr[idx[it]] },
        altM?.let { a -> DoubleArray(m) { a.getOrElse(idx[it]) { Double.NaN } } }) ?: return null
    // Back to session time: a start is its second, an end the second after its last.
    fun s(i: Int) = idx[i.coerceIn(0, m - 1)]
    fun e(i: Int) = idx[(i - 1).coerceIn(0, m - 1)] + 1
    return r.copy(splits = r.splits.map { it.copy(end = e(it.end)) }, bouts = r.bouts.map { it.copy(start = s(it.start), end = e(it.end)) })
}

private fun rideOver(n: Int, metres: DoubleArray, hr: IntArray, altM: DoubleArray?): RideSummary? {
    if (n < 60) return null
    // Speed over 10 seconds around each second, from the seconds that are known.
    val speed = DoubleArray(n) { Double.NaN }
    for (t in 0 until n) {
        var d = 0.0; var k = 0
        for (q in max(0, t - 5) until min(n, t + 5)) if (metres[q].isFinite()) { d += metres[q]; k++ }
        if (k >= 5) speed[t] = d / k
    }
    // Riding where fast enough; unknown seconds take the state around them.
    val riding = BooleanArray(n)
    var last = false
    for (t in 0 until n) { if (speed[t].isFinite()) last = speed[t] >= RIDE_MIN_SPEED; riding[t] = last }
    // Short stretches join their neighbours, a few passes so a flicker settles.
    repeat(3) {
        var i = 0
        while (i < n) {
            var j = i; while (j < n && riding[j] == riding[i]) j++
            if (j - i < RIDE_MIN_BOUT && i > 0 && j < n) for (q in i until j) riding[q] = !riding[q]
            i = j
        }
    }
    val covered = (0 until n).count { metres[it].isFinite() }
    if ((0 until n).count { riding[it] } < 60) return null

    fun avgHr(a: Int, b: Int) = (a until b).map { hr[it] }.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
    fun dist(a: Int, b: Int) = (a until b).sumOf { if (metres[it].isFinite()) metres[it] else 0.0 }
    val bouts = ArrayList<RideBout>()
    var i = 0
    while (i < n) {
        var j = i; while (j < n && riding[j] == riding[i]) j++
        bouts += RideBout(i, j, riding[i], dist(i, j), avgHr(i, j))
        i = j
    }
    val movingS = bouts.filter { it.riding }.sumOf { it.seconds }
    val distance = dist(0, n)
    val stops = bouts.filter { !it.riding && it.start > 0 && it.end < n }
    // Fastest stretches: the most metres in a window of whole known seconds.
    fun best(w: Int): Double? {
        if (n < w) return null
        var bestM = -1.0; var sum = 0.0; var unknown = 0
        for (t in 0 until n) {
            if (metres[t].isFinite()) sum += metres[t] else unknown++
            if (t >= w) { if (metres[t - w].isFinite()) sum -= metres[t - w] else unknown-- }
            if (t >= w - 1 && unknown == 0) bestM = max(bestM, sum)
        }
        return bestM.takeIf { it > 0 }?.let { it / w * 3.6 }
    }
    // Climbing from altitude smoothed over 30 s, counted in steps of 3 m so the sensor's wobble adds nothing.
    val (climb, descent) = altM?.let { a ->
        val smooth = DoubleArray(n) { t ->
            var s = 0.0; var k = 0
            for (q in max(0, t - 15) until min(n, t + 15)) if (q < a.size && a[q].isFinite()) { s += a[q]; k++ }
            if (k > 0) s / k else Double.NaN
        }
        var up = 0.0; var down = 0.0; var ref = Double.NaN
        for (v in smooth) {
            if (!v.isFinite()) continue
            if (!ref.isFinite()) { ref = v; continue }
            if (v - ref >= 3) { up += v - ref; ref = v } else if (ref - v >= 3) { down += ref - v; ref = v }
        }
        if (smooth.count { it.isFinite() } < 60) null to null else up to down
    } ?: (null to null)
    // Kilometre splits on riding time.
    val splits = ArrayList<RideSplit>()
    var m = 0.0; var moving = 0; var from = 0; var startMoving = 0
    for (t in 0 until n) {
        if (metres[t].isFinite()) m += metres[t]
        if (riding[t]) moving++
        if (m >= (splits.size + 1) * 1000.0) {
            val s = moving - startMoving
            if (s > 0) splits += RideSplit(splits.size + 1, t + 1, s, 1000.0 / s * 3.6, avgHr(from, t + 1))
            from = t + 1; startMoving = moving
        }
    }
    val ridingHrs = (0 until n).filter { riding[it] && hr[it] > 0 }.map { hr[it] }
    val ridingHr = ridingHrs.takeIf { it.isNotEmpty() }?.average()
    val ridingM = bouts.filter { it.riding }.sumOf { it.distanceM }
    return RideSummary(
        distance, movingS, n - movingS, covered,
        movingS.takeIf { it > 0 }?.let { ridingM / it * 3.6 }, best(30), best(300),
        stops.size, stops.maxOfOrNull { it.seconds } ?: 0,
        climb, descent,
        ridingHr?.roundToInt(), ridingHrs.maxOrNull(),
        ridingHr?.let { h -> ridingM / (movingS * h / 60) }.takeIf { movingS > 0 },
        splits, bouts,
    )
}

/** Riding (as [Gait.RUN]) or stopped ([Gait.STILL]) each second, for the lap finder's per-lap stretches. */
fun RideSummary.gaitPerSecond(n: Int): Array<Gait> = Array(n) { Gait.STILL }.also { k ->
    for (b in bouts) if (b.riding) for (t in max(0, b.start) until min(n, b.end)) k[t] = Gait.RUN
}
