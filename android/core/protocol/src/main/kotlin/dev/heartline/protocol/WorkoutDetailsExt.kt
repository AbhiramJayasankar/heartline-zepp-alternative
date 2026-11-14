package dev.heartline.protocol

data class WorkoutValuePoint(val t: Int, val value: Double)
data class WorkoutDetailRecord(val type: Int, val t: Int, val payload: List<Int>)
/** A whole kilometre (or mile) reached at [t]: [splitS] for that one, [totalS] of active time so far. */
data class WorkoutSplit(val t: Int, val splitS: Int, val totalS: Int)
data class WorkoutDetailsExt(
    val hr: List<TrackPoint> = emptyList(), val altitudeM: List<WorkoutValuePoint> = emptyList(),
    val speedMs: List<WorkoutValuePoint> = emptyList(), val cadence: List<WorkoutValuePoint> = emptyList(),
    val steps: List<WorkoutValuePoint> = emptyList(), val distanceM: List<WorkoutValuePoint> = emptyList(),
    val pauses: List<Int> = emptyList(), val resumes: List<Int> = emptyList(),
    /** Stride length per second, cm (type 6). */
    val strideCm: List<WorkoutValuePoint> = emptyList(),
    /** Each whole kilometre: when it was reached and how long it took (type 4). */
    val kmSplits: List<WorkoutSplit> = emptyList(),
    val undecoded: List<WorkoutDetailRecord> = emptyList(), val trailingBytes: Int = 0,
)

/** Gadgetbridge HuamiActivityDetailsParser.parse/consumeGPSAndUpdateBaseLocation/consumeHeartRate:
 * eight-byte records: type u8, wrapping offset u8, payload[6]. GPS has signed LE16 lon/lat/alt deltas.
 * consumeSpeed4/5/6 and consumeSwimming only skip their payloads upstream. Two of them are decoded here from the
 * user's own runs and walks, checked against the watch's summary totals:
 *   type 6, about once a second: pace s/km, cadence steps/min and stride cm as LE16s. Its cadence summed over the
 *     workout gives the summary's step count to 1% (3,790 vs 3,755 on a 36-minute run, 2,395 vs 2,353 on a walk),
 *     5% on a treadmill fixture), and 1000 / pace gives its distance to within 10% (pace 0: stopped or no fix).
 *   type 4, once per kilometre: that kilometre's time and the active time so far, both LE16 seconds. Type 5 is the
 *     same per mile and stays opaque.
 * Existing parseWorkoutDetails and parseWorkoutRoute remain unchanged for compatibility.
 */
fun parseWorkoutDetailsExt(data: ByteArray, summary: ByteArray? = null): WorkoutDetailsExt {
    var altitude = summary?.takeIf { it.size >= 24 }?.i32(20)?.takeUnless { it == -20000 }
    var total = 0
    var last = 0
    val altitudes = mutableListOf<WorkoutValuePoint>()
    val pauses = mutableListOf<Int>()
    val resumes = mutableListOf<Int>()
    val undecoded = mutableListOf<WorkoutDetailRecord>()
    val speeds = mutableListOf<WorkoutValuePoint>()
    val cadences = mutableListOf<WorkoutValuePoint>()
    val strides = mutableListOf<WorkoutValuePoint>()
    val splits = mutableListOf<WorkoutSplit>()
    for (i in 0 until data.size - 7 step 8) {
        val offset = data.u8(i + 1)
        total += if (last <= offset) offset - last else offset
        last = offset
        val type = data.u8(i)
        when (type) {
            0 -> {
                altitude = altitude?.plus(data.i16(i + 6))
                altitude?.let { altitudes.add(WorkoutValuePoint(total, it.toDouble())) }
            }
            1 -> Unit // The unchanged HR decoder handles both single samples and packed triples.
            6 -> {
                val pace = data.u16(i + 2); val cadence = data.u16(i + 4); val stride = data.u16(i + 6)
                speeds.add(WorkoutValuePoint(total, if (pace in 60..3600) 1000.0 / pace else 0.0))
                cadences.add(WorkoutValuePoint(total, cadence.takeIf { it <= 300 }?.toDouble() ?: 0.0))
                strides.add(WorkoutValuePoint(total, stride.takeIf { it <= 300 }?.toDouble() ?: 0.0))
            }
            4 -> splits.add(WorkoutSplit(total, data.u16(i + 2), data.u16(i + 4)))
            else -> {
                if (type == 2) pauses.add(total)
                if (type == 3) resumes.add(total)
                undecoded.add(WorkoutDetailRecord(type, total, (i + 2..i + 7).map { data.u8(it) }))
            }
        }
    }
    // Match Gadgetbridge's initial GPS timestamp fixup when a usable route is available.
    val route = summary?.let { parseWorkoutRoute(data, it) }.orEmpty()
    val fixedAltitudes = if (route.size == altitudes.size && route.isNotEmpty())
        altitudes.mapIndexed { index, p -> p.copy(t = route[index].t) } else altitudes
    return WorkoutDetailsExt(hr = parseWorkoutDetails(data), altitudeM = fixedAltitudes, speedMs = speeds, cadence = cadences,
        pauses = pauses, resumes = resumes, undecoded = undecoded, trailingBytes = data.size % 8, strideCm = strides, kmSplits = splits)
}
