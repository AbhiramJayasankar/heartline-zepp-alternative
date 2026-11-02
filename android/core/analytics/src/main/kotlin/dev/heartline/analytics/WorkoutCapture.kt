package dev.heartline.analytics

import kotlin.math.*

data class CapturePoint(val t: Double, val v: Double)
data class CaptureZone(val zone: Int, val lowBpm: Int, val highBpm: Int?, val seconds: Int)
data class CaptureSegment(val start: Double, val end: Double, val kind: String, val avgHr: Int?, val cadenceSpm: Int?)
data class CaptureSet(val start: Double, val end: Double, val reps: Int, val avgHr: Int?, val peakHr: Int?, val tempoS: Double?)
data class CaptureRest(val start: Double, val end: Double, val startHr: Int?, val endHr: Int?, val drop60: Int?)
data class CaptureResult(
    val label: String, val start: Double, val durationS: Double,
    val avgHr: Int?, val maxHr: Int?, val minHr: Int?, val zones: List<CaptureZone>,
    val load: Double?, val calories: Int?, val steps: Int?,
    val hr: List<CapturePoint>, val motion: List<CapturePoint>, val segments: List<CaptureSegment>,
    val sets: List<CaptureSet>, val rests: List<CaptureRest>, val hrSource: String, val notes: List<String>,
)

/** HR_MEASURE is authoritative. No PPG substitution and no interpolation across a missing HR interval. */
fun analyzeCapture(rec: Recording, hr: List<Pair<Double, Int>>, profile: Profile, label: String): CaptureResult {
    val samples = hr.filter { it.first.isFinite() && it.second in 25..240 && it.first >= rec.start }
        .sortedBy { it.first }.distinctBy { it.first }
    val duration = rec.captureDurationS ?: maxOf(rec.durationS, rec.accArrivalT.maxOrNull() ?: 0.0,
        rec.gyroT.maxOrNull() ?: 0.0, rec.magT.maxOrNull() ?: 0.0, samples.lastOrNull()?.let { it.first - rec.start + 1 } ?: 0.0)
    require(duration.isFinite() && duration >= 0)
    val n = ceil(duration).toInt()
    // A value holds for at most five seconds. Missing coverage never contributes to zones/load/calories.
    val seconds = IntArray(n)
    var cursor = 0
    for (i in seconds.indices) {
        val t = rec.start + i + .5
        while (cursor < samples.lastIndex && samples[cursor + 1].first <= t) cursor++
        samples.getOrNull(cursor)?.let { if (t >= it.first && t - it.first < 5) seconds[i] = it.second }
    }
    fun hrs(a: Double, b: Double) = (max(0, ceil(a).toInt()) until min(n, ceil(b).toInt())).map { seconds[it] }.filter { it > 0 }
    fun avg(a: Double, b: Double) = hrs(a, b).takeIf { it.isNotEmpty() }?.average()?.roundToInt()
    fun near(t: Double): Int? = seconds.getOrNull(t.floorIndex(n))?.takeIf { it > 0 }
    val valid = seconds.filter { it > 0 }
    val maxHr = profile.maxHr?.takeIf { it in 80..240 } ?: profile.age?.takeIf { it in 1..120 }?.let { 220 - it } ?: 190
    val zones = (1..5).map { z ->
        CaptureZone(z, ceil(maxHr * (z + 4) / 10.0).toInt(), if (z == 5) null else ceil(maxHr * (z + 5) / 10.0).toInt(),
            valid.count { captureZone(it, maxHr) == z })
    }
    val motion = captureMotion(rec, duration)
    val segments = motion.segments.map { it.copy(avgHr = avg(it.start, it.end)) }
    val sets = segments.filter { it.kind == "lifting" }.map { s ->
        val peaks = motion.repTimes.filter { it >= s.start && it < s.end }
        CaptureSet(s.start, s.end, peaks.size, s.avgHr, hrs(s.start, s.end).maxOrNull(),
            if (peaks.size > 1) (peaks.last() - peaks.first()) / (peaks.size - 1) else null)
    }
    val rests = sets.zipWithNext().flatMap { (a, b) ->
        segments.filter { it.kind == "rest" && it.start >= a.end && it.end <= b.start }.map { rest ->
            val first = near(rest.start); val last = near(rest.end - 1)
            val at60 = if (rest.end - rest.start >= 60) near(rest.start + 60) else null
            CaptureRest(rest.start, rest.end, first, last, if (first != null && at60 != null) first - at60 else null)
        }
    }
    val calories = captureCalories(valid, profile)
    val notes = mutableListOf("Heart rate comes from the watch's live HR_MEASURE notifications; moving PPG is not used for heart rate.")
    if (valid.size < duration * .9) notes += "Live heart rate covers ${if (n == 0) 0 else valid.size * 100 / n}% of the capture; missing intervals are excluded from load and calories."
    if (motion.missingS > 1) notes += "Motion data is missing for approximately ${motion.missingS.roundToInt()} seconds; gaps are not classified as rest."
    if (rec.gyro.isEmpty()) notes += "No gyroscope samples were available; lifting detection is less certain."
    if (rec.mag.isNotEmpty()) notes += "Compass samples were recorded; uncalibrated magnetic values are not used to identify exercises."
    notes += "Movement labels, repetitions and steps are wrist-motion estimates; the watch does not announce workout start or stop."
    if (calories != null) notes += "Calories are estimated from heart rate, age, sex and weight; resistance exercise and recovery can be poorly represented."
    ppgCrossCheck(rec, segments, seconds)?.let { notes += it }
    return CaptureResult(label, rec.start, duration, valid.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
        valid.maxOrNull(), valid.minOrNull(), zones,
        valid.takeIf { it.isNotEmpty() }?.sumOf { captureZone(it, maxHr) / 60.0 }, calories,
        if (motion.points.isEmpty()) null else segments.filter { it.cadenceSpm != null }.sumOf { (it.end - it.start) * it.cadenceSpm!! / 60 }.roundToInt(),
        (0 until n step 5).mapNotNull { a -> avg(a.toDouble(), min(duration, a + 5.0))?.let { CapturePoint(a.toDouble(), it.toDouble()) } },
        motion.points, segments, sets, rests, "Watch live HR (HR_MEASURE)", notes)
}

