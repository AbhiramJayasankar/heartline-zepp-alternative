package dev.heartline.protocol

data class WorkoutValuePoint(val t: Int, val value: Double)
data class WorkoutDetailRecord(val type: Int, val t: Int, val payload: List<Int>)
data class WorkoutDetailsExt(
    val hr: List<TrackPoint> = emptyList(), val altitudeM: List<WorkoutValuePoint> = emptyList(),
    val speedMs: List<WorkoutValuePoint> = emptyList(), val cadence: List<WorkoutValuePoint> = emptyList(),
    val steps: List<WorkoutValuePoint> = emptyList(), val distanceM: List<WorkoutValuePoint> = emptyList(),
    val pauses: List<Int> = emptyList(), val resumes: List<Int> = emptyList(),
    val undecoded: List<WorkoutDetailRecord> = emptyList(), val trailingBytes: Int = 0,
)

/** Gadgetbridge HuamiActivityDetailsParser.parse/consumeGPSAndUpdateBaseLocation/consumeHeartRate:
 * eight-byte records: type u8, wrapping offset u8, payload[6]. GPS has signed LE16 lon/lat/alt deltas.
 * consumeSpeed4/5/6 and consumeSwimming only skip their payloads upstream; do not invent their units.
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
    return WorkoutDetailsExt(hr = parseWorkoutDetails(data), altitudeM = fixedAltitudes,
        pauses = pauses, resumes = resumes, undecoded = undecoded, trailingBytes = data.size % 8)
}
