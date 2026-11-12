package dev.heartline.app

import dev.heartline.analytics.*
import dev.heartline.service.CaptureSession
import kotlin.math.roundToInt

internal fun CaptureResult.captureUi(id: String) = CaptureAnalysisUi(
    id, label, start.toLong(), durationS.roundToInt(), avgHr, maxHr, minHr,
    zones.map { ZoneTimeUi(it.zone, zoneName(it.zone), it.lowBpm, it.highBpm, it.seconds) }, load, calories, steps,
    hr.map { SeriesPoint(it.t, it.v) }, motion.map { SeriesPoint(it.t, it.v) },
    segments.map { SegmentUi(it.start, it.end, it.kind, it.avgHr, it.cadenceSpm) },
    sets.map { it.ui() },
    rests.map { RestUi(it.start, it.end, it.startHr, it.endHr, it.drop60) }, hrSource, notes,
    activities.map { CaptureActivityUi(it.start, it.end, it.type, it.activeS, it.distanceM, it.steps, it.calories, it.avgHr, it.maxHr) },
    recovery.map { RecoveryPointUi(it.t, it.delta, it.n) }, floor.map { SeriesPoint(it.t, it.v) }, workS, restS,
    cardio.map { CardioUi(it.start, it.end, it.kind, it.name, it.avgHr, it.steadyHr, it.peakHr, it.riseTauS,
        it.driftPerMin, it.drop60, it.drop120, it.recoveryTauS, it.cadenceSpm, it.distanceM, it.speedKmh) },
    pulse?.let { p -> PulseUi(p.status, p.usableS, p.windows.map { PulseWindowUi(it.start, it.end, it.beats,
        it.hrPpg, it.hrWatch, it.rmssdMs, it.nrmssd, it.status) }, p.nrmssd, p.entropy, p.ectopic,
        p.pairs, p.excerpt, p.excerptFs, p.excerptStart, p.excerptBeats, p.notes) },
    logMatched, logTotal, unlogged.map { it.ui() }, hrSeconds, loadMaxHr, loadSex)

/**
 * The capture with its load scored for today's resting heart rate [rest]: that moves a beat or two as days pass, and
 * scoring it again from the stored seconds is instant, where analysing the recording again takes seconds.
 */
internal fun CaptureAnalysisUi.withRest(rest: Int?): CaptureAnalysisUi =
    if (hrSeconds.isEmpty() || loadMaxHr == null) this
    else copy(load = dev.heartline.analytics.captureLoad(hrSeconds, rest?.toDouble(), loadMaxHr, loadSex))

private fun CaptureSet.ui() = SetUi(start, end, reps, avgHr, peakHr, tempoS,
    repDetail.map { RepUi(it.t, it.durationS, it.slowS, it.fastS, it.arcDeg) }, group, activity,
    slowdownS, partialLast, startHr, hrRise, exercise, setLabel, loadKg, loggedReps, warmUp, source)

internal fun CaptureSession.captureItem(result: CaptureAnalysisUi?, now: Long) = CaptureItemUi(
    id, label, start, elapsed(now), result?.avgHr, result?.sets?.size, result?.load)

internal fun CaptureSession.captureProgress(now: Long, hr: Int?) = CaptureRunUi(label, start, plannedS, hr, elapsed(now), samples, error)

/** Same zone names as synced workouts, so both pages read alike. */
internal fun zoneName(zone: Int): String = when (zone) { 1 -> "Warm-up"; 2 -> "Fat burn"; 3 -> "Aerobic"; 4 -> "Anaerobic"; else -> "Max" }
