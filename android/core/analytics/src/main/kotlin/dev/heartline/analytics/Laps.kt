package dev.heartline.analytics

import kotlin.math.*

/**
 * Where laps start and finish: a point on the loop ([lat], [lon]) and the moments the track passed it ([passes], seconds
 * from the start, to a fraction of a second). Each pair of passes in a row is one lap; [loopM] is the median lap's length
 * along the track.
 */
data class LapLine(val lat: Double, val lon: Double, val passes: List<Double>, val loopM: Double)

/**
 * Finds laps in a GPS track: the point on the route passed most often, travelling the same way, with a lap of at least
 * [minLapM] between passes that goes at least 100 m from it. A point is passed when the track comes within a radius of
 * it; the pass is the moment of closest approach. Among points passed equally often, the first one reached wins, so laps
 * start where the first lap did. A point where the route crosses itself is passed going two ways, so it doesn't count
 * twice a lap. Null without two whole laps, or when the laps' lengths don't agree (each within 25% of the median), as on
 * a route with no loop in it or one that came back a different way.
 */
fun findLapLine(track: List<GeoFix>, minLapM: Double = 250.0): LapLine? =
    // A watch's GPS can wander tens of metres off the path and miss a tight radius on some laps (19 and 21 Nov 2026):
    // the radius that finds the most laps wins, the tightest on a tie.
    LAP_RADII.mapNotNull { findLapLine(track, it, minLapM) }.maxByOrNull { it.passes.size }

private val LAP_RADII = listOf(20.0, 30.0, 45.0)

fun findLapLine(track: List<GeoFix>, radiusM: Double, minLapM: Double): LapLine? {
    val p = smoothTrack(track)
    if (p.size < 60) return null
    val s = pathLengths(p)
    if (s.last() < 2 * minLapM) return null
    val heading = DoubleArray(p.size) { i -> bearing(p[max(0, i - 5)], p[min(p.lastIndex, i + 5)]) }
    var best: Pair<Int, List<Pass>>? = null
    var next = 0.0
    for (c in p.indices) {
        if (s[c] < next) continue
        next = s[c] + 10
        val passes = passesOf(p, s, heading, c, radiusM, minLapM)
        if (passes.size < 3 || passes.size <= (best?.second?.size ?: 0)) continue
        val laps = passes.zipWithNext { a, b -> b.at - a.at }
        val median = laps.sorted()[laps.size / 2]
        if (median < minLapM || laps.any { it < 0.75 * median || it > 1.25 * median }) continue
        // Each lap goes out from the point and back: a walk out and home along a different street is no lap.
        val reach = passes.zipWithNext { a, b -> p.indices.filter { s[it] > a.at && s[it] < b.at }.maxOfOrNull { metresBetween(p[it], p[c]) } ?: 0.0 }
        if (reach.any { it < 100 }) continue
        best = c to passes
    }
    val (c, found) = best ?: return null
    val loop = found.zipWithNext { x, y -> y.at - x.at }.sorted().let { it[it.size / 2] }
    val passes = fillMissed(p, s, heading, c, found, loop)
    return LapLine(p[c].lat, p[c].lon, passes.map { it.t }, loop)
}

/**
 * Passes a wandering track missed: wherever a lap (or what follows the last pass) runs to 1.4 loops or more, the track's
 * closest approach to the point about a loop on (0.7 to 1.3 loops), going the same way, within 80 m.
 */
private fun fillMissed(p: List<GeoFix>, s: DoubleArray, heading: DoubleArray, c: Int, passes: List<Pass>, loop: Double): List<Pass> {
    val out = ArrayList<Pass>()
    val ends = passes.drop(1).map { it.at } + s.last()
    for ((k, pass) in passes.withIndex()) {
        out += pass
        var from = pass
        while (ends[k] - from.at >= 1.4 * loop) {
            val window = p.indices.filter { s[it] - from.at in 0.7 * loop..1.3 * loop && s[it] < ends[k] }
            val i = window.minByOrNull { metresBetween(p[it], p[c]) } ?: break
            if (metresBetween(p[i], p[c]) > 80 || abs(angleBetween(heading[i], heading[c])) > 60) break
            var bestD = Double.MAX_VALUE; var fill: Pass? = null
            for (q in max(0, i - 1) until min(p.lastIndex, i + 1)) {
                val (f, d) = closest(p[q], p[q + 1], p[c])
                if (d < bestD) { bestD = d; fill = Pass(p[q].t + f * (p[q + 1].t - p[q].t), s[q] + f * (s[q + 1] - s[q])) }
            }
            out += fill!!; from = fill
        }
    }
    return out
}

/** A pass of a lap point: its time (seconds) and how far along the track (metres). */
private data class Pass(val t: Double, val at: Double)

