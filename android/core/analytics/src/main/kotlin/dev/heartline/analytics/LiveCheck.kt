package dev.heartline.analytics

import kotlin.math.*

/**
 * What a heart check has found so far, worked out by the same steps as the finished check ([analyzeSession]): the
 * same LED warm-up trim, filter, beat finder, shape and movement tests, and RMSSD over back-to-back clean gaps. So the
 * live numbers are the ones the result will start from, not a separate estimate.
 *
 * [wave] is the filtered pulse at [waveFs] Hz from t = 0, in the sensor's own units (light counts, slow drift removed,
 * turned so each beat points up). Beat times and motion times are seconds on the same clock.
 */
data class LiveCheck(
    val durationS: Double,
    val waveFs: Double,
    val wave: FloatArray,
    val beatT: DoubleArray,
    val beatGood: BooleanArray,
    val motionT: DoubleArray,
    val motionG: FloatArray,
    /** From the clean gaps of the last [HR_WINDOW_S] seconds; null until there are 4 of them. */
    val hrBpm: Double?,
    val beats: Int,
    val cleanBeats: Int,
    /** Sum of the clean gaps, as the result's "usable" time. */
    val cleanS: Double,
    /** Over the whole check so far; null until 10 clean gaps with 5 back-to-back differences. */
    val rmssdMs: Double?,
    /** Wrist movement now: the accelerometer's spread over the last 2 s, summed over its axes, in g ([motionSeries]). */
    val motionNowG: Double?,
) {
    companion object {
        const val HR_WINDOW_S = 15.0
        /** Beats are set aside while the wrist moves more than this (detectBeats). */
        const val MOVING_G = dev.heartline.analytics.MOVING_G
    }
}

private const val WAVE_STEP = 4 // 100 Hz analysis grid -> 25 Hz for drawing

fun liveCheck(rec: Recording): LiveCheck? {
    if (rec.ppg.size < 25 || rec.durationS < 2) return null
    val motion = motionSeries(rec)
    val beats = detectBeats(rec, motion)
    val filtered = beats.filtered.takeIf { it.isNotEmpty() } ?: pulseWave(rec)
    val wave = FloatArray((filtered.size + WAVE_STEP - 1) / WAVE_STEP) { filtered[it * WAVE_STEP].toFloat() }
    val iv = intervals(beats.times, beats.good)
    val keep = iv.cleanIndices
    val cleanS = keep.sumOf { iv.rr[it] }
    val recent = keep.filter { iv.t[it] >= rec.durationS - LiveCheck.HR_WINDOW_S }.map { iv.rr[it] }
    val hr = if (recent.size >= 4) (60 / median(recent.toDoubleArray())).takeIf { it in 35.0..200.0 } else null
    return LiveCheck(rec.durationS, 100.0 / WAVE_STEP, wave, beats.times, beats.good,
        motion.t, FloatArray(motion.g.size) { motion.g[it].toFloat() }, hr, beats.times.size, beats.good.count { it },
        cleanS, hrvMetrics(iv).rmssdMs, motion.g.takeIf { it.isNotEmpty() }?.let { g ->
            // The last grid point can sit past the newest sample, where its window is half empty.
            val last = motion.t.indexOfLast { it <= rec.accT.lastOrNull() ?: 0.0 }
            if (last >= 0) g[last] else null
        })
}

/** detectBeats' filter and polarity, for the first seconds before it will look for beats. */
private fun pulseWave(rec: Recording): DoubleArray {
    val t = grid(0.0, rec.ppgT.last(), 0.01)
    if (t.size < 20) return DoubleArray(0)
    val y = zeroPhase(interpolate(rec.ppgT, rec.ppg, t), butterworth(100.0, 0.5, 4.0))
    val d = DoubleArray(y.size - 1) { y[it + 1] - y[it] }
    val dm = mean(d)
    var skew = 0.0
    for (v in d) skew += (v - dm).pow(3)
    if (skew < 0) for (i in y.indices) y[i] = -y[i]
    return y
}

/**
 * The raw stream of a running check, gathered sample by sample as the recording writes it (phone time and watch
 * time per sample, watch time null where a packet had no stamp), so [recording] gives what [readRecordingCsv] would
 * give for the file at this moment.
 */
class LiveCheckSamples {
    private val pt = ArrayList<Double>(); private val p = ArrayList<Double>(); private val pw = ArrayList<Double>()
    private val at = ArrayList<Double>(); private val a = ArrayList<FloatArray>(); private val aw = ArrayList<Double>()
    private val gt = ArrayList<Double>(); private val g = ArrayList<FloatArray>(); private val gw = ArrayList<Double>()

    val ppgCount: Int get() = synchronized(this) { p.size }

    fun addPpg(epoch: DoubleArray, watch: DoubleArray?, values: DoubleArray) = synchronized(this) {
        for (i in epoch.indices) { pt += epoch[i]; p += values[i]; pw += watch?.get(i) ?: Double.NaN }
    }
    fun addAccel(epoch: DoubleArray, watch: DoubleArray?, xyz: List<FloatArray>) = synchronized(this) {
        for (i in epoch.indices) { at += epoch[i]; a += xyz[i]; aw += watch?.get(i) ?: Double.NaN }
    }
    fun addGyro(epoch: DoubleArray, watch: DoubleArray?, xyz: List<FloatArray>) = synchronized(this) {
        for (i in epoch.indices) { gt += epoch[i]; g += xyz[i]; gw += watch?.get(i) ?: Double.NaN }
    }

    fun recording(): Recording = synchronized(this) {
        watchRecording(ArrayList(pt), ArrayList(p), ArrayList(at), ArrayList(a), ArrayList(gt), ArrayList(g),
            emptyList(), emptyList(), ArrayList(pw), ArrayList(aw), ArrayList(gw), emptyList())
    }
}
