package dev.heartline.analytics

import kotlin.math.*
import kotlin.random.Random

/**
 * A made-up wrist recording with known beats, for tests: green PPG (each beat a dip in reflected light, with a
 * smaller diastolic wave), breathing-paced beat gaps, slow drift and sensor noise, and an accelerometer that is still
 * except in [moving] stretches, where the hand swings and the swing leaks into the PPG as an artifact [artifactCounts]
 * times the acceleration (in counts per g) through a short lag, as a loose strap does.
 */
internal class SyntheticPpg(
    val seconds: Double = 60.0,
    val bpm: Double = 64.0,
    /** Breathing modulation of the beat gap, peak, in seconds (RSA). */
    val rsaS: Double = .045,
    val breathHz: Double = .25,
    val fs: Double = 25.0,
    val accFs: Double = 26.16,
    val pulseCounts: Double = 220.0,
    val noiseCounts: Double = 6.0,
    val moving: List<ClosedFloatingPointRange<Double>> = emptyList(),
    val swingHz: Double = 1.3,
    val swingG: Double = .25,
    val artifactCounts: Double = 2500.0,
    seed: Int = 7,
) {
    private val rnd = Random(seed)
    val beats: DoubleArray
    init {
        val out = ArrayList<Double>(); var t = .6
        while (t < seconds) {
            out += t
            t += 60 / bpm + rsaS * sin(2 * PI * breathHz * t) + rnd.nextDouble(-.006, .006)
        }
        beats = out.toDoubleArray()
    }
    /** Beat gaps (s) between consecutive true beats, and their RMSSD in ms. */
    val rr: DoubleArray get() = DoubleArray(beats.size - 1) { beats[it + 1] - beats[it] }
    val rmssdMs: Double get() { val r = rr; return sqrt((1 until r.size).sumOf { (r[it] - r[it - 1]).pow(2) } / (r.size - 1)) * 1000 }

    fun isMoving(t: Double) = moving.any { t in it }
    /** Hand acceleration (g) on x, y, z while moving; z also carries gravity. Smooth on and off over 0.3 s. */
    fun swing(t: Double): Triple<Double, Double, Double> {
        val r = moving.firstOrNull { t in (it.start - .3)..(it.endInclusive + .3) } ?: return Triple(0.0, 0.0, 0.0)
        val ramp = min(1.0, min(t - (r.start - .3), r.endInclusive + .3 - t) / .3).coerceIn(0.0, 1.0)
        // Two tones, so the motion isn't one pure sinusoid the pulse could be confused with.
        val x = swingG * ramp * (sin(2 * PI * swingHz * t) + .4 * sin(2 * PI * 2.1 * swingHz * t + 1))
        val y = .6 * swingG * ramp * sin(2 * PI * swingHz * t + .8)
        val z = .5 * swingG * ramp * sin(2 * PI * swingHz * t + 2.1)
        return Triple(x, y, z)
    }
    private fun beatShape(t: Double): Double {
        var v = 0.0
        val i = beats.lowerBound(t)
        for (k in max(0, i - 2) until min(beats.size, i + 1)) {
            val d = t - beats[k]
            if (d < -.2 || d > 1.0) continue
            v += exp(-(d / .07).pow(2) / 2) + .35 * exp(-((d - .3) / .09).pow(2) / 2)
        }
        return v
    }
    private val ppgT = DoubleArray((seconds * fs).toInt()) { it / fs }
    private val accT = DoubleArray((seconds * accFs).toInt()) { it / accFs }
    private val noise = DoubleArray(ppgT.size) { rnd.nextDouble(-1.0, 1.0) * noiseCounts }
    private val accNoise = Array(accT.size) { DoubleArray(3) { rnd.nextDouble(-1.0, 1.0) * .004 } }
    val ppg = DoubleArray(ppgT.size) { i ->
        val t = ppgT[i]
        val (sx, sy, _) = swing(t - .04)
        27000 - pulseCounts * beatShape(t) + 40 * sin(2 * PI * .05 * t) + noise[i] + artifactCounts * (sx + .5 * sy)
    }
    val acc = Array(accT.size) { i ->
        val (sx, sy, sz) = swing(accT[i])
        floatArrayOf((sx + accNoise[i][0]).toFloat(), (sy + accNoise[i][1]).toFloat(), (1 + sz + accNoise[i][2]).toFloat())
    }

    /** As the app's CSV, with watch stamps on every sample. */
    fun csv(epoch0: Double = 1_800_000_000.0, watch0: Double = 500.0): String = buildString {
        appendLine("ts,kind,ppg,ax,ay,az,wts")
        var i = 0; var j = 0
        while (i < ppgT.size || j < accT.size) {
            if (j >= accT.size || (i < ppgT.size && ppgT[i] <= accT[j])) {
                appendLine("${epoch0 + ppgT[i]},ppg,${ppg[i].roundToInt()},,,,${watch0 + ppgT[i]}"); i++
            } else {
                appendLine("${epoch0 + accT[j]},accel,,${acc[j][0]},${acc[j][1]},${acc[j][2]},${watch0 + accT[j]}"); j++
            }
        }
    }
    fun recording(): Recording = readRecordingCsv(csv().reader())
    fun samples(upTo: Double = seconds, epoch0: Double = 1_800_000_000.0, watch0: Double = 500.0): LiveCheckSamples = LiveCheckSamples().also { s ->
        val pi = ppgT.indices.filter { ppgT[it] < upTo }
        s.addPpg(DoubleArray(pi.size) { epoch0 + ppgT[pi[it]] }, DoubleArray(pi.size) { watch0 + ppgT[pi[it]] },
            DoubleArray(pi.size) { ppg[pi[it]].roundToInt().toDouble() })
        val ai = accT.indices.filter { accT[it] < upTo }
        s.addAccel(DoubleArray(ai.size) { epoch0 + accT[ai[it]] }, DoubleArray(ai.size) { watch0 + accT[ai[it]] }, ai.map { acc[it] })
    }
}
