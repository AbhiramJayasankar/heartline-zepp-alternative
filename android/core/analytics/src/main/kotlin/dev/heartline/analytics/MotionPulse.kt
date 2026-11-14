package dev.heartline.analytics

import kotlin.math.*

/**
 * A stretch where the watch's heart rate was replaced from the raw pulse: [watchBpm] and [fixedBpm] are the averages
 * over it, [evidence] how far the pulse stood out of its own noise there (median, log scale; 0.5 and up is clear).
 */
data class CaptureHrFix(val start: Double, val end: Double, val watchBpm: Int, val fixedBpm: Int, val evidence: Double)

/**
 * Motion-robust heart rate from the raw optical pulse, used to check the watch where wrist heart rate is known to go
 * wrong: while you run or walk, the optical sensor can lock onto the rhythm of your steps instead of your pulse, and
 * the watch then reports your step rate as your heart rate. Elsewhere the watch's own figure stands.
 *
 * Method, chosen on the PhysioNet "Wrist PPG during exercise" records (ECG reference; walk and run MAE 4.3 bpm, within
 * 5 bpm 76% of the time) and checked on the user's own captures:
 *  1. PPG and the six motion channels (plus the accelerometer and gyro magnitudes) band-passed 0.6 to 4.2 Hz on a
 *     25 Hz grid.
 *  2. What the motion predicts of the PPG is removed by recursive least squares over four lags of each motion
 *     channel, run forwards and backwards; each 8 s frame keeps the pass that left less.
 *  3. Each frame's spectrum (1 s hop, 40 to 220 bpm) against its own median: a peak counts only as far as it stands
 *     clear of the frame's noise (log1p(max(E - 5, 0) / 4)).
 *  4. The arm swing's harmonics (half the step rate and its multiples) carry no evidence either way.
 *  5. A Viterbi path through that evidence, 0.5 per bpm of change and at most 6 bpm a second, with a gentle pull
 *     towards the watch where its reading is trusted (not on the step rate).
 *
 * On the user's captures the watch was right nearly everywhere this path could check it: within 8 bpm of it 74 to 96%
 * of moving time. Readings on the step rate were rare (a few percent of moving seconds), so a correction is made only
 * where all of these hold for 15 s or more: running (walking heart rate and walking cadence are both near 100, so a
 * match there is no sign of a lock), the watch within [LOCK_TOL] bpm of the step rate, the path more than 10 bpm away
 * from it and the pulse clear of its noise there (median evidence of 0.5 or more), and the new figure plausible: at
 * least [RUN_MIN_BPM], within [MAX_FIX_BPM] of the watch's, and not on or next to an arm-swing harmonic. On a walk on
 * 13 Nov the path, left to itself, settled on the arm swing at about 50 bpm; those guards are what keep it out.
 */
