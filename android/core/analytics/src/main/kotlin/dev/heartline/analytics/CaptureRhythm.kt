package dev.heartline.analytics

import kotlin.math.*

internal data class RhythmSet(val start: Double, val end: Double, val reps: Int, val tempoS: Double)
private const val RHYTHM_FS = 25.0

/** Port of gym2/prototype_detector.py. Arrival-clock interpolation is intentional: retiming changes rep rhythm.
 * Run the prototype independently across coverage gaps; never interpolate a reconnect into an exercise. */
internal fun captureRhythm(rec: Recording, gait: List<CaptureSegment>, duration: Double = Double.POSITIVE_INFINITY): List<RhythmSet> {
    val out = ArrayList<RhythmSet>()
    var first = 0
    fun process(end: Int) {
        if (end - first < 2) return
        val origin = rec.accArrivalT[first]
        val count = ceil((rec.accArrivalT[end - 1] - origin) * RHYTHM_FS).toInt()
        if (count < 250) return
        val axes = List(3) { DoubleArray(count) }
        var j = first
        // numpy.arange(epochStart, epochEnd, .04) uses the rounded epoch-sized increment.
        // Preserve it here: millisecond-scale drift can move a near-threshold ACF run boundary.
        val epochOrigin = rec.start + origin
        val step = (epochOrigin + 1 / RHYTHM_FS) - epochOrigin
        for (i in 0 until count) {
            val at = (epochOrigin + i * step) - rec.start
            while (j < end - 2 && rec.accArrivalT[j + 1] <= at) j++
            val a = rec.accArrivalT[j]; val b = rec.accArrivalT[j + 1]
            val mix = if (b <= a) 0.0 else ((at - a) / (b - a)).coerceIn(0.0, 1.0)
            for (k in 0..2) axes[k][i] = rec.acc[j][k] + mix * (rec.acc[j + 1][k].toDouble() - rec.acc[j][k])
        }
        val scores = rhythmPeriodicity(axes)
        val excluded = BooleanArray(scores.size) { s -> gait.any { origin + s >= it.start && origin + s < it.end } }
        val filtered = axes.map { zeroPhase(it, butterworth(RHYTHM_FS, .12, 1.0)) }
        for ((a, b) in rhythmRuns(scores, excluded)) {
            val (reps, tempo) = rhythmReps(filtered, a, b)
            out += RhythmSet(origin + a, origin + b, reps, tempo)
        }
    }
    for (i in rec.accArrivalT.indices) {
        val valid = rec.accArrivalT[i].isFinite() && rec.accArrivalT[i] in 0.0..duration && rec.acc[i].all { it.isFinite() }
        if (!valid || (i > first && rec.accArrivalT[i] - rec.accArrivalT[i - 1] > .5)) {
            process(i)
            first = if (valid) i else i + 1
        }
    }
    process(rec.acc.size)
    return out
}

internal fun rhythmPeriodicity(axes: List<DoubleArray>): DoubleArray {
    val filtered = axes.map { zeroPhase(it, butterworth(RHYTHM_FS, .1, 1.2)) }
    return DoubleArray(axes.first().size / 25) { second ->
        val a = max(0, (second - 10) * 25)
        val b = min(axes.first().size, a + 500)
        if (b - a < 250) 0.0 else filtered.maxOf { axis ->
            val x = axis.copyOfRange(a, b)
            val mu = mean(x)
            for (i in x.indices) x[i] -= mu
            val energy = x.sumOf { it * it }
            if (energy / x.size < 1e-12) 0.0 else acfMaximum(x, 37, 200).second.coerceAtLeast(0.0)
        }
    }
}

/** numpy.correlate's biased ACF: every lag uses the full zero-lag energy denominator. Upper lag is exclusive. */
internal fun acfMaximum(x: DoubleArray, low: Int, high: Int): Pair<Int, Double> {
    val energy = x.sumOf { it * it }
    if (energy <= 1e-20) return low to 0.0
    var lag = low; var best = Double.NEGATIVE_INFINITY
    for (k in low until min(high, x.size)) {
        var sum = 0.0
        for (i in k until x.size) sum += x[i] * x[i - k]
        if (sum > best) { best = sum; lag = k }
    }
    return lag to best / energy
}

