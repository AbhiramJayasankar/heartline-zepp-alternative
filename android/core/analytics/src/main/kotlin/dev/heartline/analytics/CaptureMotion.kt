package dev.heartline.analytics

import kotlin.math.*

internal data class CaptureMotionResult(val points: List<CapturePoint>, val segments: List<CaptureSegment>, val repTimes: List<Double>, val missingS: Double)
private data class Period(val hz: Double, val score: Double, val fraction: Double)
private data class MotionBin(val start: Double, val end: Double, var kind: String, val cadence: Int?, val repAxis: Int)
private const val FS = 25.0

internal fun captureMotion(rec: Recording, duration: Double): CaptureMotionResult {
    val n = ceil(duration * FS).toInt()
    val acc = resampleVectors(rec.accArrivalT, rec.acc, n)
    val gyro = resampleVectors(rec.gyroT, rec.gyro, n)
    val norm = magnitude(acc).map { it - 1 }.toDoubleArray()
    val hp = filterRuns(norm, .1, 8.0)
    val accAxes = acc.map { filterRuns(it, .1, 8.0) }
    val gyroAxes = (gyro + listOf(magnitude(gyro))).map { filterRuns(it, .1, 8.0) }
    val bins = ArrayList<MotionBin>()
    val points = ArrayList<CapturePoint>()
    for (i in 0 until ceil(duration / 5).toInt()) {
        val start = i * 5.0; val end = min(duration, start + 5)
        val a = (start * FS).toInt(); val b = min(n, ceil(end * FS).toInt())
        val central = hp.copyOfRange(a, b)
        if (central.isEmpty() || central.count { it.isFinite() } < central.size * .8) {
            bins += MotionBin(start, end, "other", null, 0); continue
        }
        val rms = rms(central)
        points += CapturePoint(start, rms)
        val axisRms = accAxes.maxOf { rms(it.copyOfRange(a, b)) }
        if (rms < .03 && axisRms < .065) { bins += MotionBin(start, end, "rest", null, 0); continue }
        val wa = max(0, ((start - 2.5) * FS).toInt()); val wb = min(n, ((end + 2.5) * FS).toInt())
        val gait = period(hp.copyOfRange(wa, wb), 1.4, 3.5, bandScore = true)
        if (rms >= .045 && gait != null && gait.score >= .25 && gait.fraction >= .15) {
            val cadence = (gait.hz * 60).roundToInt()
            bins += MotionBin(start, end, if (cadence > 140 || rms > .65) "running" else "walking", cadence, 0)
            continue
        }
        val la = max(0, ((start - 5.5) * FS).toInt()); val lb = min(n, ((end + 5.5) * FS).toInt())
        val candidates = accAxes.mapIndexedNotNull { axis, values ->
            period(values.copyOfRange(la, lb), .15, 1.2)?.let { axis to it }
        }
        val lift = candidates.maxByOrNull { it.second.score * it.second.fraction }
        val gp = gyroAxes.mapNotNull { period(it.copyOfRange(la, lb), .15, 2.4) }
        val corroborated = lift != null && (rec.gyro.isEmpty() || gp.any {
            it.score > .55 && (abs(it.hz - lift.second.hz) < .12 || abs(it.hz - 2 * lift.second.hz) < .15)
        })
        bins += MotionBin(start, end,
            if (lift != null && lift.second.score > .65 && lift.second.fraction > .65 && axisRms > .045 && corroborated) "lifting" else "other",
            null, lift?.first ?: 0)
    }
    // Brief wrist turns can obscure footfalls. Bridge at most ten seconds between consistent gait estimates.
    for (i in 1 until bins.lastIndex) {
        if (bins[i].kind != "other" || bins[i - 1].kind !in setOf("walking", "running")) continue
        val end = (i..min(bins.lastIndex, i + 2)).firstOrNull { bins[it].kind != "other" } ?: continue
        if (bins[i - 1].kind == bins[end].kind && abs((bins[i - 1].cadence ?: 0) - (bins[end].cadence ?: 0)) <= 30 &&
            (i until end).all { k -> points.any { it.t == bins[k].start } })
            for (k in i until end) bins[k].kind = bins[i - 1].kind
    }
    fun runs(): List<List<MotionBin>> {
        val out = ArrayList<MutableList<MotionBin>>()
        bins.forEach { if (out.lastOrNull()?.last()?.kind == it.kind) out.last().add(it) else out.add(mutableListOf(it)) }
        return out
    }
    for (run in runs()) {
        val span = run.last().end - run.first().start
        if ((run.first().kind in setOf("walking", "running") && span < 20) || (run.first().kind == "lifting" && span !in 8.0..120.0))
            run.forEach { it.kind = "other" }
    }
    val repTimes = ArrayList<Double>()
    val segments = runs().map { run ->
        val start = run.first().start; val end = run.last().end; var kind = run.first().kind
        if (kind == "lifting") {
            val axis = run.groupingBy { it.repAxis }.eachCount().maxBy { it.value }.key
            val a = (start * FS).toInt(); val b = min(n, (end * FS).toInt())
            val signal = accAxes[axis].copyOfRange(a, b)
            if (signal.all { it.isFinite() } && signal.size > 25) {
                val filtered = zeroPhase(signal, butterworth(FS, .15, 1.2))
                val f = period(signal, .15, 1.2)?.hz ?: .5
                val peaks = findPeaks(filtered, max(1, (FS / f * .65).toInt()), max(.025, rms(filtered) * .6))
                if (peaks.size >= 3) repTimes += peaks.map { start + it / FS } else kind = "other"
            } else kind = "other"
        }
        val cadence = run.mapNotNull { it.cadence }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
        CaptureSegment(start, end, kind, null, if (kind in setOf("walking", "running")) cadence else null)
    }
    return CaptureMotionResult(points, segments, repTimes, (n - hp.count { it.isFinite() }) / FS)
}

