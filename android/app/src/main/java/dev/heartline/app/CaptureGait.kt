package dev.heartline.app

import dev.heartline.analytics.GeoFix
import dev.heartline.analytics.Gait
import dev.heartline.analytics.GaitSummary
import dev.heartline.analytics.LapSummary
import dev.heartline.analytics.RideSummary
import dev.heartline.analytics.findLapLine
import dev.heartline.analytics.gaitSummary
import dev.heartline.analytics.lapSummary
import dev.heartline.analytics.perSecond
import dev.heartline.analytics.rideSummary
import dev.heartline.analytics.trackMetresPerSecond
import dev.heartline.analytics.withMetres
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Each second of a capture: its gait and cadence from the motion segments, and its heart rate (0 where none). */
private class CaptureSeconds(val kind: Array<Gait>, val cadence: DoubleArray, val hr: IntArray)

/** Each second's gait from the motion segments, before the ride is taken out. */
private fun segmentGait(a: CaptureAnalysisUi): Pair<Array<Gait>, DoubleArray> {
    val n = a.durationS
    val kind = Array(n) { Gait.NONE }
    val cadence = DoubleArray(n) { Double.NaN }
    for (s in a.segments) {
        val g = when (s.kind) { "running" -> Gait.RUN; "walking" -> Gait.WALK; "rest", "lifting" -> Gait.STILL; else -> Gait.NONE }
        for (t in s.start.toInt().coerceAtLeast(0) until minOf(n, ceil(s.end).toInt())) {
            kind[t] = g
            s.cadenceSpm?.let { cadence[t] = it.toDouble() }
        }
    }
    return kind to cadence
}

private fun captureHr(a: CaptureAnalysisUi): IntArray =
    perSecond(a.hr.map { it.t.toInt() to it.v }, a.durationS, 5).map { if (it.isFinite()) it.roundToInt() else 0 }.toIntArray()

/** The capture's seconds on foot: riding seconds ([captureRideMask]) have no gait, so runs, walks and laps leave them out. */
private fun captureSeconds(a: CaptureAnalysisUi): CaptureSeconds {
    val (kind, cadence) = segmentGait(a)
    captureRideMask(a)?.forEachIndexed { t, riding -> if (riding && t < kind.size) { kind[t] = Gait.NONE; cadence[t] = Double.NaN } }
    return CaptureSeconds(kind, cadence, captureHr(a))
}

/** A watch workout that is a ride: Cycling, Indoor cycling, Spinning and the like. */
fun isRideType(type: String): Boolean = type.lowercase().let { t -> "cycl" in t || "bik" in t || "spin" in t }

/** Faster than this (m/s on the phone's GPS, over 30 s) while the wrist isn't running is riding: 12.6 km/h. */
private const val RIDE_SPEED = 3.5
/** A stretch at riding speed must last this long (seconds) to count. */
private const val RIDE_MIN_S = 60

/**
 * The seconds of a capture spent riding: inside a watch workout that is a ride ([isRideType]), or a minute or more at
 * riding speed on the phone's GPS while the wrist wasn't running (a session recorded as Run / walk that took the bike
 * for a while). Null when nothing in it was a ride.
 */
fun captureRideMask(a: CaptureAnalysisUi): BooleanArray? {
    val n = a.durationS
    if (n < 60) return null
    val mask = BooleanArray(n)
    for (w in a.activities) if (isRideType(w.type)) for (t in w.start.toInt().coerceAtLeast(0) until minOf(n, ceil(w.end).toInt())) mask[t] = true
    a.phoneMetres()?.let { m ->
        val (kind, _) = segmentGait(a)
        // Speed over 30 seconds from the known seconds; fast stretches that last are riding.
        val fast = BooleanArray(n) { t ->
            var d = 0.0; var k = 0
            for (q in maxOf(0, t - 15) until minOf(n, t + 15)) if (m[q].isFinite()) { d += m[q]; k++ }
            k >= 15 && d / k >= RIDE_SPEED && kind[t] != Gait.RUN
        }
        var i = 0
        while (i < n) {
            var j = i; while (j < n && fast[j] == fast[i]) j++
            if (fast[i] && j - i >= RIDE_MIN_S) for (q in i until j) mask[q] = true
            i = j
        }
    }
    return mask.takeIf { m -> m.count { it } >= RIDE_MIN_S }
}

/** Seconds of a capture spent riding ([captureRideMask]); it needs no track, so the list can tell too. */
fun captureRideS(a: CaptureAnalysisUi): Int = captureRideMask(a)?.count { it } ?: 0

/** The ride in a capture ([captureRideMask]) from the phone's GPS: speed, stops, climbing and splits. Null without a ride or a track. */
fun captureRide(a: CaptureAnalysisUi): RideSummary? {
    val mask = captureRideMask(a) ?: return null
    val metres = a.phoneMetres() ?: return null
    val alt = DoubleArray(a.durationS) { Double.NaN }.also { arr -> a.route.forEach { p -> p.altM?.let { if (p.t in arr.indices) arr[p.t] = it } } }
    return rideSummary(a.durationS, metres, captureHr(a), alt, mask)
}

