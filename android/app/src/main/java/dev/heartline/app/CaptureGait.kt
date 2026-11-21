package dev.heartline.app

import dev.heartline.analytics.GeoFix
import dev.heartline.analytics.Gait
import dev.heartline.analytics.GaitSummary
import dev.heartline.analytics.LapSummary
import dev.heartline.analytics.findLapLine
import dev.heartline.analytics.gaitSummary
import dev.heartline.analytics.lapSummary
import dev.heartline.analytics.perSecond
import dev.heartline.analytics.trackMetresPerSecond
import dev.heartline.analytics.withMetres
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Each second of a capture: its gait and cadence from the motion segments, and its heart rate (0 where none). */
private class CaptureSeconds(val kind: Array<Gait>, val cadence: DoubleArray, val hr: IntArray)

private fun captureSeconds(a: CaptureAnalysisUi): CaptureSeconds {
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
    val hr = perSecond(a.hr.map { it.t.toInt() to it.v }, n, 5).map { if (it.isFinite()) it.roundToInt() else 0 }.toIntArray()
    return CaptureSeconds(kind, cadence, hr)
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
    val metres = a.phoneMetres() ?: return null
    val line = findLapLine(a.track()) ?: return null
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
 * Whether to read a capture as a run or walk rather than a gym session: labelled Run or Walk with five minutes or more
 * on foot, or most of it on foot with hardly any sets and no gym log.
 */
fun captureOnFoot(a: CaptureAnalysisUi, g: GaitSummary?): Boolean {
    if (g == null) return false
    val onFoot = g.runS + g.walkS
    return (isOnFootLabel(a.label) && onFoot >= 300) ||
        (onFoot >= 0.6 * a.durationS && a.sets.size <= 3 && a.logTotal == null)
}