private fun passesOf(p: List<GeoFix>, s: DoubleArray, heading: DoubleArray, c: Int, radiusM: Double, minLapM: Double): List<Pass> {
    val anchor = p[c]
    val out = ArrayList<Pass>()
    var i = 0
    while (i < p.size) {
        if (metresBetween(p[i], anchor) >= radiusM) { i++; continue }
        // One visit: every fix in a row within the radius. Its pass is the closest approach to the point along the line.
        var j = i
        while (j + 1 < p.size && metresBetween(p[j + 1], anchor) < radiusM) j++
        var bestD = Double.MAX_VALUE; var bestT = p[i].t.toDouble(); var bestAt = s[i]; var bestK = i
        for (k in max(0, i - 1) until min(p.lastIndex, j + 1)) {
            val (f, d) = closest(p[k], p[k + 1], anchor)
            if (d < bestD) { bestD = d; bestT = p[k].t + f * (p[k + 1].t - p[k].t); bestAt = s[k] + f * (s[k + 1] - s[k]); bestK = k }
        }
        val sameWay = abs(angleBetween(heading[bestK], heading[c])) <= 60
        if (sameWay && (out.isEmpty() || bestAt - out.last().at >= minLapM)) out += Pass(bestT, bestAt)
        i = j + 1
    }
    return out
}

/** Where along a→b the point [x] is closest (0..1), and how far it is then, in metres on a local flat plane. */
private fun closest(a: GeoFix, b: GeoFix, x: GeoFix): Pair<Double, Double> {
    val k = PI / 180 * 6_371_000
    val cosLat = cos(x.lat * PI / 180)
    val ax = (a.lon - x.lon) * k * cosLat; val ay = (a.lat - x.lat) * k
    val bx = (b.lon - x.lon) * k * cosLat; val by = (b.lat - x.lat) * k
    val dx = bx - ax; val dy = by - ay
    val len2 = dx * dx + dy * dy
    val f = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
    return f to hypot(ax + f * dx, ay + f * dy)
}

private fun bearing(a: GeoFix, b: GeoFix): Double {
    val x = (b.lon - a.lon) * cos((a.lat + b.lat) / 2 * PI / 180); val y = b.lat - a.lat
    return atan2(x, y) * 180 / PI
}

private fun angleBetween(a: Double, b: Double): Double = ((a - b + 540) % 360) - 180

/**
 * The track with each fix averaged with its neighbours within 2 seconds, which takes out a GPS fix's second-to-second
 * jitter (a few metres) without cutting corners on a path (at a jog, 2 seconds is under 6 metres).
 */
fun smoothTrack(track: List<GeoFix>): List<GeoFix> {
    val t = track.sortedBy { it.t }.distinctBy { it.t }
    return t.indices.map { i ->
        var lat = 0.0; var lon = 0.0; var n = 0
        var k = i
        while (k >= 0 && t[i].t - t[k].t <= 2) { lat += t[k].lat; lon += t[k].lon; n++; k-- }
        k = i + 1
        while (k < t.size && t[k].t - t[i].t <= 2) { lat += t[k].lat; lon += t[k].lon; n++; k++ }
        GeoFix(t[i].t, lat / n, lon / n)
    }
}

private fun pathLengths(p: List<GeoFix>): DoubleArray {
    val s = DoubleArray(p.size)
    for (i in 1 until p.size) s[i] = s[i - 1] + metresBetween(p[i - 1], p[i])
    return s
}

/**
 * Metres covered in each second 0 until [n] along the (smoothed) track, from its length interpolated at whole seconds.
 * Seconds with no fix within 10 seconds either side get NaN: the track can't say how far they went.
 */
fun trackMetresPerSecond(track: List<GeoFix>, n: Int): DoubleArray {
    val p = smoothTrack(track)
    val out = DoubleArray(n) { Double.NaN }
    if (p.size < 2) return out
    val s = pathLengths(p)
    fun at(t: Double): Double? {
        var lo = 0; var hi = p.lastIndex
        if (t < p[0].t || t > p[hi].t) return null
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (p[m].t <= t) lo = m else hi = m }
        if (p[hi].t - p[lo].t > 20) return null
        val f = if (p[hi].t == p[lo].t) 0.0 else (t - p[lo].t) / (p[hi].t - p[lo].t)
        return s[lo] + f * (s[hi] - s[lo])
    }
    for (t in 0 until n) { val a = at(t.toDouble()); val b = at(t + 1.0); if (a != null && b != null) out[t] = b - a }
    return out
}

