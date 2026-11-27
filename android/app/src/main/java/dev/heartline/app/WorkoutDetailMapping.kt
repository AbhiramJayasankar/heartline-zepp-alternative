package dev.heartline.app

import dev.heartline.data.model.RawFetch
import dev.heartline.protocol.*
import java.util.Locale
import kotlin.math.ceil
import dev.heartline.analytics.withMetres
import kotlin.math.roundToInt

internal data class StoredWorkoutDetail(
    val summary: WorkoutSummaryExt?, val details: WorkoutDetailsExt?, val route: List<RoutePoint>,
)

/** Join on the summary's workout start, not the fetch request cursor; newest capture wins. */
internal fun storedWorkoutDetails(fetches: List<RawFetch>): Map<Long, StoredWorkoutDetail> {
    val summaries = fetches.filter { it.type == "workouts" && it.data != null }.sortedBy { it.ts }
        .mapNotNull { f -> parseWorkoutSummary(f.data!!)?.let { it.start to f.data } }.toMap()
    val details = fetches.filter { it.type == "workout_details" && it.start != null && it.data != null }.sortedBy { it.ts }
        .associate { it.start!!.toLong() to it.data!! }
    val routes = storedWorkoutRoutes(fetches)
    return (summaries.keys + details.keys).associateWith { start ->
        val summary = summaries[start]
        StoredWorkoutDetail(summary?.let(::parseWorkoutSummaryExt), details[start]?.let { parseWorkoutDetailsExt(it, summary) }, routes[start].orEmpty())
    }
}

internal fun workoutMaxHr(profile: ProfileUi): Int = profileMaxHr(profile.maxHr, profile.age) ?: 190

/** Maximum heart rate everywhere in the app: the profile's, else 208 - 0.7 x age (Tanaka), as the daily load uses. */
fun profileMaxHr(maxHr: Int?, age: Int?): Int? = maxHr?.takeIf { it in 80..240 }
    ?: age?.takeIf { it in 1..120 }?.let { kotlin.math.round(208 - 0.7 * it).toInt() }

/** HR records are change samples: hold each value until the next record, clipped to the workout.
 * Do not backfill the unobserved interval before the first sample. Last sample wins at duplicate times.
 */
internal fun workoutZones(
    track: List<TrackPoint>, durationS: Int, maxHr: Int, watchSeconds: List<Int>? = null,
    pauses: List<Int> = emptyList(), resumes: List<Int> = emptyList(),
): List<ZoneTimeUi> {
    require(maxHr > 0)
    val bounds = (5..9).map { ceil(maxHr * it / 10.0).toInt() }
    val seconds = IntArray(5)
    if (watchSeconds != null && watchSeconds.size == 5 && watchSeconds.all { it >= 0 }) {
        watchSeconds.forEachIndexed { index, value -> seconds[index] = value }
    } else {
        val activeSpans = buildList {
            var active = true
            var start = 0
            val events = (pauses.map { it to false } + resumes.map { it to true })
                .filter { it.first in 0..durationS }.sortedBy { it.first }
            for ((time, resumed) in events) {
                if (active && !resumed) add(start to time)
                if (!active && resumed) start = time
                active = resumed
            }
            if (active) add(start to durationS)
        }
        val points = track.filter { it.t in 0..durationS }.associateBy { it.t }.values.sortedBy { it.t }
        points.forEachIndexed { index, p ->
            val end = points.getOrNull(index + 1)?.t ?: durationS
            val zone = if (p.hr in 1..254) bounds.indexOfLast { p.hr >= it } else -1
            if (zone >= 0) seconds[zone] += activeSpans.sumOf { (start, stop) ->
                (minOf(end, stop) - maxOf(p.t, start)).coerceAtLeast(0)
            }
        }
    }
    val names = listOf("Warm-up", "Fat burn", "Aerobic", "Anaerobic", "Max")
    return names.indices.map { i -> ZoneTimeUi(i + 1, names[i], bounds[i], bounds.getOrNull(i + 1), seconds[i]) }
}