internal fun motionHrFixes(rec: Recording, watch: IntArray, segments: List<CaptureSegment>): List<Pair<CaptureHrFix, IntArray>> {
    if (rec.ppg.size < 25 * 60 || rec.acc.isEmpty()) return emptyList()
    val n = watch.size
    // Step rate per second from the gait timeline (wrist cadence), only while walking or running.
    val steps = DoubleArray(n) { Double.NaN }
    val running = BooleanArray(n)
    for (s in segments) if ((s.kind == "running" || s.kind == "walking") && s.cadenceSpm != null)
        for (t in max(0, s.start.toInt()) until min(n, ceil(s.end).toInt())) { steps[t] = s.cadenceSpm.toDouble(); running[t] = s.kind == "running" }
    val locked = BooleanArray(n) { running[it] && watch[it] > 0 && steps[it].isFinite() && abs(watch[it] - steps[it]) <= LOCK_TOL }
    // Bridge 3-second holes, then keep runs of 15 s and more.
    val windows = runsOf(locked, bridge = 3).filter { (a, b) -> b - a >= 15 }
    if (windows.isEmpty()) return emptyList()
    val out = ArrayList<Pair<CaptureHrFix, IntArray>>()
    val grid = PulseGrid.of(rec) ?: return emptyList()
    for ((a, b) in windows) {
        val from = max(0, a - 60); val to = min(n, b + 60)
        val est = grid.estimate(from.toDouble(), to.toDouble(), watch, locked, steps) ?: continue
        val fixed = IntArray(b - a); val ev = DoubleArray(b - a); var far = 0
        for (t in a until b) {
            val k = est.indexAt(t + .5) ?: continue
            fixed[t - a] = est.bpm[k]; ev[t - a] = est.evidence[k]
            if (watch[t] > 0 && abs(est.bpm[k] - watch[t]) > 10) far++
        }
        val evidence = median(ev)
        if (far < (b - a) * .8 || evidence < .5 || fixed.any { it == 0 }) continue
        val watchAvg = (a until b).map { watch[it] }.filter { it > 0 }.average().roundToInt()
        // Plausible as a running heart rate, and not the movement's own rhythm showing through.
        val onSwing = (a until b).count { t -> val sw = steps[t] / 2; sw.isFinite() && (1..5).any { h -> abs(fixed[t - a] - sw * h) <= 6 } }
        if (fixed.average() < RUN_MIN_BPM || abs(fixed.average() - watchAvg) > MAX_FIX_BPM || onSwing > (b - a) * .2) continue
        out += CaptureHrFix(a.toDouble(), b.toDouble(), watchAvg, fixed.average().roundToInt(), rounded(evidence, 2)) to fixed
    }
    return out
}

/** The watch's reading within this many bpm of the step rate counts as locked onto it. */
internal const val LOCK_TOL = 5
/** A corrected running heart rate is at least this, and at most this far from the watch's. */
internal const val RUN_MIN_BPM = 100
internal const val MAX_FIX_BPM = 40

private fun runsOf(mask: BooleanArray, bridge: Int): List<Pair<Int, Int>> {
    val m = mask.copyOf()
    var i = 0
    while (i < m.size) {
        if (m[i]) { i++; continue }
        var j = i
        while (j < m.size && !m[j]) j++
        if (i > 0 && j < m.size && j - i <= bridge) for (k in i until j) m[k] = true
        i = j
    }
    val out = ArrayList<Pair<Int, Int>>(); var a = -1
    for (k in 0..m.size) {
        val on = k < m.size && m[k]
        if (on && a < 0) a = k
        if (!on && a >= 0) { out += a to k; a = -1 }
    }
    return out
}

internal class PulseEstimate(val t: DoubleArray, val bpm: IntArray, val evidence: DoubleArray) {
    fun indexAt(time: Double): Int? {
        if (t.isEmpty()) return null
        val i = t.lowerBound(time).coerceIn(0, t.lastIndex)
        val j = if (i > 0 && abs(t[i - 1] - time) < abs(t[i] - time)) i - 1 else i
        return j.takeIf { abs(t[it] - time) <= 1.0 }
    }
}

