package dev.heartline.analytics

import kotlin.math.*

/** Seconds with sufficient raw coverage, low magnitude SD and bias-corrected gyro energy.
 * Erode by three consecutive seconds, then bridge isolated one-second holes as in the session reference. */
internal fun captureStillWindows(rec: Recording, duration: Int): List<Pair<Int, Int>> {
    if (rec.acc.isEmpty() || rec.gyro.isEmpty() || duration == 0) return emptyList()
    val sum = DoubleArray(duration); val sum2 = DoubleArray(duration); val count = IntArray(duration)
    for (i in rec.acc.indices) {
        val s = floor((if (rec.watchClock) rec.accT else rec.accArrivalT)[i]).toInt()
        if (s !in sum.indices || rec.acc[i].any { !it.isFinite() }) continue
        val v = sqrt(rec.acc[i].sumOf { it.toDouble().pow(2) })
        sum[s] += v; sum2[s] += v * v; count[s]++
    }
    val bias = DoubleArray(3) { k -> median(rec.gyro.map { it[k].toDouble() }.filter { it.isFinite() }.toDoubleArray()) }
    val gs = DoubleArray(duration); val gc = IntArray(duration)
    for (i in rec.gyro.indices) {
        val s = floor(rec.gyroT[i]).toInt()
        if (s !in gs.indices || rec.gyro[i].any { !it.isFinite() }) continue
        gs[s] += sqrt((0..2).sumOf { (rec.gyro[i][it] - bias[it]).pow(2) }); gc[s]++
    }
    for (i in gs.indices) if (gc[i] > 0) gs[i] /= gc[i]
    val threshold = .6 * median(gs.filterIndexed { i, _ -> gc[i] >= 15 }.toDoubleArray().takeIf { it.isNotEmpty() } ?: return emptyList())
    val still = BooleanArray(duration) { s -> count[s] >= 15 && gc[s] >= 15 &&
        sqrt(max(0.0, sum2[s] / count[s] - (sum[s] / count[s]).pow(2))) < .03 && gs[s] < threshold }
    val use = BooleanArray(duration) { it > 0 && it < duration - 1 && still[it - 1] && still[it] && still[it + 1] }
    val out = ArrayList<Pair<Int, Int>>()
    var a = 0
    while (a < duration) {
        if (!use[a]) { a++; continue }
        var b = a
        while (b < duration && (use[b] || (b + 2 < duration && (b..b + 2).count { use[it] } >= 2))) b++
        if (b - a >= 20) out += a to b
        a = b
    }
    return out
}

/** Fit sample index to arrival time independently inside each PPG run. This removes packet jitter;
 * a >1 s gap remains a hard barrier. Sparse fixtures and reconnects use the same clock. */
internal fun capturePpgClock(times: DoubleArray): DoubleArray {
    val out = DoubleArray(times.size)
    var a = 0
    for (b in 1..times.size) {
        if (b < times.size && times[b] - times[b - 1] <= 1) continue
        val n = b - a
        val mid = (n - 1) / 2.0
        var sy = 0.0; var sxy = 0.0
        for (j in 0 until n) { val y = times[a + j] - times[a]; sy += y; sxy += (j - mid) * y }
        val step = if (n > 1) sxy / (n.toDouble() * (n.toDouble() * n - 1) / 12) else .04
        val intercept = times[a] + sy / n - step * mid
        for (j in 0 until n) out[a + j] = intercept + j * step
        a = b
    }
    return out
}