internal fun edwardsTrimp(zones: List<ZoneTimeUi>): Double = zones.sumOf { it.seconds * it.zone.toDouble() } / 60.0

/**
 * Banister TRIMP over the HR track (each record held until the next, as for zones), on the scale of the daily load and
 * strain: r = (hr - rest) / (max - rest) from half of max up, r x a x e^(b r) a minute.
 */
internal fun banisterLoad(track: List<TrackPoint>, durationS: Int, maxHr: Int, rest: Double, sex: String?,
                          pauses: List<Int> = emptyList(), resumes: List<Int> = emptyList()): Double {
    val a = if (sex == "female") 0.86 else 0.64
    val b = if (sex == "female") 1.67 else 1.92
    val spans = activeSpans(durationS, pauses, resumes)
    val points = track.filter { it.t in 0..durationS }.associateBy { it.t }.values.sortedBy { it.t }
    return points.withIndex().sumOf { (i, p) ->
        val end = points.getOrNull(i + 1)?.t ?: durationS
        val dt = spans.sumOf { (s, e) -> (minOf(end, e) - maxOf(p.t, s)).coerceAtLeast(0) }
        if (p.hr !in 1..254 || p.hr < 0.5 * maxHr || maxHr <= rest) 0.0
        else ((p.hr - rest) / (maxHr - rest)).coerceIn(0.0, 1.0).let { r -> r * a * kotlin.math.exp(b * r) } * dt / 60.0
    }
}

/** The stretches between the workout's pauses and resumes, in seconds from its start. */
private fun activeSpans(durationS: Int, pauses: List<Int>, resumes: List<Int>): List<Pair<Int, Int>> = buildList {
    var active = true
    var start = 0
    for ((time, resumed) in (pauses.map { it to false } + resumes.map { it to true }).filter { it.first in 0..durationS }.sortedBy { it.first }) {
        if (active && !resumed) add(start to time)
        if (!active && resumed) start = time
        active = resumed
    }
    if (active) add(start to durationS)
}

/** Evenly spaced original points, including both endpoints; no fabricated/interpolated values. */
internal fun downsampleWorkoutSeries(points: List<SeriesPoint>, limit: Int = 600): List<SeriesPoint> {
    require(limit >= 2)
    if (points.size <= limit) return points
    return (0 until limit).map { points[(it.toLong() * (points.size - 1) / (limit - 1)).toInt()] }
}

/**
 * Laps of a loop in a run or walk's track. Each second's metres come from the phone's track when there is one, else
 * from the bouts' distances by steps when the watch's track wandered (its passes of the lap point still mark the laps,
 * but its length doesn't hold), else from the watch's track.
 */
private fun workoutLoop(track: List<dev.heartline.analytics.GeoFix>?, phoneMetres: DoubleArray?, wandered: Boolean,
                        gait: dev.heartline.analytics.GaitSummary?, d: WorkoutDetailsExt?, hr: List<TrackPoint>, n: Int): dev.heartline.analytics.LapSummary? {
    if (track == null || track.size < 60 || gait == null || d == null || n <= 0) return null
    val line = dev.heartline.analytics.findLapLine(track) ?: return null
    val metres = phoneMetres ?: if (wandered) DoubleArray(n).also { m ->
        for (b in gait.bouts) { val per = (b.distanceM ?: continue) / b.seconds; for (t in b.start until minOf(n, b.end)) m[t] = per }
    } else dev.heartline.analytics.trackMetresPerSecond(track, n)
    val cadence = dev.heartline.analytics.perSecond(d.cadence.map { it.t to it.value }, n, 3)
    val beats = dev.heartline.analytics.perSecond(hr.filter { it.hr in 1..254 }.map { it.t to it.hr.toDouble() }, n, 30)
        .map { if (it.isFinite()) it.roundToInt() else 0 }.toIntArray()
    return dev.heartline.analytics.lapSummary(line, n, metres, dev.heartline.analytics.gaitFromCadence(cadence), cadence, beats, gait.bouts)
}

