package dev.heartline.analytics

import kotlin.math.*

internal fun vectorAngle(a: DoubleArray, b: DoubleArray): Double {
    val norm = sqrt(a.sumOf { it * it } * b.sumOf { it * it })
    return if (norm < 1e-12) 0.0 else acos((a.indices.sumOf { a[it] * b[it] } / norm).coerceIn(-1.0, 1.0)) * 180 / PI
}

internal fun captureSlope(points: List<Pair<Double, Double>>): Double? {
    if (points.size < 2) return null
    val x = points.map { it.first }.average(); val y = points.map { it.second }.average()
    val den = points.sumOf { (it.first - x).pow(2) }
    return if (den < 1e-12) null else points.sumOf { (it.first - x) * (it.second - y) } / den
}

/** Consecutive wrist-orientation/tempo clusters, not named exercise recognition. */
internal fun exerciseGroups(sets: List<RhythmSet>, cardio: List<CaptureCardio>): IntArray {
    var group = 0
    return IntArray(sets.size) { i ->
        if (i > 0) {
            val a = sets[i - 1]; val b = sets[i]
            val angle = vectorAngle(a.gravity, b.gravity)
            val tempo = abs(ln(b.tempoS / a.tempoS))
            val arcA = a.repDetail.map { it.arcDeg }.takeIf { it.isNotEmpty() }?.let { median(it.toDoubleArray()) } ?: 0.0
            val arcB = b.repDetail.map { it.arcDeg }.takeIf { it.isNotEmpty() }?.let { median(it.toDoubleArray()) } ?: 0.0
            val arcChange = abs(arcB - arcA) > 15 && max(arcA, arcB) > 1.6 * min(arcA, arcB)
            if (b.start - a.end > 360 || cardio.any { it.start < b.start && it.end > a.end } ||
                angle > 25 || (angle > 10 && arcChange) || tempo > .4) group++
        }
        group
    }
}

internal fun captureRecovery(sets: List<CaptureSet>, cardio: List<CaptureCardio>, hr: IntArray): List<CaptureRecoveryPoint> {
    val eligible = sets.zipWithNext().filter { (a, b) -> b.start - a.end >= 60 &&
        cardio.none { it.start < b.start && it.end > a.end } }
    val values = Array(49) { ArrayList<Double>() }
    for ((a, b) in eligible) {
        val range = max(0, ceil(a.start).toInt()) until min(hr.size, ceil(min(a.end + 15, b.start)).toInt())
        val peak = range.filter { hr[it] > 0 }.maxByOrNull { hr[it] } ?: continue
        for (offset in 0..240 step 5) {
            val at = peak + offset
            if (at >= b.start) break
            // Offset zero is the alignment anchor; other samples must be in the rest.
            if (offset > 0 && at < a.end) continue
            hr.getOrNull(at)?.takeIf { it > 0 }?.let { values[offset / 5] += (it - hr[peak]).toDouble() }
        }
    }
    val minimum = max(3, ceil(eligible.size * .2).toInt())
    return values.mapIndexedNotNull { i, v -> if (v.size < minimum) null else CaptureRecoveryPoint(i * 5, v.average(), v.size) }
}

internal fun cardioType(type: String) = listOf("treadmill", "walk", "run", "cycl", "bike", "ellipt", "row", "stair", "cardio")
    .any { type.contains(it, ignoreCase = true) }