/** The capture on one 25 Hz grid: PPG, accelerometer and gyro, each from its own de-jittered clock. */
internal class PulseGrid private constructor(val ppg: DoubleArray, val motion: Array<DoubleArray>) {
    companion object {
        const val FS = 25.0
        const val WIN = 200      // 8 s
        const val HOP = 25       // 1 s
        const val BPM_LO = 40
        const val BPM_HI = 220
        private const val LAGS = 4
        private const val FORGET = .997
        private const val TAU = 5.0
        private const val KAPPA = 4.0
        private const val LAM = .5
        private const val MAXSTEP = 6
        private const val COMB_W = 2
        private const val HARMONICS = 5
        private const val SIGMA = 8.0
        private const val BETA = 1.0

        fun of(rec: Recording): PulseGrid? {
            val pt = if (rec.watchClock) rec.ppgT else capturePpgClock(rec.ppgArrivalT)
            if (pt.size < 2) return null
            val n = floor(pt.last() * FS).toInt() + 1
            if (n < WIN * 4) return null
            fun uniform(t: DoubleArray, v: (Int) -> Double): DoubleArray {
                val order = t.indices.filter { t[it].isFinite() && v(it).isFinite() }.sortedBy { t[it] }
                val tt = DoubleArray(order.size) { t[order[it]] }; val vv = DoubleArray(order.size) { v(order[it]) }
                return if (tt.size < 2) DoubleArray(n) else interpolate(tt, vv, DoubleArray(n) { it / FS })
            }
            val ppg = uniform(pt) { rec.ppg[it] }
            val at = if (rec.watchClock) rec.accT else capturePpgClock(rec.accArrivalT)
            val acc = Array(3) { k -> uniform(at) { rec.acc[it][k].toDouble() } }
            val gt = if (rec.gyroT.isEmpty()) DoubleArray(0) else if (rec.watchClock) rec.gyroT else capturePpgClock(rec.gyroT)
            val gyro = if (rec.gyro.size == gt.size && gt.size > 1) Array(3) { k -> uniform(gt) { rec.gyro[it][k].toDouble() } } else emptyArray()
            val amag = DoubleArray(n) { i -> sqrt(acc.sumOf { it[i] * it[i] }) }
            val gmag = if (gyro.isEmpty()) null else DoubleArray(n) { i -> sqrt(gyro.sumOf { it[i] * it[i] }) }
            return PulseGrid(ppg, (acc.toList() + gyro.toList() + listOfNotNull(amag, gmag)).toTypedArray())
        }

        private val bins = BPM_HI - BPM_LO + 1
        private val window = DoubleArray(WIN) { .5 - .5 * cos(2 * PI * it / (WIN - 1)) }
        private val cosT = Array(bins) { b -> DoubleArray(WIN) { cos(2 * PI * (BPM_LO + b) / 60.0 * it / FS) * window[it] } }
        private val sinT = Array(bins) { b -> DoubleArray(WIN) { sin(2 * PI * (BPM_LO + b) / 60.0 * it / FS) * window[it] } }

        /** Power at each whole bpm of one frame (mean removed, Hann window). */
        fun spectrum(x: DoubleArray, at: Int): DoubleArray {
            var m = 0.0
            for (i in 0 until WIN) m += x[at + i]
            m /= WIN
            return DoubleArray(bins) { b ->
                var re = 0.0; var im = 0.0
                val c = cosT[b]; val s = sinT[b]
                for (i in 0 until WIN) { val v = x[at + i] - m; re += v * c[i]; im += v * s[i] }
                re * re + im * im
            }
        }

        /** Exponentially weighted recursive least squares: [d] minus what [x] (columns) predicts of it. */
        fun rlsResidual(d: DoubleArray, x: Array<DoubleArray>, reverse: Boolean): DoubleArray {
            val n = d.size; val k = x.size
            val w = DoubleArray(k); val p = Array(k) { i -> DoubleArray(k) { j -> if (i == j) 100.0 else 0.0 } }
            val out = DoubleArray(n); val v = DoubleArray(k); val pv = DoubleArray(k)
            for (step in 0 until n) {
                val t = if (reverse) n - 1 - step else step
                for (i in 0 until k) v[i] = x[i][t]
                var denom = FORGET
                for (i in 0 until k) { var s = 0.0; val row = p[i]; for (j in 0 until k) s += row[j] * v[j]; pv[i] = s; denom += v[i] * s }
                var e = d[t]
                for (i in 0 until k) e -= w[i] * v[i]
                for (i in 0 until k) {
                    val g = pv[i] / denom
                    w[i] += g * e
                    val row = p[i]
                    for (j in 0 until k) row[j] = (row[j] - g * pv[j]) / FORGET
                }
                out[t] = e
            }
            return out
        }

        fun viterbi(logE: Array<DoubleArray>): IntArray {
            val T = logE.size; val S = bins
            var score = logE[0].copyOf()
            val back = Array(T) { IntArray(S) }
            for (t in 1 until T) {
                val next = DoubleArray(S) { Double.NEGATIVE_INFINITY }
                for (s in 0 until S) {
                    var best = Double.NEGATIVE_INFINITY; var arg = s
                    for (d in -MAXSTEP..MAXSTEP) {
                        val from = s - d
                        if (from < 0 || from >= S) continue
                        val v = score[from] - LAM * abs(d)
                        if (v > best) { best = v; arg = from }
                    }
                    next[s] = best + logE[t][s]; back[t][s] = arg
                }
                score = next
            }
            val path = IntArray(T)
            path[T - 1] = score.indices.maxBy { score[it] }
            for (t in T - 1 downTo 1) path[t - 1] = back[t][path[t]]
            return path
        }
    }