internal fun List<RoutePoint>.geoFixes() = map { dev.heartline.analytics.GeoFix(it.t, it.lat, it.lon) }

/** The workout's gait from its stored details; null for workouts that aren't on foot or lack the cadence. */
private fun StoredWorkoutDetail.gait(type: String, hr: List<TrackPoint>, durationS: Int) =
    details?.takeIf { it.cadence.size >= 60 && gaitWorkout(type) }?.let { d -> workoutGait(d.cadence, d.speedMs, d.strideCm, hr, durationS) }

private fun WorkoutDetailsExt.speedPoints() = speedMs.map { it.t to it.value }

/** The workout's GPS distance check against its own speed and steps (see [dev.heartline.analytics.checkGpsDistance]). */
internal fun StoredWorkoutDetail.gpsCheck(type: String, durationS: Int, watchM: Int?, totalSteps: Int?, lengths: dev.heartline.analytics.StepLengths) =
    details?.let { d -> dev.heartline.analytics.checkGpsDistance(watchM, d.speedPoints(), route.geoFixes(), gait(type, d.hr, durationS), lengths, totalSteps) }

/** Step lengths walking and running, from the runs and walks whose GPS held. */
internal fun learnedStepLengths(all: Map<Long, StoredWorkoutDetail>): dev.heartline.analytics.StepLengths =
    dev.heartline.analytics.learnStepLengths(all.values.mapNotNull { w ->
        val d = w.details ?: return@mapNotNull null
        val type = w.summary?.let { s -> WORKOUT_TYPES[s.typeCode] } ?: return@mapNotNull null
        if (w.route.size < 60) return@mapNotNull null
        w.gait(type, d.hr, (d.cadence.maxOfOrNull { it.t } ?: 0) + 1)?.let { it to w.route.geoFixes() }
    })

/**
 * The workout with everything decoded from its stored detail. [phone] is the phone's GPS track from a capture that ran
 * alongside ([RoutePoint.t] from the workout's start): when it covers the workout, its route, distance and laps are used,
 * as it keeps to the path where the watch's can wander.
 */
