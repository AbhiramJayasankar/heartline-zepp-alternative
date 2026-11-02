package dev.heartline.protocol

/** Version 515 only. Nullable values are absent, non-finite or firmware sentinels, never inferred. */
data class WorkoutSummaryExt(
    val version: Int, val typeCode: Int, val start: Long, val end: Long,
    val baseLongitude: Int, val baseLatitude: Int, val baseAltitudeM: Int?,
    val steps: Int, val activeS: Int,
    val maxLatitude: Int, val minLatitude: Int, val maxLongitude: Int, val minLongitude: Int,
    val caloriesKcal: Double?, val distanceM: Double?, val ascentM: Double?, val descentM: Double?,
    val maxAltitudeM: Double?, val minAltitudeM: Double?, val avgAltitudeM: Double?,
    val maxSpeedMs: Double?, val minSpeedMs: Double?, val avgSpeedMs: Double?,
    val minPaceSPerM: Double?, val maxPaceSPerM: Double?, val avgPaceSPerM: Double?,
    val maxCadence: Int?, val minCadence: Int?, val avgCadence: Int?,
    val maxStrideCm: Int?, val minStrideCm: Int?, val avgStride2Cm: Int?,
    val secondaryDistanceM: Double?, val avgHr: Int?, val avgPaceSPerKm: Int?, val avgStrideCm: Int?, val maxHr: Int?,
    val terrain: WorkoutTerrain? = null, val swimming: WorkoutSwimming? = null,
) // v515 has no decoded min HR, total stride, training effect, recovery, load or zone times in Gadgetbridge.

data class WorkoutTerrain(
    val ascentDistanceM: Double?, val ascentS: Int, val descentDistanceM: Double?, val descentS: Int,
    val flatDistanceM: Double?, val flatS: Int,
)

data class WorkoutSwimming(
    val avgStrokeDistanceM: Double?, val avgStrokesPerS: Double?, val avgLapPaceS: Double?,
    val strokes: Int, val swolf: Int, val styleCode: Int, val laps: Int,
)

/** Gadgetbridge devices.huami.HuamiActivitySummaryParser.parseBinaryData, version >=512 branch.
 * All multibyte values LE. GTR 2e v515 has its fixed body at byte 24 (not v516's byte 28).
 * Cadence/stride use Java float multiplication and Math.round, as in Gadgetbridge.
 */
fun parseWorkoutSummaryExt(data: ByteArray): WorkoutSummaryExt? {
    if (data.size < 140 || data.u16(0) != 515) return null
    fun f(offset: Int) = data.f32(offset).takeIf { it.isFinite() }
    fun scaled(offset: Int, scale: Int) = Float.fromBits(data.i32(offset)).takeIf { it.isFinite() }
        ?.let { Math.round(it * scale) }
    fun positiveShort(offset: Int) = data.i16(offset).takeIf { it > 0 }
    fun altitude(offset: Int) = f(offset)?.takeUnless { it == -20000.0 || it == -100000.0 || it == 100000.0 }
    val type = data.u16(2)
    val terrain = if (type in setOf(1, 4, 10, 15) && data.size >= 168) WorkoutTerrain(
        f(144), data.i32(148) / 1000, f(152), data.i32(156) / 1000, f(160), data.i32(164) / 1000,
    ) else null
    val swimming = if (type in setOf(6, 7) && data.size >= 190) WorkoutSwimming(
        f(140), f(164), f(168), data.i16(176), data.i16(178), data.u8(180), data.u8(181),
    ) else null
    return WorkoutSummaryExt(
        515, type, data.u32(4), data.u32(8), data.i32(12), data.i32(16), data.i32(20).takeUnless { it == -20000 },
        data.i32(24), data.i32(28), data.i32(32), data.i32(36), data.i32(40), data.i32(44),
        f(48), f(52), f(56), f(60), altitude(64), altitude(68), altitude(72),
        f(76), f(80), f(84), f(88)?.takeIf { it > 0 && it <= 60 }, f(92)?.takeIf { it > 0 }, f(96)?.takeIf { it > 0 },
        scaled(100, 60), scaled(104, 60), scaled(108, 60), scaled(112, 100), scaled(116, 100), scaled(120, 100),
        f(124), positiveShort(132), positiveShort(134), positiveShort(136), positiveShort(138), terrain, swimming,
    )
}