    private fun band(x: DoubleArray): DoubleArray = zeroPhase(x, butterworth(FS, .6, 4.2))

    /**
     * The path over [from, to) seconds: anchored to [watch] where it isn't [locked], with the arm-swing harmonics
     * ([steps] / 2 and multiples) neutral.
     */
    fun estimate(from: Double, to: Double, watch: IntArray, locked: BooleanArray, steps: DoubleArray): PulseEstimate? {
        val a = max(0, (from * FS).toInt()); val b = min(ppg.size, (to * FS).toInt())
        if (b - a < WIN * 3) return null
        val p = band(ppg.copyOfRange(a, b)).let { x -> val sd = sqrt(variance(x)).takeIf { it > 0 } ?: 1.0; DoubleArray(x.size) { x[it] / sd } }
        val regressors = ArrayList<DoubleArray>()
        for (ch in motion) {
            val m = band(ch.copyOfRange(a, b))
            val sd = sqrt(variance(m)).takeIf { it > 1e-12 } ?: continue
            for (lag in 0 until LAGS) regressors += DoubleArray(m.size) { i -> m[(i - lag + m.size) % m.size] / sd }
        }
        val x = regressors.toTypedArray()
        val fwd = rlsResidual(p, x, false); val bwd = rlsResidual(p, x, true)
        val frames = (0..(p.size - WIN) step HOP).toList()
        val bins = BPM_HI - BPM_LO + 1
        val logE = Array(frames.size) { DoubleArray(bins) }
        val own = Array(frames.size) { DoubleArray(bins) }
        val centre = DoubleArray(frames.size)
        for ((fi, at) in frames.withIndex()) {
            val sf = spectrum(fwd, at); val sb = spectrum(bwd, at)
            val c = if (sf.sum() <= sb.sum()) sf else sb
            val med = median(c).takeIf { it > 0 } ?: 1e-12
            val tc = (a + at + WIN / 2.0) / FS
            centre[fi] = tc
            val sec = tc.toInt().coerceIn(0, steps.lastIndex)
            val swing = steps[sec] / 2
            for (k in 0 until bins) {
                val bpm = BPM_LO + k
                var e = c[k] / med
                if (swing.isFinite() && (1..HARMONICS).any { h -> abs(bpm - swing * h) <= COMB_W + .5 }) e = min(1.0, TAU)
                val v = ln(1 + max(e - TAU, 0.0) / KAPPA)
                own[fi][k] = v
                val w = watch.getOrElse(sec) { 0 }
                logE[fi][k] = v + if (w > 0 && !locked.getOrElse(sec) { true }) BETA * exp(-.5 * ((bpm - w) / SIGMA).pow(2)) else 0.0
            }
        }
        val path = viterbi(logE)
        val evidence = DoubleArray(frames.size) { f -> (max(0, path[f] - 2)..min(bins - 1, path[f] + 2)).maxOf { own[f][it] } }
        return PulseEstimate(centre, IntArray(frames.size) { BPM_LO + path[it] }, evidence)
    }
}