internal fun WorkoutUi.withWorkoutDetail(raw: StoredWorkoutDetail?, profile: ProfileUi,
                                         steps: dev.heartline.analytics.StepLengths = dev.heartline.analytics.StepLengths(),
                                         phone: List<RoutePoint> = emptyList()): WorkoutUi {
    val s = raw?.summary
    val d = raw?.details
    val hr = d?.hr?.takeIf { it.isNotEmpty() } ?: track.map { TrackPoint((it.ts - start).toInt(), it.bpm) }
    val zones = workoutZones(hr, durationS, workoutMaxHr(profile), pauses = d?.pauses.orEmpty(), resumes = d?.resumes.orEmpty())
    fun Double?.positive() = this?.takeIf { it > 0 && it.isFinite() }
    fun List<WorkoutValuePoint>.series(scale: Double = 1.0) = downsampleWorkoutSeries(
        filter { it.value.isFinite() }.map { SeriesPoint(it.t.toDouble(), it.value * scale) })
    val extras = buildList {
        fun addValue(label: String, value: Number?, unit: String = "") {
            if (value == null || !value.toDouble().isFinite()) return
            val number = if (value is Int || value is Long) value.toString()
                else String.format(Locale.ROOT, "%.2f", value.toDouble()).trimEnd('0').trimEnd('.')
            add(label to (number + if (unit.isEmpty()) "" else " $unit"))
        }
        if (s != null) {
            addValue("Summary version", s.version)
            addValue("Activity type code", s.typeCode)
            addValue("Energy", s.caloriesKcal, "kcal")
            addValue("Distance", s.distanceM, "m")
            addValue("Minimum speed", s.minSpeedMs.positive()?.times(3.6), "km/h")
            addValue("Slowest pace", s.minPaceSPerM?.times(1000), "s/km")
            addValue("Average pace (float)", s.avgPaceSPerM?.times(1000), "s/km")
            addValue("Average pace (summary)", s.avgPaceSPerKm, "s/km")
            addValue("Minimum cadence", s.minCadence, "spm")
            addValue("Maximum stride", s.maxStrideCm, "cm")
            addValue("Minimum stride", s.minStrideCm, "cm")
            addValue("Average stride (float)", s.avgStride2Cm, "cm")
            addValue("Secondary distance", s.secondaryDistanceM, "m")
            addValue("Average altitude", s.avgAltitudeM, "m")
            addValue("Starting altitude", s.baseAltitudeM, "m")
            // Only base coordinate scaling is documented by AbstractHuamiActivityDetailsParser.
            if (s.baseLongitude != 0 || s.baseLatitude != 0) {
                addValue("Starting longitude", s.baseLongitude / 3_000_000.0, "°")
                addValue("Starting latitude", s.baseLatitude / 3_000_000.0, "°")
            }
            fun bound(value: Int) = value.takeUnless { it == Int.MIN_VALUE || it == Int.MAX_VALUE }
            addValue("Maximum latitude (Huami units)", bound(s.maxLatitude))
            addValue("Minimum latitude (Huami units)", bound(s.minLatitude))
            addValue("Maximum longitude (Huami units)", bound(s.maxLongitude))
            addValue("Minimum longitude (Huami units)", bound(s.minLongitude))
            s.terrain?.let {
                addValue("Uphill distance", it.ascentDistanceM, "m"); addValue("Uphill time", it.ascentS, "s")
                addValue("Downhill distance", it.descentDistanceM, "m"); addValue("Downhill time", it.descentS, "s")
                addValue("Flat distance", it.flatDistanceM, "m"); addValue("Flat time", it.flatS, "s")
            }
            s.swimming?.let {
                addValue("Average stroke distance", it.avgStrokeDistanceM, "m")
                addValue("Average stroke rate", it.avgStrokesPerS?.times(60), "strokes/min")
                addValue("Average lap pace", it.avgLapPaceS, "s")
                addValue("Strokes", it.strokes); addValue("SWOLF", it.swolf); addValue("Laps", it.laps)
                add("Swim style" to when (it.styleCode) { 1 -> "Breaststroke"; 2 -> "Freestyle"; 3 -> "Backstroke"; 4 -> "Medley"; else -> "Unknown (${it.styleCode})" })
            }
        }
        if (d != null) {
            addValue("Pause events", d.pauses.size); addValue("Resume events", d.resumes.size)
            if (d.undecoded.isNotEmpty()) add("Undecoded detail records" to d.undecoded.groupingBy { it.type }.eachCount().toSortedMap()
                .entries.joinToString { "type ${it.key}: ${it.value}" })
            if (d.trailingBytes > 0) addValue("Incomplete detail bytes", d.trailingBytes)
            d.steps.lastOrNull()?.let { addValue("Last recorded step count", it.value, "steps") }
            d.distanceM.lastOrNull()?.let { addValue("Last recorded distance", it.value, "m") }
        }
        add("Heart-rate zones" to "Estimated from HR track, excluding recorded pauses; max HR ${workoutMaxHr(profile)} bpm")
        add("Minimum heart rate" to "Estimated from HR track")
        add("Training load" to if (profile.restingHr != null) "Banister TRIMP, the same scale as the daily load and strain" else "Needs a resting heart rate (the daily figures supply one)")
    }
    val watchGait = raw?.gait(type, hr, durationS)
    // A GPS track that wandered: the watch's distance (the track's length), pace, best pace, top speed and kilometre
    // marks all follow the wander, so distance and pace come from your steps instead (or, before step lengths are
    // learnt, the watch's own speed).
    val phoneTrack = phone.takeIf { p -> p.size >= 60 && p.last().t - p.first().t >= 0.8 * durationS }?.geoFixes()
    val phoneMetres = phoneTrack?.let { dev.heartline.analytics.trackMetresPerSecond(it, durationS) }
    val phoneM = phoneMetres?.filter { it.isFinite() }?.sum()?.takeIf { it >= 100 }
    // A ride has no steps to check its GPS against, and rides fast enough that the wander is small next to the distance.
    val riding = isRideType(type)
    val check = if (phoneM != null || riding) null else raw?.gpsCheck(type, durationS, distanceM, this.steps ?: s?.steps, steps)
    val gps = check?.let { g -> GpsCheckUi(g.wander, g.ratio, distanceM, g.distanceM.roundToInt(), g.fromSteps, steps.walkM, steps.runM) }
    // With the distance from the steps (or the phone), each run and walk has its distance from them too.
    val gait = if (phoneMetres != null && phoneM != null) watchGait?.withMetres(phoneMetres)
        else check?.gait ?: watchGait
    // The distance shown in place of the watch's: the phone's, else the steps' when the watch's track wandered.
    val fixedM = phoneM ?: gps?.distanceM?.toDouble()
    val active = s?.activeS ?: durationS
    return copy(detail = WorkoutDetailUi(
        activeS = s?.activeS,
        avgPaceSPerKm = if (fixedM != null) active * 1000.0 / fixedM else s?.avgPaceSPerKm?.toDouble() ?: s?.avgPaceSPerM?.times(1000),
        bestPaceSPerKm = if (fixedM != null) null else s?.maxPaceSPerM?.times(1000),
        avgSpeedKmh = if (fixedM != null) fixedM * 3.6 / active else s?.avgSpeedMs.positive()?.times(3.6),
        maxSpeedKmh = if (fixedM != null) null else s?.maxSpeedMs.positive()?.times(3.6), avgCadence = s?.avgCadence, maxCadence = s?.maxCadence,
        // The watch's stride is its distance over the steps; with the distance from the steps, so is this.
        avgStrideCm = if (fixedM != null) (this.steps ?: s?.steps)?.takeIf { it > 0 }?.let { (fixedM * 100.0 / it).roundToInt() } else s?.avgStrideCm, ascentM = s?.ascentM, descentM = s?.descentM,
        minAltM = s?.minAltitudeM, maxAltM = s?.maxAltitudeM,
        minHr = hr.filter { it.hr in 1..254 }.minOfOrNull { it.hr }, load = hr.takeIf { it.isNotEmpty() }?.let { h -> profile.restingHr?.let { banisterLoad(h, durationS, workoutMaxHr(profile), it.toDouble(), profile.sex, d?.pauses.orEmpty(), d?.resumes.orEmpty()) } },
        zones = zones, speedKmh = d?.speedMs.orEmpty().series(3.6), cadence = d?.cadence.orEmpty().series(),
        altitude = d?.altitudeM.orEmpty().series(), extras = extras, gait = gait, gps = gps,
        laps = if (fixedM != null) emptyList() else kilometreLaps(d?.kmSplits.orEmpty(), hr, durationS, distanceM),
        loop = workoutLoop(phoneTrack ?: raw?.route?.geoFixes(), phoneMetres, check != null, gait, d, hr, durationS),
        phoneM = phoneM?.roundToInt(),
        ride = if (riding) workoutRide(phoneMetres ?: raw?.route?.takeIf { it.size >= 60 }?.geoFixes()?.let { dev.heartline.analytics.trackMetresPerSecond(it, durationS) },
            hr, d?.altitudeM.orEmpty(), durationS) else null,
    ), route = if (phoneTrack != null) phone else raw?.route ?: route,
        watchRoute = if (phoneTrack != null) raw?.route.orEmpty() else emptyList())
}