/** A stretch of running, walking or standing within a lap: seconds from the start, its distance and steps where known. */
data class LapSegment(val start: Double, val end: Double, val gait: Gait, val distanceM: Double?, val cadenceSpm: Int?, val avgHr: Int?) {
    val seconds: Double get() = end - start
    val paceSPerKm: Double? get() = distanceM?.takeIf { it >= 20 }?.let { seconds * 1000 / it }
}

/**
 * One lap (or the part-lap before the first pass, [index] 0, or after the last, [partial]), from [start] to [end] in
 * seconds from the session's start.
 *
 * [bestPaceSPerKm] is the fastest 30 seconds within it; [steps] adds up the cadence; [strideM] is the distance over
 * the steps (a step, not a pair); [metresPerBeat] the distance over the heartbeats, which rises as fitness does and
 * falls as you tire at the same pace.
 */
data class Lap(
    val index: Int, val start: Double, val end: Double, val partial: Boolean, val distanceM: Double?,
    val runS: Double, val walkS: Double, val stillS: Double, val runM: Double?, val walkM: Double?,
    val bestPaceSPerKm: Double?, val steps: Int?, val cadenceSpm: Int?, val avgHr: Int?, val maxHr: Int?,
    val segments: List<LapSegment>,
) {
    val seconds: Double get() = end - start
    val paceSPerKm: Double? get() = distanceM?.takeIf { it >= 50 }?.let { seconds * 1000 / it }
    val runPaceSPerKm: Double? get() = runM?.takeIf { it >= 30 }?.let { runS * 1000 / it }
    val walkPaceSPerKm: Double? get() = walkM?.takeIf { it >= 30 }?.let { walkS * 1000 / it }
    val strideM: Double? get() = steps?.takeIf { it >= 30 }?.let { s -> distanceM?.let { it / s } }
    val metresPerBeat: Double? get() = avgHr?.takeIf { it > 0 }?.let { hr -> distanceM?.let { it / (seconds * hr / 60) } }
}

/**
 * The session's laps and how they went. [laps] holds whole laps; [lead] the stretch before the first pass and [tail]
 * the part-lap after the last, when they are long enough to count (20 seconds and 30 metres).
 *
 * [fadePct] is how much slower the last third of laps went than the first third (negative: faster), by average lap
 * time. [spreadPct] is the laps' times' coefficient of variation: under 3% is very even. [runShareFirst] and
 * [runShareLast] are how much of the first and last whole lap was running.
 */
data class LapSummary(
    val line: LapLine, val laps: List<Lap>, val lead: Lap?, val tail: Lap?,
    val fastest: Int?, val slowest: Int?, val fadePct: Double?, val spreadPct: Double?,
    val runShareFirst: Double?, val runShareLast: Double?,
)

/**
 * Laps with their stats, from per-second arrays over the session's [n] seconds: [metres] covered (NaN where unknown),
 * [gait], [cadence] (steps a minute, NaN where none) and [hr] (bpm, 0 where none). [gaitBouts], when given, are the
 * running and walking bouts already found for the session, so laps agree with the rest of the page; without them each
 * second's own [gait] is used.
 */