internal fun capturePulse(rec: Recording, hr: IntArray): CapturePulse? {
    if ((if (rec.watchClock) rec.durationS < 20 else rec.ppg.size < 500) || rec.gyro.isEmpty()) return null
    val windows = captureStillWindows(rec, hr.size).sortedByDescending { it.second - it.first }.take(40)
    val clock = if (rec.watchClock) rec.ppgT else capturePpgClock(rec.ppgArrivalT)
    val output = ArrayList<CapturePulseWindow>()
    val pooled = ArrayList<Intervals>()
    val pairs = ArrayList<Pair<Double, Double>>()
    var bestQuality = -1.0; var excerpt = emptyList<Double>(); var excerptBeats = emptyList<Double>(); var excerptStart = 0.0
    var usable = 0
    for ((a, originalEnd) in windows) {
        val b = min(originalEnd, a + 120)
        val lo = clock.lowerBound(a - 2.0); val hi = clock.lowerBound(b + 2.0)
        if ((if (rec.watchClock) hi - lo < 2 || clock[hi - 1] - clock[lo] < 19 else hi - lo < 500) ||
            (lo + 1 until hi).any { clock[it] - clock[it - 1] > .5 }) continue
        val raw = rec.ppg.copyOfRange(lo, hi)
        if (raw.count { !it.isFinite() || it < 100 || it > 65000 } > raw.size * .02) continue
        // Small saturated glitches are interpolated; larger saturation rejects the window.
        val good = raw.indices.filter { raw[it].isFinite() && raw[it] in 100.0..65000.0 }
        val start = clock[lo]
        val time = DoubleArray(hi - lo) { clock[lo + it] - start }
        val signal = interpolate(good.map { time[it] }.toDoubleArray(), good.map { raw[it] }.toDoubleArray(), time)
        val slice = Recording(rec.start + start, time, signal, doubleArrayOf(), emptyArray(), watchClock = rec.watchClock)
        val watch = (a until min(b, hr.size)).map { hr[it] }.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average()
        val beats = detectBeats(slice, Motion(doubleArrayOf(), doubleArrayOf()), watch)
        val inside = beats.times.indices.filter { beats.times[it] + start >= a && beats.times[it] + start < b }
        if (inside.size < 4) continue
        val bt = inside.map { beats.times[it] }.toDoubleArray()
        val bg = inside.map { beats.good[it] }.toBooleanArray()
        val iv = intervals(bt, bg)
        val clean = iv.cleanIndices
        if (clean.size < 10) continue
        val nn = clean.map { iv.rr[it] }.toDoubleArray()
        val ppgHr = 60 / mean(nn)
        val rmssd = sqrt((1 until nn.size).sumOf { (nn[it] - nn[it - 1]).pow(2) } / (nn.size - 1))
        val rhythm = assessRhythm(iv, ppgHr)
        val reliable = watch != null && abs(ppgHr - watch) <= 10 && clean.size >= 30
        output += CapturePulseWindow(a.toDouble(), b.toDouble(), bt.size, ppgHr.roundToInt(), watch?.roundToInt(),
            rmssd * 1000, rmssd / mean(nn), if (reliable) rhythm.status else "unreliable")
        if (!reliable) continue
        usable += b - a
        pooled += iv
        val cleanMask = BooleanArray(iv.rr.size) { !iv.ectopic[it] && !iv.artifact[it] }
        for (i in 0 until iv.rr.lastIndex) if (cleanMask[i] && cleanMask[i + 1] &&
            abs(iv.t[i + 1] - iv.t[i] - iv.rr[i + 1]) < .02 && pairs.size < 600)
            pairs += iv.rr[i] * 1000 to iv.rr[i + 1] * 1000
        val quality = clean.size.toDouble() / max(1, bt.size - 1)
        if (quality > bestQuality) {
            bestQuality = quality
            excerptStart = a + min(8.0, (b - a - 12).coerceAtLeast(0).toDouble())
            val index = ((excerptStart - start) * 100).roundToInt().coerceAtLeast(0)
            excerpt = (index until min(beats.filtered.size, index + 1200) step 4).map { beats.filtered[it] }
            excerptBeats = bt.filter { it + start >= excerptStart && it + start < excerptStart + 12 }.map { it + start - excerptStart }
        }
    }
    // Pool clean timing only. Candidate counts come from flagEctopic inside the original
    // windows; never search for a compensatory pause across unrelated window joins.
    val rr = pooled.flatMap { iv -> iv.cleanIndices.map { iv.rr[it] } }.toDoubleArray()
    val combined = Intervals(DoubleArray(rr.size) { it.toDouble() }, rr, BooleanArray(rr.size), BooleanArray(rr.size))
    val ectopic = pooled.sumOf { it.ectopic.count { e -> e } / 2 }
    val overall = assessRhythm(combined, null, ectopicBeats = ectopic)
    return CapturePulse(overall.status, usable, output.sortedBy { it.start }, overall.irregularity, overall.features?.shannonEntropy,
        overall.ectopicBeats, pairs, excerpt, 25.0, excerptStart, excerptBeats,
        overall.notes + "Only still PPG windows with at least 30 clean intervals and agreement with live watch HR within 10 bpm contribute; this is an optical pulse check during recovery.")
}

internal fun DoubleArray.lowerBound(value: Double): Int {
    var a = 0; var b = size
    while (a < b) { val m = (a + b) ushr 1; if (this[m] < value) a = m + 1 else b = m }
    return a
}
