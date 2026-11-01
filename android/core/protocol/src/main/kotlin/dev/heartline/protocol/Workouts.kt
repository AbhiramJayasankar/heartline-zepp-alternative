package dev.heartline.protocol

data class TrackPoint(val t: Int, val hr: Int)
data class Workout(
    val start: Long, val end: Long, val type: String, val durationS: Int,
    val activeS: Int?, val avgHr: Int?, val maxHr: Int?, val calories: Int?, val steps: Int?, val distanceM: Int?,
    val track: List<TrackPoint>, val version: Int = 0, val typeCode: Int = 0,
    val avgSpeedMs: Double? = null, val avgCadence: Int? = null,
)

val WORKOUT_TYPES: Map<Int, String> = mapOf(
    1 to "Outdoor running", 2 to "Treadmill", 3 to "Walking", 4 to "Cycling", 5 to "Exercise",
    6 to "Swimming", 7 to "Open water swimming", 8 to "Indoor cycling", 9 to "Elliptical trainer",
    10 to "Climbing", 15 to "Hiking", 0x12 to "Soccer", 0x15 to "Jump rope", 0x17 to "Rowing machine",
    0x34 to "Strength training", 0x3C to "Yoga", 0x4E to "Cricket", 0x55 to "Basketball",
    0x59 to "Table tennis", 0x5C to "Badminton",
)

/** health.parse_workout_summary / HuamiActivitySummaryParser, including v516's four-byte extension. */
fun parseWorkoutSummary(data: ByteArray): Workout? {
    if (data.size < 24) return null
    val version = data.u16(0)
    val kind = data.u16(2)
    val start = data.u32(4)
    val end = data.u32(8)
    val basic = Workout(start, end, WORKOUT_TYPES[kind] ?: "Type $kind", (end - start).toInt(),
        null, null, null, null, null, null, emptyList(), version, kind)
    val pos = if (version == 516) 28 else 24
    if (version < 512 || version == 519 || data.size < pos + 116) return basic
    val avgSpeed = data.f32(pos + 60)
    val avgCadence = data.f32(pos + 84)
    return basic.copy(steps = data.u32(pos).toInt(), activeS = data.i32(pos + 4),
        calories = Math.rint(data.f32(pos + 24)).toInt(), distanceM = Math.rint(data.f32(pos + 28)).toInt(),
        avgHr = data.i16(pos + 108).takeIf { it != 0 }, maxHr = data.i16(pos + 114).takeIf { it != 0 },
        avgSpeedMs = avgSpeed.takeIf { it > 0 }?.let { pyRound(it, 2) },
        avgCadence = avgCadence.takeIf { it > 0 }?.let { Math.rint(it * 60).toInt() })
}

/** health.parse_workout_details / HuamiActivityDetailsParser: wrapping offsets and packed HR triples. */
fun parseWorkoutDetails(data: ByteArray): List<TrackPoint> {
    val track = mutableListOf<TrackPoint>()
    var total = 0
    var last = 0
    for (n in 0 until data.size / 8) {
        val i = n * 8
        val off = data.u8(i + 1)
        total += if (last <= off) off - last else off
        last = off
        if (data.u8(i) == 1) {
            if ((i + 3..i + 7).all { data.u8(it) == 0 }) {
                if (data.u8(i + 2) != 0) track.add(TrackPoint(total, data.u8(i + 2)))
            } else {
                for (k in 2..6 step 2) if (data.u8(i + k + 1) != 0)
                    track.add(TrackPoint(data.u8(i + k), data.u8(i + k + 1)))
            }
        }
    }
    return track
}