/** A watch ride from its per-second track [metres], heart rate held between records, and altitude. */
private fun workoutRide(metres: DoubleArray?, hr: List<TrackPoint>, alt: List<WorkoutValuePoint>, durationS: Int): dev.heartline.analytics.RideSummary? {
    if (metres == null || durationS < 60) return null
    val h = hr.filter { it.hr in 1..254 }.sortedBy { it.t }
    val bpm = IntArray(durationS).also { a -> var j = 0; var cur = 0
        for (t in 0 until durationS) { while (j < h.size && h[j].t <= t) { cur = h[j].hr; j++ }; a[t] = cur } }
    val altM = DoubleArray(durationS) { Double.NaN }.also { a -> alt.forEach { p -> if (p.t in a.indices && p.value.isFinite()) a[p.t] = p.value } }
    return dev.heartline.analytics.rideSummary(durationS, metres, bpm, altM)
}

/** Workouts on foot, where the cadence tells running from walking. */
internal fun gaitWorkout(type: String): Boolean = type.lowercase().let { t ->
    listOf("run", "walk", "hik", "trail", "treadmill").any { it in t } }

/** Bouts of running and walking from the watch's per-second cadence, speed and stride, with the HR track held between records. */
internal fun workoutGait(cadence: List<WorkoutValuePoint>, speedMs: List<WorkoutValuePoint>, strideCm: List<WorkoutValuePoint>,
                         hr: List<TrackPoint>, durationS: Int): dev.heartline.analytics.GaitSummary? {
    val n = durationS.coerceAtLeast((cadence.maxOfOrNull { it.t } ?: 0) + 1)
    fun held(points: List<WorkoutValuePoint>) = dev.heartline.analytics.perSecond(points.map { it.t to it.value }, n, 3)
    val c = held(cadence)
    val beats = dev.heartline.analytics.perSecond(hr.filter { it.hr in 1..254 }.map { it.t to it.hr.toDouble() }, n, 30)
        .map { if (it.isFinite()) it.toInt() else 0 }.toIntArray()
    return dev.heartline.analytics.gaitSummary(dev.heartline.analytics.gaitFromCadence(c), beats, c,
        speedMs.takeIf { it.isNotEmpty() }?.let(::held), strideCm.takeIf { it.isNotEmpty() }?.let(::held))
}