/** The phone's track of a capture, seconds from its start. */
internal fun CaptureAnalysisUi.track(): List<GeoFix> = route.map { GeoFix(it.t, it.lat, it.lon) }

/** Metres each second from the phone's GPS, or null without a track. */
internal fun CaptureAnalysisUi.phoneMetres(): DoubleArray? = metres ?: route.takeIf { it.size >= 60 }?.let { trackMetresPerSecond(track(), durationS) }

/**
 * The capture's running and walking, from its motion segments (the wrist's own step rate and how hard it moved) and its
 * five-second heart rate, with each bout's distance from the phone's GPS when it recorded. Null when there is too little
 * of either to be worth a section.
 */
fun captureGait(a: CaptureAnalysisUi): GaitSummary? {
    if (a.durationS < 60) return null
    val s = captureSeconds(a)
    // Motion bins are 5 s and often have a 5 s "other" between strides of the same gait: bridge up to 10 s.
    val g = gaitSummary(s.kind, s.hr, s.cadence, bridgeS = 10) ?: return null
    if (g.runS < 60 && g.walkS < 300) return null
    return a.phoneMetres()?.let { g.withMetres(it) } ?: g
}

/** Laps of a loop in the phone's track, with each lap's running, walking, pace and heart rate. Null without two laps. */
fun captureLaps(a: CaptureAnalysisUi, g: GaitSummary?): LapSummary? {
    // Laps are on foot: a ride round the same streets isn't a lap of the loop.
    val ride = captureRideMask(a)
    val metres = a.phoneMetres()?.let { m -> if (ride == null) m else DoubleArray(m.size) { if (ride.getOrElse(it) { false }) Double.NaN else m[it] } } ?: return null
    val line = findLapLine(a.track().filter { ride == null || !ride.getOrElse(it.t) { false } }) ?: return null
    val s = captureSeconds(a)
    return lapSummary(line, a.durationS, metres, s.kind, s.cadence, s.hr, g?.bouts)
}

/** Seconds of the capture its track covers (the whole capture without one). */
fun captureCoveredS(a: CaptureAnalysisUi): Int = a.phoneMetres()?.count { it.isFinite() }?.takeIf { it > 0 } ?: a.durationS

/** How far the phone's GPS says the capture went, or null without a track. */
fun capturePhoneDistanceM(a: CaptureAnalysisUi): Double? = a.phoneMetres()?.filter { it.isFinite() }?.sum()?.takeIf { it > 0 }

/** The capture label for a session on foot: the walk there, runs and walks, laps, the walk back. */
const val ON_FOOT_LABEL = "Run / walk"

/** A label that means a session on foot: [ON_FOOT_LABEL], or Run and Walk from before 0.9.1. */
fun isOnFootLabel(label: String): Boolean = listOf(ON_FOOT_LABEL, "Run", "Walk").any { it.equals(label, ignoreCase = true) }

/**
 * Whether to read a capture as a run or walk (and any ride in it, [rideS] seconds) rather than a gym session: labelled Run / walk with
 * five minutes or more on foot or riding, or most of it so with hardly any sets and no gym log.
 */
fun captureOnFoot(a: CaptureAnalysisUi, g: GaitSummary?, rideS: Int = captureRideS(a)): Boolean {
    val onFoot = (g?.let { it.runS + it.walkS } ?: 0) + rideS
    if (g == null && rideS == 0) return false
    return (isOnFootLabel(a.label) && onFoot >= 300) ||
        (onFoot >= 0.6 * a.durationS && a.sets.size <= 3 && a.logTotal == null)
}

/**
 * Riding speed (km/h, over 30 seconds) every 5 seconds through a ride's seconds, from per-second [metres]; the
 * seconds outside [mask] are left out, so the line breaks where the ride does.
 */
fun rideSpeedSeries(metres: DoubleArray, mask: BooleanArray?): List<SeriesPoint> {
    val n = metres.size
    return (0 until n step 5).mapNotNull { t ->
        if (mask != null && !mask.getOrElse(t) { false }) return@mapNotNull null
        var d = 0.0; var k = 0
        for (q in maxOf(0, t - 15) until minOf(n, t + 15)) if ((mask == null || mask[q]) && metres[q].isFinite()) { d += metres[q]; k++ }
        if (k >= 10) SeriesPoint(t.toDouble(), d / k * 3.6) else null
    }
}

/** [rideSpeedSeries] for a capture's ride. */
fun captureRideSpeed(a: CaptureAnalysisUi): List<SeriesPoint> =
    a.phoneMetres()?.let { m -> captureRideMask(a)?.let { rideSpeedSeries(m, it) } }.orEmpty()
