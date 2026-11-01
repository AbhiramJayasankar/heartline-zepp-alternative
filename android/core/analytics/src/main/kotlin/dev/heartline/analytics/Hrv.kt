package dev.heartline.analytics

import kotlin.math.*

internal data class Intervals(val t: DoubleArray, val rr: DoubleArray, val ectopic: BooleanArray, val artifact: BooleanArray) {
    val cleanIndices: List<Int> get() = rr.indices.filter { !ectopic[it] && !artifact[it] }
}

internal fun flagEctopic(rr: DoubleArray, window: Int = 5): Pair<BooleanArray, BooleanArray> {
    val ectopic = BooleanArray(rr.size)
    val artifact = BooleanArray(rr.size)
    if (rr.size < 5) return ectopic to artifact
    val med = DoubleArray(rr.size) { median(rr.copyOfRange(max(0, it - window), min(rr.size, it + window + 1))) }
    for (i in 0 until rr.lastIndex) if (rr[i] < 0.8 * med[i] && rr[i + 1] > 1.1 * med[i]) {
        ectopic[i] = true; ectopic[i + 1] = true
    }
    for (i in rr.indices) artifact[i] = abs(rr[i] - med[i]) / med[i] > 0.30 && !ectopic[i]
    return ectopic to artifact
}

internal fun intervals(times: DoubleArray, good: BooleanArray): Intervals {
    val t = ArrayList<Double>()
    val rr = ArrayList<Double>()
    for (i in 1 until times.size) if (good[i] && good[i - 1]) {
        val d = times[i] - times[i - 1]
        if (d in 0.3..2.0) { t.add(times[i]); rr.add(d) }
    }
    val r = rr.toDoubleArray()
    val (ectopic, artifact) = flagEctopic(r)
    return Intervals(t.toDoubleArray(), r, ectopic, artifact)
}

// scipy.signal.lombscargle, unnormalized, floating_mean=False, precenter=False.
internal fun lombScargle(t: DoubleArray, y: DoubleArray, f: DoubleArray): DoubleArray = DoubleArray(f.size) { k ->
    val w = 2 * PI * f[k]
    var sin2 = 0.0
    var cos2 = 0.0
    for (v in t) { sin2 += sin(2 * w * v); cos2 += cos(2 * w * v) }
    val tau = atan2(sin2, cos2) / (2 * w)
    var yc = 0.0
    var ys = 0.0
    var cc = 0.0
    var ss = 0.0
    for (i in t.indices) {
        val c = cos(w * (t[i] - tau))
        val s = sin(w * (t[i] - tau))
        yc += y[i] * c; ys += y[i] * s; cc += c * c; ss += s * s
    }
    0.5 * (yc * yc / cc + ys * ys / ss)
}

internal fun frequencyDomain(t: DoubleArray, nn: DoubleArray): Triple<Double?, Double?, Double?> {
    if (nn.size < 20 || t.last() - t[0] < 60) return Triple(null, null, null)
    val ms = DoubleArray(nn.size) { nn[it] * 1000 }
    val m = mean(ms)
    val y = DoubleArray(ms.size) { ms[it] - m }
    // Match numpy.linspace's multiply-then-add grid (and the half-open frequency bands).
    val step = (0.5 - 0.01) / 490
    val f = DoubleArray(491) { 0.01 + it * step }
    f[f.lastIndex] = 0.5
    val psd = lombScargle(t, y, f)
    for (i in psd.indices) psd[i] *= 2.0 / y.size
    fun integrate(lo: Double, hi: Double): Double {
        var total = 0.0
        var prev = -1
        for (i in f.indices) if (f[i] >= lo && f[i] < hi) {
            if (prev >= 0) total += (psd[prev] + psd[i]) * (f[i] - f[prev]) / 2
            prev = i
        }
        return total
    }
    val total = integrate(0.0, 1.0)
    if (total > 0) {
        val scale = variance(y) / total
        for (i in psd.indices) psd[i] *= scale
    }
    val hf = integrate(0.15, 0.40)
    if (t.last() - t[0] < 120) return Triple(null, rounded(hf, 1), null)
    val lf = integrate(0.04, 0.15)
    return Triple(rounded(lf, 1), rounded(hf, 1), if (hf > 0) rounded(lf / hf, 2) else null)
}

internal fun stressIndex(nn: DoubleArray): Double? {
    if (nn.size < 30) return null
    val counts = sortedMapOf<Int, Int>()
    for (v in nn) {
        val k = floor(v / 0.05 + 1e-9).toInt()
        counts[k] = (counts[k] ?: 0) + 1
    }
    val mode = counts.maxBy { it.value }
    val mo = (mode.key + 0.5) * 0.05
    val range = nn.max() - nn.min()
    if (range <= 0) return null
    return rounded((mode.value.toDouble() / nn.size * 100) / (2 * mo * range), 1)
}

internal fun hrvMetrics(iv: Intervals): HrvResult {
    val keep = iv.cleanIndices
    val nn = DoubleArray(keep.size) { iv.rr[keep[it]] }
    val t = DoubleArray(keep.size) { iv.t[keep[it]] }
    val (lf, hf, ratio) = frequencyDomain(t, nn)
    if (nn.size < 10) return HrvResult(null, null, null, null, null, null, lf, hf, ratio, stressIndex(nn))
    val ms = DoubleArray(nn.size) { nn[it] * 1000 }
    val diff = DoubleArray(ms.size - 1) { ms[it + 1] - ms[it] }
    val sdnn = sqrt(variance(ms, 1))
    val sd1 = sqrt(0.5) * sqrt(variance(diff, 1))
    val sd2 = 2 * sdnn * sdnn - sd1 * sd1
    return HrvResult(rounded(mean(ms), 1), rounded(sdnn, 1),
        rounded(sqrt(diff.sumOf { it * it } / diff.size), 1),
        rounded(diff.count { abs(it) > 50 }.toDouble() / diff.size * 100, 1),
        rounded(sd1, 1), if (sd2 > 0) rounded(sqrt(sd2), 1) else null,
        lf, hf, ratio, stressIndex(nn))
}
