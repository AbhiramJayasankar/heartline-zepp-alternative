package dev.heartline.app

import dev.heartline.analytics.*
import dev.heartline.service.CaptureSession
import kotlin.math.roundToInt

internal fun CaptureResult.captureUi(id: String) = CaptureAnalysisUi(
    id, label, start.toLong(), durationS.roundToInt(), avgHr, maxHr, minHr,
    zones.map { ZoneTimeUi(it.zone, zoneName(it.zone), it.lowBpm, it.highBpm, it.seconds) }, load, calories, steps,
    hr.map { SeriesPoint(it.t, it.v) }, motion.map { SeriesPoint(it.t, it.v) },
    segments.map { SegmentUi(it.start, it.end, it.kind, it.avgHr, it.cadenceSpm) },
    sets.map { SetUi(it.start, it.end, it.reps, it.avgHr, it.peakHr, it.tempoS) },
    rests.map { RestUi(it.start, it.end, it.startHr, it.endHr, it.drop60) }, hrSource, notes)

internal fun CaptureSession.captureItem(result: CaptureAnalysisUi?, now: Long) = CaptureItemUi(
    id, label, start, elapsed(now), result?.avgHr, result?.sets?.size, result?.load)

internal fun CaptureSession.captureProgress(now: Long, hr: Int?) = CaptureRunUi(label, start, plannedS, hr, elapsed(now), samples, error)

/** Same zone names as synced workouts, so both pages read alike. */
internal fun zoneName(zone: Int): String = when (zone) { 1 -> "Warm-up"; 2 -> "Fat burn"; 3 -> "Aerobic"; 4 -> "Anaerobic"; else -> "Max" }