private fun Double.floorIndex(n: Int) = if (n == 0) -1 else floor(this).toInt().coerceIn(0, n - 1)
private fun captureZone(hr: Int, max: Int): Int = when {
    hr * 10 < 5 * max -> 0; hr * 10 < 6 * max -> 1; hr * 10 < 7 * max -> 2
    hr * 10 < 8 * max -> 3; hr * 10 < 9 * max -> 4; else -> 5
}

private fun captureCalories(hr: List<Int>, p: Profile): Int? {
    val age = p.age?.takeIf { it > 0 } ?: return null
    val weight = p.weightKg?.takeIf { it.isFinite() && it > 0 } ?: return null
    val sex = p.sex?.lowercase()?.takeIf { it == "male" || it == "female" } ?: return null
    if (hr.isEmpty()) return null
    // Keytel et al. (2005), sex-specific HR/weight/age equations, kJ/min -> kcal/second.
    return hr.sumOf { h -> max(0.0, if (sex == "male") -55.0969 + .6309 * h + .1988 * weight + .2017 * age
        else -20.4022 + .4472 * h - .1263 * weight + .074 * age) / (4.184 * 60) }.roundToInt()
}

private fun ppgCrossCheck(rec: Recording, segments: List<CaptureSegment>, hr: IntArray): String? {
    // Limit the expensive beat detector to one sustained still interval. PPG never fills HR gaps.
    val rest = segments.firstOrNull { it.kind == "rest" && it.end - it.start >= 30 } ?: return null
    val end = min(rest.end, rest.start + 60)
    val pi = rec.ppgT.indices.filter { rec.ppgT[it] in rest.start..end }
    if (pi.size < 500) return null
    val ai = rec.accT.indices.filter { rec.accT[it] in rest.start..end }
    val slice = Recording(rec.start + rest.start, pi.map { rec.ppgT[it] - rest.start }.toDoubleArray(),
        pi.map { rec.ppg[it] }.toDoubleArray(), ai.map { rec.accT[it] - rest.start }.toDoubleArray(), ai.map { rec.acc[it] }.toTypedArray())
    val check = analyzeSession(slice, "capture-still-cross-check")
    val watch = hr.drop(rest.start.toInt()).take((end - rest.start).toInt()).filter { it > 0 }
    return if (check.signalQuality >= .7 && check.hrBpm != null && watch.isNotEmpty())
        "Still-period PPG cross-check: ${check.hrBpm.roundToInt()} bpm versus ${watch.average().roundToInt()} bpm from the watch."
    else "Still-period PPG did not provide a reliable independent heart-rate cross-check."
}
