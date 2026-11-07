package dev.heartline.analytics

import kotlin.math.*

/*
 * Breathing from a wrist pulse. Each breath nudges beat timing (respiratory sinus arrhythmia), beat height and the
 * pulse baseline. On the GTR 2e a slow blood-pressure (Mayer) wave near 0.1 Hz, 6 a minute, is far stronger than
 * breathing in the baseline and beat height, and the old 6-30/min search locked onto it: every check read 6-9/min
 * while the person breathed 12-15 (counted by hand on 7 Nov 2026). So the slow wave is filtered out (two passes of a
 * 0.13-0.6 Hz band-pass), only real peaks between 9 and 30 a minute count, and the estimate comes from beat timing and
 * beat height together. The baseline estimate is still worked out and reported, but it does not vote.
 */
private const val FS = 4.0
private const val BAND_LO = 0.15
private const val BAND_HI = 0.5
private const val NFFT = 4096

/** Averaged (Welch, 64 s Hann windows, half overlap) power spectrum of [v] at times [t], slow waves filtered out. */
private fun breathSpectrum(t: DoubleArray, v: DoubleArray): DoubleArray? {
    if (v.size < 10 || t.last() - t[0] < 30) return null
    val g = grid(t[0], t.last(), 1 / FS)
    val sos = butterworth(FS, 0.13, 0.6)
    val x = zeroPhase(zeroPhase(detrend(interpolate(t, v, g)), sos), sos)
    val n = min(x.size, (64 * FS).toInt())
    val step = max(1, n / 2)
    val total = DoubleArray(NFFT / 2 + 1)
    var windows = 0
    var at = 0
    while (at + n <= x.size) {
        val seg = DoubleArray(n) { i -> x[at + i] * (0.5 - 0.5 * cos(2 * PI * i / (n - 1))) }
        val p = powerSpectrum(seg, NFFT)
        for (i in total.indices) total[i] += p.getOrElse(i) { 0.0 }
        windows++; at += step
    }
    if (windows == 0) return null
    for (i in total.indices) total[i] /= windows
    return total
}

private fun inBand(i: Int) = i * FS / NFFT in BAND_LO..BAND_HI

/** The strongest true peak (not a band edge) between 9 and 30 a minute: breaths per minute and how far it stands above the band's median. */
private fun bestPeak(spec: DoubleArray): Pair<Double, Double>? {
    var best = -1
    for (i in 1 until spec.lastIndex) if (inBand(i) && spec[i] > spec[i - 1] && spec[i] >= spec[i + 1] && (best == -1 || spec[i] > spec[best])) best = i
    if (best == -1) return null
    val band = spec.indices.filter(::inBand).map { spec[it] }.toDoubleArray()
    val med = median(band)
    return best * FS / NFFT * 60 to if (med > 0) spec[best] / med else 0.0
}

private fun normalised(spec: DoubleArray): DoubleArray {
    val top = spec.indices.filter(::inBand).maxOfOrNull { spec[it] } ?: 0.0
    return if (top <= 0) spec else DoubleArray(spec.size) { spec[it] / top }
}

internal fun breathingRate(beats: Beats, iv: Intervals): Pair<Double?, RespirationComponents> {
    val good = beats.good.indices.filter { beats.good[it] }
    val t = DoubleArray(good.size) { beats.times[good[it]] }
    val keep = iv.cleanIndices
    val baseline = breathSpectrum(t, DoubleArray(good.size) { beats.baseline[good[it]] })
    val height = breathSpectrum(t, DoubleArray(good.size) { beats.amplitude[good[it]] })
    val timing = breathSpectrum(DoubleArray(keep.size) { iv.t[keep[it]] }, DoubleArray(keep.size) { iv.rr[keep[it]] })
    fun rate(s: DoubleArray?) = s?.let(::bestPeak)?.first?.let { rounded(it, 1) }
    val components = RespirationComponents(rate(baseline), rate(height), rate(timing))
    val voters = listOfNotNull(height, timing)
    if (voters.isEmpty()) return null to components
    // Beat height and beat timing agreeing within 3 a minute is the strongest evidence: average them.
    val h = height?.let(::bestPeak); val r = timing?.let(::bestPeak)
    if (h != null && r != null && abs(h.first - r.first) <= 3 && max(h.second, r.second) >= 2.0) return rounded((h.first + r.first) / 2, 1) to components
    val fused = DoubleArray(NFFT / 2 + 1)
    for (s in voters) normalised(s).forEachIndexed { i, v -> fused[i] += v }
    val (bpm, clarity) = bestPeak(fused) ?: return null to components
    // A flat spectrum has no breathing rhythm worth reporting.
    if (clarity < 2.0) return null to components
    return rounded(bpm, 1) to components
}
