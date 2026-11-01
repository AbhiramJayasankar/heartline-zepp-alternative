package dev.heartline.analytics

import kotlin.math.*

private fun peakRate(t: DoubleArray, v: DoubleArray): Pair<Double?, Double> {
    if (v.size < 10 || t.last() - t[0] < 30) return null to 0.0
    val grid = grid(t[0], t.last(), 0.25)
    val x = zeroPhase(detrend(interpolate(t, v, grid)), butterworth(4.0, 0.1, 0.5))
    var nfft = 1024
    while (nfft < x.size * 4) nfft *= 2
    for (i in x.indices) x[i] *= 0.5 - 0.5 * cos(2 * PI * i / (x.size - 1))
    val spec = powerSpectrum(x, nfft)
    val df = 4.0 / nfft
    var sum = 0.0
    var peak = -1
    for (i in spec.indices) if (i * df in 0.1..0.5) {
        sum += spec[i]
        if (peak == -1 || spec[i] > spec[peak]) peak = i
    }
    if (peak == -1 || sum <= 0) return null to 0.0
    var near = 0.0
    for (i in spec.indices) if (i * df in 0.1..0.5 && abs((i - peak) * df) <= 0.03) near += spec[i]
    return peak * df * 60 to near / sum
}

internal fun breathingRate(beats: Beats, iv: Intervals): Pair<Double?, RespirationComponents> {
    val good = beats.good.indices.filter { beats.good[it] }
    val t = DoubleArray(good.size) { beats.times[good[it]] }
    val keep = iv.cleanIndices
    val estimates = listOf(
        peakRate(t, DoubleArray(good.size) { beats.baseline[good[it]] }),
        peakRate(t, DoubleArray(good.size) { beats.amplitude[good[it]] }),
        peakRate(DoubleArray(keep.size) { iv.t[keep[it]] }, DoubleArray(keep.size) { iv.rr[keep[it]] }),
    )
    val components = RespirationComponents(estimates[0].first?.let { rounded(it, 1) },
        estimates[1].first?.let { rounded(it, 1) }, estimates[2].first?.let { rounded(it, 1) })
    var rates = estimates.filter { it.first != null && it.second > 0 }.map { it.first!! to it.second }
    if (rates.size < 2) return null to components
    if (rates.maxOf { it.first } - rates.minOf { it.first } > 4) {
        var best: List<Pair<Double, Double>>? = null
        var weight = -1.0
        for (i in rates.indices) for (j in i + 1 until rates.size) {
            val w = rates[i].second + rates[j].second
            if (abs(rates[i].first - rates[j].first) <= 4 && w > weight) {
                best = listOf(rates[i], rates[j]); weight = w
            }
        }
        rates = best ?: return null to components
    }
    return rounded(rates.sumOf { it.first * it.second } / rates.sumOf { it.second }, 1) to components
}
