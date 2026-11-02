package dev.heartline.analytics

import kotlin.math.*

data class CapturePoint(val t: Double, val v: Double)
data class CaptureZone(val zone: Int, val lowBpm: Int, val highBpm: Int?, val seconds: Int)
data class CaptureSegment(val start: Double, val end: Double, val kind: String, val avgHr: Int?, val cadenceSpm: Int?)
data class CaptureRep(val t: Double, val durationS: Double, val slowS: Double, val fastS: Double, val arcDeg: Double)
data class CaptureSet(val start: Double, val end: Double, val reps: Int, val avgHr: Int?, val peakHr: Int?, val tempoS: Double?,
    val repDetail: List<CaptureRep> = emptyList(), val group: Int = 0, val activity: String? = null,
    val slowdownS: Double? = null, val partialLast: Boolean = false, val startHr: Int? = null, val hrRise: Int? = null)

data class CaptureActivity(val start: Double, val end: Double, val type: String, val activeS: Int?, val distanceM: Int?,
    val steps: Int?, val calories: Int?, val avgHr: Int?, val maxHr: Int?)
data class CaptureRecoveryPoint(val t: Int, val delta: Double, val n: Int)
data class CaptureCardio(val start: Double, val end: Double, val kind: String, val name: String?, val avgHr: Int?,
    val steadyHr: Int?, val peakHr: Int?, val riseTauS: Int?, val driftPerMin: Double?, val drop60: Int?, val drop120: Int?,
    val recoveryTauS: Int?, val cadenceSpm: Int?, val distanceM: Int?, val speedKmh: Double?)
data class CapturePulseWindow(val start: Double, val end: Double, val beats: Int, val hrPpg: Int, val hrWatch: Int?,
    val rmssdMs: Double, val nrmssd: Double, val status: String)
data class CapturePulse(val status: String, val usableS: Int, val windows: List<CapturePulseWindow>, val nrmssd: Double?,
    val entropy: Double?, val ectopic: Int, val pairs: List<Pair<Double, Double>>, val excerpt: List<Double>,
    val excerptFs: Double, val excerptStart: Double, val excerptBeats: List<Double>, val notes: List<String>)
data class CaptureRest(val start: Double, val end: Double, val startHr: Int?, val endHr: Int?, val drop60: Int?)
data class CaptureResult(
    val label: String, val start: Double, val durationS: Double,
    val avgHr: Int?, val maxHr: Int?, val minHr: Int?, val zones: List<CaptureZone>,
    val load: Double?, val calories: Int?, val steps: Int?,
    val hr: List<CapturePoint>, val motion: List<CapturePoint>, val segments: List<CaptureSegment>,
    val sets: List<CaptureSet>, val rests: List<CaptureRest>, val hrSource: String, val notes: List<String>,
    val activities: List<CaptureActivity> = emptyList(), val recovery: List<CaptureRecoveryPoint> = emptyList(),
    val floor: List<CapturePoint> = emptyList(), val workS: Int? = null, val restS: Int? = null,
    val cardio: List<CaptureCardio> = emptyList(), val pulse: CapturePulse? = null,
)