internal fun captureCardio(segments: List<CaptureSegment>, activities: List<CaptureActivity>, hr: IntArray): List<CaptureCardio> {
    val gait = segments.filter { it.kind == "walking" || it.kind == "running" }
    val merged = ArrayList<MutableList<CaptureSegment>>()
    for (g in gait) {
        if (merged.isNotEmpty() && g.start - merged.last().last().end <= 20) merged.last() += g
        else merged += mutableListOf(g)
    }
    // Split at workout boundaries so a watch name only describes gait inside that workout.
    val cardioActivities = activities.filter { cardioType(it.type) }
    val blocks = merged.flatMap { run ->
        val a = run.first().start; val b = run.last().end
        val edges = (listOf(a, b) + cardioActivities.flatMap { listOf(it.start, it.end) }.filter { it > a && it < b }).distinct().sorted()
        edges.zipWithNext().filter { (s, e) -> e - s >= 180 }.map { (s, e) -> Triple(s, e, run) }
    }.filterNot { (a, b, _) -> walkingBetweenSets(a, b, segments, cardioActivities) }
    fun at(t: Double) = hr.getOrNull(floor(t).toInt())?.takeIf { it > 0 }
    fun points(a: Double, b: Double) = (max(0, ceil(a).toInt()) until min(hr.size, ceil(b).toInt()))
        .filter { hr[it] > 0 }.map { it - a to hr[it].toDouble() }
    fun avg(p: List<Pair<Double, Double>>) = p.takeIf { it.isNotEmpty() }?.map { it.second }?.average()?.roundToInt()
    return blocks.mapIndexed { i, (a, rawEnd, run) ->
        val watch = cardioActivities.maxByOrNull { min(rawEnd, it.end) - max(a, it.start) }?.takeIf { it.start < rawEnd && it.end > a }
        // Walking on after the belt stops is cooldown, not the effort. The watch's active time marks the real end,
        // which matters for the recovery drop and time constant.
        val b = watch?.activeS?.takeIf { it > 0 }?.let { min(rawEnd, max(a + 180, watch.start + it + 5)) } ?: rawEnd
        val all = points(a, b); val steady = points((a + b) / 2, b)
        val next = blocks.getOrNull(i + 1)?.first ?: hr.size.toDouble()
        val endHr = at(b)
        fun drop(s: Int): Int? = if (b + s >= next) null else at(b + s)?.let { h -> endHr?.minus(h) }
        val recovery = points(b, min(b + 240, next))
        val cadence = run.mapNotNull { g -> g.cadenceSpm?.let { it to max(0.0, min(b, g.end) - max(a, g.start)) } }
        CaptureCardio(a, b, if (run.filter { it.kind == "running" }.sumOf { max(0.0, min(b, it.end) - max(a, it.start)) } > (b - a) / 2) "running" else "walking",
            watch?.type, avg(all), avg(steady), all.maxOfOrNull { it.second }?.roundToInt(), exponentialTau(all, rise = true),
            captureSlope(steady)?.times(60), drop(60), drop(120), exponentialTau(recovery, rise = false),
            cadence.takeIf { it.sumOf { p -> p.second } > 0 }?.let { c -> (c.sumOf { it.first * it.second } / c.sumOf { it.second }).roundToInt() },
            watch?.distanceM, watch?.let { w -> if ((w.activeS ?: 0) > 0 && w.distanceM != null) w.distanceM * 3.6 / w.activeS!! else null })
    }
}

/** Walking around during a rest (a set ends just before it, the next starts just after, under six minutes, no cardio
 * workout on the watch) is part of the rest, not cardio: 2026-11-03 had 3.5 minutes of it between tricep sets. */
private fun walkingBetweenSets(a: Double, b: Double, segments: List<CaptureSegment>, cardio: List<CaptureActivity>): Boolean {
    val lifting = segments.filter { it.kind == "lifting" }
    return b - a < 360 && cardio.none { it.start < b && it.end > a } &&
        lifting.any { abs(it.end - a) <= 30 } && lifting.any { abs(it.start - b) <= 30 }
}

/** Grid search with analytic two-parameter least squares. A non-flat fit needs >=30 s of observed HR.
 * Reject wrong-sign, boundary-optimum or essentially flat fits rather than reporting an arbitrary tau. */
internal fun exponentialTau(points: List<Pair<Double, Double>>, rise: Boolean): Int? {
    if (points.size < 20 || points.last().first - points.first().first < 30) return null
    val meanY = points.map { it.second }.average()
    val total = points.sumOf { (it.second - meanY).pow(2) }
    if (total < points.size) return null
    var best = Double.POSITIVE_INFINITY; var tauBest = 0; var amplitude = 0.0
    for (tau in 10..600) {
        var sx = 0.0; var sxx = 0.0; var sxy = 0.0
        for ((t, y) in points) {
            val x = if (rise) 1 - exp(-t / tau) else exp(-t / tau)
            sx += x; sxx += x * x; sxy += x * y
        }
        val den = sxx - sx * sx / points.size
        if (den < 1e-12) continue
        val a = (sxy - sx * meanY) / den
        if (a <= 0) continue
        val error = total - a * a * den
        if (error < best) { best = error; tauBest = tau; amplitude = a }
    }
    return tauBest.takeIf { it in 11..599 && amplitude >= 3 && best < total * .95 }
}
