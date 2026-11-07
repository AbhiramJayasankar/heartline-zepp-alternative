package dev.heartline.analytics

import kotlin.math.*

fun analyzeSession(rec: Recording, name: String): SessionResult {
    val motion = motionSeries(rec)
    val beats = detectBeats(rec, motion)
    val iv = intervals(beats.times, beats.good)
    val keep = iv.cleanIndices
    val nn = DoubleArray(keep.size) { iv.rr[keep[it]] }
    val hr = if (nn.size >= 10) rounded(60 / mean(nn), 1).takeIf { it in 35.0..200.0 } else null
    val (resp, components) = breathingRate(beats, iv)
    // About 2000 points, but never thinner than 25 Hz: a coarser waveform puts beat marks beside their peaks.
    val previewStep = max(1, min(ceil(beats.filtered.size / 2000.0).toInt(), 4))
    val preview = DoubleArray((beats.filtered.size + previewStep - 1) / previewStep) { beats.filtered[it * previewStep] }
    val scale = if (preview.isEmpty()) 1.0 else percentile(DoubleArray(preview.size) { abs(preview[it]) }, 99.0).let {
        if (it == 0.0) 1.0 else it
    }
    val motionStep = max(1, ceil(motion.t.size / 500.0).toInt())
    return SessionResult(name, rec.start, rounded(rec.durationS, 1),
        rounded(if (beats.good.isEmpty()) 0.0 else beats.good.count { it }.toDouble() / beats.good.size, 3),
        rounded(nn.sum(), 1), hr, beats.times.size, hrvMetrics(iv), assessRhythm(iv, hr, beats.notes), resp, components,
        iv.t.indices.map { RrSample(rounded(iv.t[it], 3), rounded(iv.rr[it] * 1000, 1), iv.ectopic[it]) },
        preview.map { rounded(it / scale, 4) },
        if (preview.isNotEmpty()) rounded(100.0 / previewStep, 3) else null,
        beats.times.map { rounded(it, 3) }, beats.good.toList(),
        motion.t.indices.step(motionStep).map { MotionSample(rounded(motion.t[it], 2), rounded(motion.g[it], 4)) })
}