/** One lap a kilometre, from the watch's own kilometre marks, and the part-kilometre after the last one. */
internal fun kilometreLaps(splits: List<WorkoutSplit>, hr: List<TrackPoint>, durationS: Int, distanceM: Int?): List<LapUi> {
    if (splits.isEmpty()) return emptyList()
    fun avgHr(a: Int, b: Int): Int? {
        // Each record holds until the next: weight by the seconds it covers inside [a, b).
        val pts = hr.filter { it.hr in 1..254 }.sortedBy { it.t }
        var sum = 0.0; var secs = 0
        pts.forEachIndexed { i, p ->
            val end = minOf(b, pts.getOrNull(i + 1)?.t ?: b); val from = maxOf(a, p.t)
            if (end > from) { sum += p.hr.toDouble() * (end - from); secs += end - from }
        }
        return if (secs == 0) null else kotlin.math.round(sum / secs).toInt()
    }
    val laps = splits.mapIndexed { i, s ->
        LapUi(i + 1, s.splitS, 1000, avgHr(splits.getOrNull(i - 1)?.t ?: 0, s.t), s.splitS.toDouble())
    }.toMutableList()
    val rest = distanceM?.minus(1000 * splits.size)
    val restS = durationS - splits.last().t
    if (rest != null && rest >= 100 && rest < 1000 && restS > 0)
        laps += LapUi(splits.size + 1, restS, rest, avgHr(splits.last().t, durationS), restS * 1000.0 / rest)
    return laps
}

