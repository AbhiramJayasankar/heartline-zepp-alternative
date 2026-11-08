package dev.heartline.analytics

import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Which way the watch faced at [ts]: the mean gravity direction of a recording's accelerometer as a unit vector in the
 * watch's axes (z points out of the screen, so a watch lying face up on a table reads z = +1).
 */
data class WristPose(val ts: Long, val x: Double, val y: Double, val z: Double)

/** The mean gravity of [acc] (in g), or null when there are too few samples or it isn't near 1 g (the wrist was moving). */
fun gravityOf(acc: Array<FloatArray>, ts: Long): WristPose? {
    if (acc.size < 50) return null
    var sx = 0.0; var sy = 0.0; var sz = 0.0
    for (a in acc) { sx += a[0]; sy += a[1]; sz += a[2] }
    sx /= acc.size; sy /= acc.size; sz /= acc.size
    val g = sqrt(sx * sx + sy * sy + sz * sz)
    if (g !in 0.8..1.2) return null
    return WristPose(ts, sx / g, sy / g, sz / g)
}

/** [gravityOf], but only when the wrist was still: every axis within 0.1 g (one standard deviation) of its mean. */
fun stillGravityOf(acc: Array<FloatArray>, ts: Long): WristPose? {
    val p = gravityOf(acc, ts) ?: return null
    for (k in 0..2) {
        val mean = acc.sumOf { it[k].toDouble() } / acc.size
        if (sqrt(acc.sumOf { (it[k] - mean) * (it[k] - mean) } / acc.size) > 0.1) return null
    }
    return p
}

/** The watch's facing in words. Only the screen axis is named: which way 12 o'clock points depends on the wrist it's on. */
fun poseWords(z: Double): String = when {
    z >= 0.9 -> "Watch face up"
    z >= 0.5 -> "Tilted, face up"
    z >= 0.2 -> "On its side, a little face up"
    z > -0.2 -> "On its side"
    z > -0.5 -> "On its side, a little face down"
    z > -0.9 -> "Tilted, face down"
    else -> "Watch face down"
}

/** A minute counts as movement at this activity intensity (0-100); smaller twitches don't split a stretch. */
const val MOVE_INTENSITY = 10
/** A still stretch is at least this many minutes between two movements. */
const val STILL_MIN = 5
/** Two stretches are the same position when the watch faced within this many degrees. */
const val SAME_POSE_DEG = 30.0

/** A movement: consecutive movement minutes (gaps up to 2 minutes), [peak] its highest intensity. */
data class NightMove(val start: Long, val end: Long, val peak: Int)
/** Time lying still between two movements; [position] numbers the watch's facing, null when no recording measured it. */
data class StillStretch(val start: Long, val end: Long, val position: Int?, val x: Double? = null, val y: Double? = null, val z: Double? = null) {
    val minutes: Int get() = ((end - start) / 60).toInt()
}
/** One position: the watch's facing when first seen, in words, its total minutes and how many stretches it was. */
data class SleepPosition(val n: Int, val x: Double, val y: Double, val z: Double, val words: String, val minutes: Int, val times: Int)

data class SleepMovement(
    val start: Long, val end: Long,
    /** Each minute of the night and the watch's activity intensity (0-100). */
    val intensity: List<Pair<Long, Int>>,
    val moves: List<NightMove>,
    val stretches: List<StillStretch>,
    val positions: List<SleepPosition>,
) {
    val longestStillMin: Int get() = stretches.maxOfOrNull { it.minutes } ?: 0
    val restlessMin: Int get() = intensity.count { it.second >= MOVE_INTENSITY }
    /** Changes of position: neighbouring measured stretches in different positions. */
    val turns: Int get() = stretches.filter { it.position != null }.zipWithNext().count { (a, b) -> a.position != b.position }
    /** Whether any stretch had a recording to measure the watch's facing. */
    val measured: Boolean get() = positions.isNotEmpty()
}

private fun angle(a: DoubleArray, b: DoubleArray): Double =
    Math.toDegrees(acos((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1.0, 1.0)))

/**
 * The night from [start] to [end] split into still stretches and movements, with positions from the watch's facing.
 *
 * Movements are the watch's per-minute activity intensity at [MOVE_INTENSITY] or more, joined when under 2 minutes
 * apart. The time between two movements is a still stretch when it lasts [STILL_MIN] minutes or more. [poses] (from the
 * overnight HRV recordings, about one every 15 minutes) give a stretch its facing when one started inside it; stretches
 * facing within [SAME_POSE_DEG] of a position seen earlier get its number, so turning back onto the same side counts
 * as returning to that position. The wrist's facing usually follows how you lie, but not always.
 */
fun sleepMovement(minutes: List<MinuteSample>, start: Long, end: Long, poses: List<WristPose>): SleepMovement {
    val night = minutes.filter { it.ts in start until end }.sortedBy { it.ts }
    val moves = ArrayList<NightMove>()
    for (m in night) if (m.intensity >= MOVE_INTENSITY) {
        val last = moves.lastOrNull()
        if (last != null && m.ts - last.end <= 120) moves[moves.lastIndex] = last.copy(end = m.ts, peak = max(last.peak, m.intensity))
        else moves.add(NightMove(m.ts, m.ts, m.intensity))
    }
    val spans = ArrayList<Pair<Long, Long>>()
    var cur = start
    for (mv in moves) {
        if (mv.start - cur >= STILL_MIN * 60) spans.add(cur to mv.start)
        cur = mv.end + 60
    }
    if (end - cur >= STILL_MIN * 60) spans.add(cur to end)
    val centres = ArrayList<DoubleArray>()
    val stretches = spans.map { (a, b) ->
        val ps = poses.filter { it.ts in a until b }
        if (ps.isEmpty()) StillStretch(a, b, null) else {
            var v = doubleArrayOf(ps.sumOf { it.x }, ps.sumOf { it.y }, ps.sumOf { it.z })
            val g = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).takeIf { it > 0 } ?: 1.0
            v = doubleArrayOf(v[0] / g, v[1] / g, v[2] / g)
            val best = centres.indices.minByOrNull { angle(centres[it], v) }
            val n = if (best != null && angle(centres[best], v) < SAME_POSE_DEG) best else { centres.add(v); centres.lastIndex }
            StillStretch(a, b, n + 1, v[0], v[1], v[2])
        }
    }
    val positions = centres.mapIndexed { i, v ->
        val mine = stretches.filter { it.position == i + 1 }
        SleepPosition(i + 1, v[0], v[1], v[2], poseWords(v[2]), mine.sumOf { it.minutes }, mine.size)
    }
    return SleepMovement(start, end, night.map { it.ts to it.intensity }, moves, stretches, positions)
}