/** scipy.ndimage.uniform_filter1d(size=5, mode=reflect), then the prototype's exact gap lookahead.
 * Its gap=6 bridges up to FIVE off-seconds (the current off-second belongs to the six-sample slice).
 * Coverage/gait exclusions are hard barriers, including inside a bridged run. */
internal fun rhythmRuns(scores: DoubleArray, excluded: BooleanArray = BooleanArray(scores.size)): List<Pair<Int, Int>> {
    if (scores.isEmpty()) return emptyList()
    fun reflect(index: Int): Int {
        var i = index
        while (i !in scores.indices) i = if (i < 0) -i - 1 else 2 * scores.size - i - 1
        return i
    }
    val on = BooleanArray(scores.size) { s -> !excluded[s] && (-2..2).sumOf { scores[reflect(s + it)] } / 5 > .45 }
    val out = ArrayList<Pair<Int, Int>>()
    var i = 0
    while (i < on.size) {
        if (!on[i]) { i++; continue }
        var j = i
        while (j < on.size && !excluded[j] && (on[j] ||
                (j + 6 < on.size && (j until j + 6).any { on[it] }))) j++
        if (j - i >= 12) out += i to j
        i = j
    }
    return out
}

/** Core PCA == first right singular vector of centered XYZ. Count both signs so eigenvector sign is immaterial. */
internal fun rhythmReps(filtered: List<DoubleArray>, start: Int, end: Int): Pair<Int, Double> {
    val a = start * 25; val b = end * 25
    val mu = DoubleArray(3) { k -> mean(filtered[k].copyOfRange(a, b)) }
    val cov = Array(3) { DoubleArray(3) }
    for (j in 0..2) for (k in j..2) {
        for (i in a until b) cov[j][k] += (filtered[j][i] - mu[j]) * (filtered[k][i] - mu[k])
        cov[k][j] = cov[j][k]
    }
    val v = dominantDirection(cov)
    fun project(from: Int, to: Int) = DoubleArray(to - from) { i -> (0..2).sumOf { k -> (filtered[k][from + i] - mu[k]) * v[k] } }
    val core = project(a, b)
    val window = project(max(0, a - 250), min(filtered.first().size, b + 250))
    val period = acfMaximum(core, 30, 200).first / RHYTHM_FS
    val amplitude = percentile(DoubleArray(core.size) { abs(core[it]) }, 90.0)
    if (amplitude < 1e-6) return 0 to period
    val distance = (0.55 * period * RHYTHM_FS).toInt().coerceAtLeast(1)
    return max(findPeaks(window, distance, .9 * amplitude).size,
        findPeaks(DoubleArray(window.size) { -window[it] }, distance, .9 * amplitude).size) to period
}

// Symmetric 3x3 Jacobi eigendecomposition avoids an SVD dependency and handles any wrist orientation.
internal fun dominantDirection(matrix: Array<DoubleArray>): DoubleArray {
    val a = Array(3) { matrix[it].copyOf() }
    val v = Array(3) { row -> DoubleArray(3) { col -> if (row == col) 1.0 else 0.0 } }
    repeat(30) {
        val (p, q) = listOf(0 to 1, 0 to 2, 1 to 2).maxBy { abs(a[it.first][it.second]) }
        if (abs(a[p][q]) > 1e-15) {
            val theta = .5 * atan2(2 * a[p][q], a[q][q] - a[p][p])
            val c = cos(theta); val s = sin(theta)
            val pp = a[p][p]; val qq = a[q][q]; val pq = a[p][q]
            a[p][p] = c*c*pp - 2*s*c*pq + s*s*qq
            a[q][q] = s*s*pp + 2*s*c*pq + c*c*qq
            a[p][q] = 0.0; a[q][p] = 0.0
            for (k in 0..2) {
                if (k != p && k != q) {
                    val kp = a[k][p]; val kq = a[k][q]
                    a[k][p] = c*kp - s*kq; a[p][k] = a[k][p]
                    a[k][q] = s*kp + c*kq; a[q][k] = a[k][q]
                }
                val vp = v[k][p]; val vq = v[k][q]
                v[k][p] = c*vp - s*vq; v[k][q] = s*vp + c*vq
            }
        }
    }
    val k = (0..2).maxBy { a[it][it] }
    return DoubleArray(3) { v[it][k] }
}