fun lapSummary(line: LapLine, n: Int, metres: DoubleArray, gait: Array<Gait>, cadence: DoubleArray, hr: IntArray,
               gaitBouts: List<GaitBout>? = null): LapSummary {
    // Each second's gait: from the bouts when given (the rest of the page's view), else as labelled.
    val kind = if (gaitBouts == null) gait.copyOf() else Array(n) { Gait.STILL }.also { k ->
        for (b in gaitBouts) for (t in max(0, b.start) until min(n, b.end)) k[t] = if (b.running) Gait.RUN else Gait.WALK
    }
    fun frac(a: Double, b: Double, t: Int): Double = (min(b, t + 1.0) - max(a, t.toDouble())).coerceAtLeast(0.0)
    fun seconds(a: Double, b: Double) = (floor(a).toInt().coerceAtLeast(0) until min(n, ceil(b).toInt()))
    fun distance(a: Double, b: Double, only: Gait? = null): Double? {
        var d = 0.0; var known = 0.0; var all = 0.0
        for (t in seconds(a, b)) {
            if (only != null && kind[t] != only) continue
            val f = frac(a, b, t); all += f
            if (metres[t].isFinite()) { d += metres[t] * f; known += f }
        }
        // Fill short gaps at the stretch's own pace; most of it unknown means no distance.
        return if (all == 0.0) 0.0 else if (known < 0.8 * all) null else d * all / known
    }
    fun segmentsOf(a: Double, b: Double): List<LapSegment> {
        val out = ArrayList<LapSegment>()
        var segStart = a; var cur: Gait? = null
        fun close(end: Double) {
            val g = cur ?: return
            if (end - segStart <= 0) return
            val secs = seconds(segStart, end)
            val cad = secs.map { cadence.getOrElse(it) { Double.NaN } }.filter { it.isFinite() && it > 0 }
            val h = secs.map { hr.getOrElse(it) { 0 } }.filter { it > 0 }
            out += LapSegment(segStart, end, g, distance(segStart, end, null),
                cad.takeIf { it.isNotEmpty() }?.average()?.roundToInt(), h.takeIf { it.isNotEmpty() }?.average()?.roundToInt())
        }
        for (t in seconds(a, b)) {
            val g = if (kind[t] == Gait.NONE) Gait.STILL else kind[t]
            if (g != cur) { close(max(a, t.toDouble())); segStart = max(a, t.toDouble()); cur = g }
        }
        close(b)
        return out
    }
    fun lap(index: Int, a: Double, b: Double, partial: Boolean): Lap {
        val segs = segmentsOf(a, b)
        val secs = seconds(a, b)
        val cad = secs.map { cadence.getOrElse(it) { Double.NaN } }
        val steps = cad.indices.sumOf { i -> val c = cad[i]; if (c.isFinite() && c > 0) c / 60 * frac(a, b, secs.first + i) else 0.0 }
        val moving = secs.filter { kind[it] == Gait.RUN || kind[it] == Gait.WALK }.map { cadence.getOrElse(it) { Double.NaN } }.filter { it.isFinite() && it > 0 }
        val h = secs.map { hr.getOrElse(it) { 0 } }.filter { it > 0 }
        // The fastest 30 seconds: the most metres in any 30 whole seconds in a row with no unknown second.
        var best: Double? = null
        val whole = secs.filter { it >= a && it + 1 <= b }
        if (whole.size >= 30) for (k in 0..whole.size - 30) {
            var m = 0.0; var ok = true
            for (q in k until k + 30) { val v = metres[whole[q]]; if (!v.isFinite()) { ok = false; break }; m += v }
            if (ok && m > 0) best = min(best ?: Double.MAX_VALUE, 30 * 1000 / m)
        }
        fun gaitS(g: Gait) = segs.filter { it.gait == g }.sumOf { it.seconds }
        fun gaitM(g: Gait) = segs.filter { it.gait == g }.let { l -> if (l.isEmpty()) 0.0 else if (l.any { it.distanceM == null }) null else l.sumOf { it.distanceM!! } }
        return Lap(index, a, b, partial, distance(a, b), gaitS(Gait.RUN), gaitS(Gait.WALK), gaitS(Gait.STILL), gaitM(Gait.RUN), gaitM(Gait.WALK),
            best, steps.takeIf { it > 0 }?.roundToInt(), moving.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            h.takeIf { it.isNotEmpty() }?.average()?.roundToInt(), h.maxOrNull(), segs)
    }
    val laps = line.passes.zipWithNext().mapIndexed { i, (a, b) -> lap(i + 1, a, b, false) }
    fun counts(l: Lap) = l.seconds >= 20 && (l.distanceM ?: 0.0) >= 30
    val lead = lap(0, 0.0, line.passes.first(), true).takeIf(::counts)
    val tail = lap(laps.size + 1, line.passes.last(), n.toDouble(), true).takeIf(::counts)
    val times = laps.map { it.seconds }
    val third = laps.size / 3
    val fade = if (third >= 1) (times.takeLast(third).average() / times.take(third).average() - 1) * 100 else null
    val spread = if (times.size >= 2) { val m = times.average(); sqrt(times.sumOf { (it - m).pow(2) } / (times.size - 1)) / m * 100 } else null
    fun share(l: Lap?) = l?.let { it.runS / it.seconds }
    return LapSummary(line, laps, lead, tail, laps.minByOrNull { it.seconds }?.index, laps.maxByOrNull { it.seconds }?.index,
        fade, spread, share(laps.firstOrNull()), share(laps.lastOrNull()))
}

/**
 * The bouts with their distances from [metres] covered each second (a GPS track that held): each bout's metres, its gaps
 * filled at its own pace, or null when under 80% of it is known.
 */
fun GaitSummary.withMetres(metres: DoubleArray): GaitSummary {
    val out = bouts.map { b ->
        val secs = (max(0, b.start) until min(b.end, metres.size))
        val known = secs.filter { metres[it].isFinite() }
        b.copy(distanceM = if (b.seconds == 0 || known.size < 0.8 * b.seconds) null else known.sumOf { metres[it] } * b.seconds / known.size)
    }
    fun total(running: Boolean) = out.filter { it.running == running }.mapNotNull { it.distanceM }.takeIf { it.isNotEmpty() }?.sum()
    return copy(bouts = out, runDistanceM = total(true), walkDistanceM = total(false))
}