/**
 * Metres each second of a watch workout as its page shows them: along the phone's track when one was used, by steps
 * when the watch's track wandered, else along the watch's track. Null without a route.
 */
internal fun WorkoutUi.metresPerSecond(): DoubleArray? {
    if (route.size < 60) return null
    val d = detail
    val wandered = d?.phoneM == null && d?.gps != null
    val g = d?.gait
    // Distance from steps: within the workout, a second in no walk or run is standing, so it covers nothing.
    if (wandered && g != null && d.gps?.fromSteps == true) return DoubleArray(durationS) { 0.0 }.also { m ->
        for (b in g.bouts) { val per = (b.distanceM ?: continue) / b.seconds; for (t in b.start until minOf(durationS, b.end)) m[t] = per }
    }
    if (wandered) return null
    return dev.heartline.analytics.trackMetresPerSecond(route.geoFixes(), durationS)
}

/**
 * A capture with the route and distance of the watch workouts within it, when it has no phone track of its own: each
 * workout's track and metres placed at its time in the capture.
 */
/**
 * The watch workouts within a capture as they stand now, clipped to it, with their corrected distances: a capture is
 * often analysed before the watch hands over its workouts (it holds them back while one is running), so the list it
 * stored can miss some or carry a wandered GPS distance.
 */
internal fun CaptureAnalysisUi.withWatchActivities(workouts: List<WorkoutUi>): CaptureAnalysisUi {
    val end = start + durationS
    val found = workouts.filter { it.start < end && it.start + it.durationS > start }.sortedBy { it.start }.map { w ->
        CaptureActivityUi((w.start - start).toDouble().coerceAtLeast(0.0), (w.start + w.durationS - start).toDouble().coerceAtMost(durationS.toDouble()),
            w.type, w.durationS, w.detail?.phoneM ?: w.detail?.gps?.distanceM ?: w.distanceM, w.steps, w.calories, w.avgHr, w.maxHr)
    }
    return if (found.isEmpty()) this else copy(activities = found)
}

internal fun CaptureAnalysisUi.withWatchTracks(workouts: List<WorkoutUi>): CaptureAnalysisUi {
    val n = durationS
    // The watch's own GPS over the capture, kept beside the phone's track to compare the two.
    fun watchPoints() = workouts.sortedBy { it.start }.filter { it.start < start + n && it.start + it.durationS > start }.flatMap { w ->
        val off = (w.start - start).toInt()
        w.watchRoute.ifEmpty { if (w.detail?.phoneM != null) emptyList() else w.route }.map { it.copy(t = it.t + off) }.filter { it.t in 0 until n }
    }.sortedBy { it.t }.distinctBy { it.t }
    if (route.isNotEmpty()) return copy(watchRoute = watchPoints().takeIf { it.size >= 60 }.orEmpty())
    val metres = DoubleArray(n) { Double.NaN }
    val points = ArrayList<RoutePoint>()
    var steps = false; var phone = false; var any = false
    for (w in workouts.sortedBy { it.start }) {
        if (w.start >= start + n || w.start + w.durationS <= start) continue
        val m = w.metresPerSecond() ?: continue
        val off = (w.start - start).toInt()
        for (t in m.indices) { val ct = t + off; if (ct in 0 until n && m[t].isFinite()) metres[ct] = m[t] }
        points += w.route.map { it.copy(t = it.t + off) }.filter { it.t in 0 until n }
        any = true
        if (w.detail?.phoneM != null) phone = true else if (w.detail?.gps != null) steps = true
    }
    if (!any || points.size < 60) return this
    return copy(route = points.sortedBy { it.t }.distinctBy { it.t }, metres = metres,
        routeSource = when { phone && !steps -> RouteSource.PHONE; steps -> RouteSource.WATCH_STEPS; else -> RouteSource.WATCH })
}
