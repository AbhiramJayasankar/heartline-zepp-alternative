package dev.heartline.analytics

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/** A GPS fix: seconds from the workout's start, and degrees. */
data class GeoFix(val t: Int, val lat: Double, val lon: Double)

fun metresBetween(a: GeoFix, b: GeoFix): Double {
    val k = PI / 180
    val x = (b.lon - a.lon) * k * cos((a.lat + b.lat) / 2 * k)
    val y = (b.lat - a.lat) * k
    return 6_371_000 * sqrt(x * x + y * y)
}

/**
 * How much a track wanders: its length fix to fix over its length between fixes at least 15 seconds apart. A fix that
 * jitters around the true path adds a few metres every second, which the 15-second line steps over; real turns are
 * kept by both. Clean tracks measure 1.04 to 1.10. Null for a track too short to judge (under 60 fixes or 300 metres).
 */
fun gpsWander(track: List<GeoFix>): Double? {
    if (track.size < 60) return null
    val raw = track.zipWithNext().sumOf { (a, b) -> metresBetween(a, b) }
    var coarse = 0.0
    var last = track.first()
    for (p in track) if (p.t - last.t >= 15) { coarse += metresBetween(last, p); last = p }
    coarse += metresBetween(last, track.last())
    if (coarse < 300) return null
    return raw / coarse
}

/**
 * Distance from the watch's per-second speed ([t] seconds, m/s), each reading held until the next for up to 10 s.
 * The GPS chip measures speed apart from position (from the signal's Doppler shift), so a position that wanders
 * lengthens the track but hardly moves the speed. Where speed readings are missing, it falls short.
 */
fun speedDistance(speed: List<Pair<Int, Double>>): Double =
    speed.sortedBy { it.first }.zipWithNext().sumOf { (a, b) -> if (a.second.isFinite() && a.second >= 0) a.second * (b.first - a.first).coerceAtMost(10) else 0.0 }

/** Your step length walking and running, in metres, and how many minutes of each it was learnt from. */
data class StepLengths(val walkM: Double? = null, val runM: Double? = null, val walkMin: Int = 0, val runMin: Int = 0)

private val WALK_STEP = 0.45..1.05
private val RUN_STEP = 0.6..1.7
/** Tracks this steady or better teach step lengths. */
private const val GPS_CLEAN = 1.12

/**
 * Step lengths from workouts whose GPS held: for walking and for running, the bouts' distance (the watch's per-second
 * pace) over their steps (seconds x cadence). Only bouts of 30 seconds or more, with a step length a person can have,
 * count; each needs 5 minutes in all. [workouts] pairs each workout's gait with its track.
 */
fun learnStepLengths(workouts: List<Pair<GaitSummary, List<GeoFix>>>): StepLengths {
    var walkD = 0.0; var walkSteps = 0.0; var walkS = 0
    var runD = 0.0; var runSteps = 0.0; var runS = 0
    for ((g, track) in workouts) {
        val wander = gpsWander(track) ?: continue
        if (wander > GPS_CLEAN) continue
        for (b in g.bouts) {
            val cadence = b.cadenceSpm ?: continue
            val d = b.distanceM ?: continue
            if (b.seconds < 30 || cadence <= 0) continue
            val steps = b.seconds * cadence / 60.0
            val len = d / steps
            if (b.running && len in RUN_STEP) { runD += d; runSteps += steps; runS += b.seconds }
            if (!b.running && len in WALK_STEP) { walkD += d; walkSteps += steps; walkS += b.seconds }
        }
    }
    return StepLengths(walkD.takeIf { walkS >= 300 }?.let { it / walkSteps }, runD.takeIf { runS >= 300 }?.let { it / runSteps },
        walkS / 60, runS / 60)
}

/**
 * The bouts with distances from their steps at [lengths]: each bout's seconds times its cadence, scaled so they add up
 * to [totalSteps] (the watch's count) when it is known. Null when a kind the workout has a minute or more of has no
 * learnt length.
 */
fun withStepDistances(g: GaitSummary, lengths: StepLengths, totalSteps: Int? = null): GaitSummary? {
    if (g.runS >= 60 && lengths.runM == null) return null
    if (g.walkS >= 60 && lengths.walkM == null) return null
    val raw = g.bouts.map { b -> b.cadenceSpm?.let { b.seconds * it / 60.0 } ?: 0.0 }
    val k = totalSteps?.takeIf { it > 0 }?.let { t -> raw.sum().takeIf { it > 0 }?.let { t / it } }?.takeIf { it in 0.5..2.0 } ?: 1.0
    val bouts = g.bouts.mapIndexed { i, b ->
        b.copy(distanceM = if (b.cadenceSpm == null) null else ((if (b.running) lengths.runM else lengths.walkM) ?: 0.0) * raw[i] * k)
    }
    fun total(running: Boolean) = bouts.filter { it.running == running }.mapNotNull { it.distanceM }.takeIf { it.isNotEmpty() }?.sum()
    return g.copy(bouts = bouts, runDistanceM = total(true), walkDistanceM = total(false))
}

/**
 * Above this, the watch's distance (its track's length) is more than the steps can cover at your step lengths: the GPS
 * position wandered. Measured against laps of a 745 m lake loop (whose laps the clean run of 14 Nov 2026 measured), the
 * steps came within 1 to 4%: 3.6 km against about 3.75 km on 19 Nov, 3.87 against about 3.85 on 21 Nov, where the
 * watch said 5.47 and 5.00 km and even its speed 4.18 and 4.44 km. Without step lengths, the watch's speed is the check.
 */
const val GPS_TOO_LONG = 1.2

/**
 * A workout whose GPS distance doesn't hold: the watch's distance is [ratio] times [distanceM], what it really
 * covered, from the steps ([gait] then has each bout's distance from its steps) or, with no step lengths, the speed.
 */
data class GpsCheck(val ratio: Double, val distanceM: Double, val fromSteps: Boolean, val wander: Double?, val gait: GaitSummary?)

/** Null when the watch's distance holds; see [GPS_TOO_LONG]. */
fun checkGpsDistance(watchM: Int?, speed: List<Pair<Int, Double>>, track: List<GeoFix>, gait: GaitSummary?,
                     lengths: StepLengths, totalSteps: Int?): GpsCheck? {
    if (watchM == null || watchM < 300) return null
    val bySteps = gait?.let { withStepDistances(it, lengths, totalSteps) }
    val stepsM = bySteps?.let { (it.runDistanceM ?: 0.0) + (it.walkDistanceM ?: 0.0) }?.takeIf { it >= 200 }
    if (stepsM != null) return (watchM / stepsM).takeIf { it >= GPS_TOO_LONG }?.let { GpsCheck(it, stepsM, true, gpsWander(track), bySteps) }
    if (speed.size < 60) return null
    val bySpeed = speedDistance(speed).takeIf { it >= 200 } ?: return null
    return (watchM / bySpeed).takeIf { it >= GPS_TOO_LONG }?.let { GpsCheck(it, bySpeed, false, gpsWander(track), null) }
}
