package dev.heartline.app

import dev.heartline.data.model.RawFetch
import dev.heartline.protocol.*
import java.util.Locale
import kotlin.math.ceil

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

internal fun workoutMaxHr(profile: ProfileUi): Int = profile.maxHr?.takeIf { it > 0 }
    ?: profile.age?.takeIf { it in 1..120 }?.let { 220 - it } ?: 190

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

/** Evenly spaced original points, including both endpoints; no fabricated/interpolated values. */
internal fun downsampleWorkoutSeries(points: List<SeriesPoint>, limit: Int = 600): List<SeriesPoint> {
    require(limit >= 2)
    if (points.size <= limit) return points
    return (0 until limit).map { points[(it.toLong() * (points.size - 1) / (limit - 1)).toInt()] }
}

internal fun WorkoutUi.withWorkoutDetail(raw: StoredWorkoutDetail?, profile: ProfileUi): WorkoutUi {
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
                else String.format(Locale.ROOT, "%.3f", value.toDouble()).trimEnd('0').trimEnd('.')
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
        add("Training load" to "Edwards TRIMP (zone minutes × zone number)")
    }
    return copy(route = raw?.route ?: route, detail = WorkoutDetailUi(
        activeS = s?.activeS,
        avgPaceSPerKm = s?.avgPaceSPerKm?.toDouble() ?: s?.avgPaceSPerM?.times(1000),
        bestPaceSPerKm = s?.maxPaceSPerM?.times(1000), avgSpeedKmh = s?.avgSpeedMs.positive()?.times(3.6),
        maxSpeedKmh = s?.maxSpeedMs.positive()?.times(3.6), avgCadence = s?.avgCadence, maxCadence = s?.maxCadence,
        avgStrideCm = s?.avgStrideCm, ascentM = s?.ascentM, descentM = s?.descentM,
        minAltM = s?.minAltitudeM, maxAltM = s?.maxAltitudeM,
        minHr = hr.filter { it.hr in 1..254 }.minOfOrNull { it.hr }, load = hr.takeIf { it.isNotEmpty() }?.let { edwardsTrimp(zones) },
        zones = zones, speedKmh = d?.speedMs.orEmpty().series(3.6), cadence = d?.cadence.orEmpty().series(),
        altitude = d?.altitudeM.orEmpty().series(), extras = extras,
    ))
}