/** HR_MEASURE is authoritative. No PPG substitution and no interpolation across a missing HR interval. */
fun analyzeCapture(rec: Recording, hr: List<Pair<Double, Int>>, profile: Profile, label: String,
    activities: List<CaptureActivity> = emptyList()): CaptureResult {
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
    fun near(t: Double): Int? = seconds.getOrNull(floor(t).toInt())?.takeIf { it > 0 }
    fun liveAt(t: Double): Int? {
        val epoch = rec.start + t
        val found = samples.binarySearch { it.first.compareTo(epoch) }
        val index = if (found >= 0) found else -found - 2
        return samples.getOrNull(index)?.takeIf { epoch - it.first < 5 }?.second
    }
    val valid = seconds.filter { it > 0 }
    val maxHr = profile.maxHr?.takeIf { it in 80..240 } ?: profile.age?.takeIf { it in 1..120 }?.let { 220 - it } ?: 190
    val zones = (1..5).map { z ->
        CaptureZone(z, ceil(maxHr * (z + 4) / 10.0).toInt(), if (z == 5) null else ceil(maxHr * (z + 5) / 10.0).toInt(),
            valid.count { captureZone(it, maxHr) == z })
    }
    val motion = captureMotion(rec, duration)
    val segments = motion.segments.map { it.copy(avgHr = avg(it.start, it.end)) }
    val cardio = captureCardio(segments, activities, seconds)
    val groups = exerciseGroups(motion.sets, cardio)
    val sets = motion.sets.mapIndexed { i, s ->
        val startHr = liveAt(s.start)
        var peak = startHr
        val found = samples.binarySearch { it.first.compareTo(rec.start + s.start) }
        var j = if (found >= 0) found else -found - 1
        while (j < samples.size && samples[j].first <= rec.start + s.end + 15) {
            peak = max(peak ?: 0, samples[j].second); j++
        }
        val detail = s.repDetail
        CaptureSet(s.start, s.end, s.reps, avg(s.start, s.end), hrs(s.start, s.end).maxOrNull(), s.tempoS,
            detail, groups[i], activities.firstOrNull { w -> val mid = (s.start + s.end) / 2; mid >= w.start && mid < w.labelEnd() }?.type,
            if (detail.size >= 5) captureSlope(detail.mapIndexed { k, r -> k.toDouble() to r.durationS }) else null,
            detail.size >= 4 && detail.last().arcDeg < .8 * median(detail.map { it.arcDeg }.toDoubleArray()),
            startHr, if (peak != null && startHr != null) peak - startHr else null)
    }
    val rests = sets.zipWithNext().mapNotNull { (a, b) ->
        if (b.start <= a.end) return@mapNotNull null
        val first = near(a.end); val last = near(b.start - 1)
        val at60 = if (b.start - a.end >= 60) near(a.end + 60) else null
        CaptureRest(a.end, b.start, first, last, if (first != null && at60 != null) first - at60 else null)
    }
    val calories = captureCalories(valid, profile)
    val notes = mutableListOf("Heart rate comes from the watch's live HR_MEASURE notifications; moving PPG is not used for heart rate.")
    if (valid.size < duration * .9) notes += "Live heart rate covers ${if (n == 0) 0 else valid.size * 100 / n}% of the capture; missing intervals are excluded from load and calories."
    if (motion.missingS > 1) notes += "Motion data is missing for approximately ${motion.missingS.roundToInt()} seconds; missing motion is labelled other."
    if (rec.mag.isNotEmpty()) notes += "Compass samples were recorded; uncalibrated magnetic values are not used to identify exercises."
    notes += "Movement labels, repetitions and steps are wrist-motion estimates; synced watch workout summaries are retained independently when available."
    if (calories != null) notes += "Calories are estimated from heart rate, age, sex and weight; resistance exercise and recovery can be poorly represented."
    val pulse = capturePulse(rec, seconds)
    return CaptureResult(label, rec.start, duration, valid.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
        valid.maxOrNull(), valid.minOrNull(), zones,
        valid.takeIf { it.isNotEmpty() }?.sumOf { captureZone(it, maxHr) / 60.0 }, calories,
        if (motion.points.isEmpty()) null else segments.filter { it.cadenceSpm != null }.sumOf { (it.end - it.start) * it.cadenceSpm!! / 60 }.roundToInt(),
        (0 until n step 5).mapNotNull { a -> avg(a.toDouble(), min(duration, a + 5.0))?.let { CapturePoint(a.toDouble(), it.toDouble()) } },
        motion.points, segments, sets, rests, "Watch live HR (HR_MEASURE)", notes, activities,
        captureRecovery(sets, cardio, seconds), (0 until n step 300).mapNotNull { a ->
            hrs(a.toDouble(), min(duration, a + 300.0)).takeIf { it.isNotEmpty() }?.let {
                CapturePoint(a.toDouble(), percentile(it.map(Int::toDouble).toDoubleArray(), 10.0)) }
        }, if (motion.points.isEmpty()) null else sets.sumOf { it.end - it.start }.roundToInt(),
        if (motion.points.isEmpty()) null else sets.zipWithNext().sumOf { (a, b) ->
            if (b.start - a.end <= 600 && cardio.none { it.start < b.start && it.end > a.end }) b.start - a.end else 0.0
        }.roundToInt(), cardio, pulse)
}

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

/** A cardio workout left running after the effort (e.g. the treadmill record kept open while lifting) only names
 * what happened during its active time; other workouts name everything inside them. */
private fun CaptureActivity.labelEnd(): Double =
    if (cardioType(type) && (activeS ?: 0) > 0) min(end, start + activeS!! + 30) else end
