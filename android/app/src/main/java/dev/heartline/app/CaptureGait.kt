package dev.heartline.app

import dev.heartline.analytics.Gait
import dev.heartline.analytics.GaitSummary
import dev.heartline.analytics.gaitSummary
import dev.heartline.analytics.perSecond
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The capture's running and walking, from its motion segments (the wrist's own step rate and how hard it moved) and its
 * five-second heart rate. Null when there is too little of either to be worth a section.
 */
fun captureGait(a: CaptureAnalysisUi): GaitSummary? {
    val n = a.durationS
    if (n < 60) return null
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
    // Motion bins are 5 s and often have a 5 s "other" between strides of the same gait: bridge up to 10 s.
    val g = gaitSummary(kind, hr, cadence, bridgeS = 10) ?: return null
    return g.takeIf { it.runS >= 60 || it.walkS >= 300 }
}

/**
 * Whether to read a capture as a run or walk rather than a gym session: labelled Run with five minutes or more on foot,
 * or most of it on foot with hardly any sets and no gym log.
 */
fun captureOnFoot(a: CaptureAnalysisUi, g: GaitSummary?): Boolean {
    if (g == null) return false
    val onFoot = g.runS + g.walkS
    return (a.label.equals("Run", ignoreCase = true) && onFoot >= 300) ||
        (onFoot >= 0.6 * a.durationS && a.sets.size <= 3 && a.logTotal == null)
}