private fun magnitude(axes: List<DoubleArray>) = DoubleArray(axes.first().size) { i -> sqrt(axes.sumOf { it[i] * it[i] }) }
private fun rms(x: DoubleArray): Double = x.filter { it.isFinite() }.let { if (it.isEmpty()) 0.0 else sqrt(it.sumOf { v -> v * v } / it.size) }

/** Interpolate only across sub-500 ms arrival jitter. Reconnects and sensor stalls remain NaN. */
private fun resampleVectors(arrival: DoubleArray, xyz: Array<FloatArray>, n: Int): List<DoubleArray> {
    val t = arrival.copyOf()
    var first = 0
    // Retiming removes overlapping packet stamps without stretching a reconnect gap into motion.
    for (i in 1..t.size) {
        if (i < t.size && arrival[i] - arrival[i - 1] <= 1) continue
        val local = retime(arrival.copyOfRange(first, i))
        for (j in local.indices) t[first + j] = arrival[first] + local[j]
        first = i
    }
    val order = t.indices.filter { t[it].isFinite() && xyz[it].all { v -> v.isFinite() } }.sortedBy { t[it] }.distinctBy { t[it] }
    val out = List(3) { DoubleArray(n) { Double.NaN } }
    var j = 0
    for (i in 0 until n) {
        val at = i / FS
        while (j < order.lastIndex && t[order[j + 1]] <= at) j++
        val a = order.getOrNull(j) ?: continue
        val b = order.getOrNull(j + 1) ?: a
        if (at < t[a] || at - t[a] > .5 || t[b] - t[a] > .5) continue
        val mix = if (a == b) 0.0 else ((at - t[a]) / (t[b] - t[a])).coerceIn(0.0, 1.0)
        for (axis in 0..2) out[axis][i] = xyz[a][axis] + mix * (xyz[b][axis] - xyz[a][axis])
    }
    return out
}

private fun filterRuns(x: DoubleArray, lo: Double, hi: Double): DoubleArray {
    val out = DoubleArray(x.size) { Double.NaN }
    var a = 0
    while (a < x.size) {
        if (!x[a].isFinite()) { a++; continue }
        var b = a + 1
        while (b < x.size && x[b].isFinite()) b++
        if (b - a > 25) zeroPhase(x.copyOfRange(a, b), butterworth(FS, lo, hi)).copyInto(out, a)
        a = b
    }
    return out
}

/** Spectral concentration plus normalized autocorrelation rejects isolated impacts and unstructured motion. */
private fun period(x: DoubleArray, lo: Double, hi: Double, bandScore: Boolean = false): Period? {
    if (x.size < FS * 4 || x.any { !it.isFinite() }) return null
    val y = detrend(x)
    val energy = y.sumOf { it * it }
    if (energy < 1e-8) return null
    var nfft = 1
    while (nfft < x.size * 2) nfft *= 2
    val power = powerSpectrum(DoubleArray(y.size) { y[it] * (.5 - .5 * cos(2 * PI * it / (y.size - 1))) }, nfft)
    val low = ceil(lo * nfft / FS).toInt(); val high = min(power.lastIndex, floor(hi * nfft / FS).toInt())
    if (low > high) return null
    val k = (low..high).maxBy { power[it] }
    val hz = k * FS / nfft
    val total = (ceil(.12 * nfft / FS).toInt()..min(power.lastIndex, (5 * nfft / FS).toInt())).sumOf { power[it] }
    val radius = max(1, (.15 * nfft / FS).roundToInt())
    val fraction = (max(1, k - radius)..min(power.lastIndex, k + radius)).sumOf { power[it] } / total
    val lag = (FS / hz).roundToInt().coerceIn(1, y.size - 1)
    val signal = if (bandScore) zeroPhase(y, butterworth(FS, lo, hi)) else y
    var ab = 0.0; var aa = 0.0; var bb = 0.0
    for (i in lag until signal.size) { ab += signal[i] * signal[i - lag]; aa += signal[i] * signal[i]; bb += signal[i - lag] * signal[i - lag] }
    return Period(hz, if (aa * bb == 0.0) 0.0 else ab / sqrt(aa * bb), fraction)
}
