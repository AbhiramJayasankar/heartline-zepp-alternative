package dev.heartline.protocol

import java.math.BigDecimal
import java.math.RoundingMode

data class WorkoutRoutePoint(val t: Int, val lat: Double, val lon: Double, val altM: Double?)

private data class RouteSample(var t: Int, var coordinate: Coordinate? = null)
private data class Coordinate(val lon: Double, val lat: Double, val altitude: Int?)
private fun huamiDegrees(value: Long) = BigDecimal.valueOf(value).divide(BigDecimal.valueOf(3_000_000), 6, RoundingMode.HALF_UP).toDouble()

/** HuamiActivitySummaryParser.parseBinaryData: signed base lon/lat/alt at offsets 12/16/20.
 * HuamiActivityDetailsParser.parse/consumeGPSAndUpdateBaseLocation: 8-byte records, signed LE16 deltas.
 * AbstractHuamiActivityDetailsParser converts coordinates / 3,000,000 to six decimal places, HALF_UP.
 */
fun parseWorkoutRoute(details: ByteArray, summary: ByteArray): List<WorkoutRoutePoint> {
    if (summary.size < 24) return emptyList()
    var lon = summary.i32(12).toLong()
    var lat = summary.i32(16).toLong()
    var altitude = summary.i32(20).takeUnless { it == -20000 }
    if (lon == 0L && lat == 0L) return emptyList()
    val samples = mutableListOf<RouteSample>()
    var total = 0
    var last = 0
    fun point(time: Int, coordinate: Coordinate? = null) {
        val previous = samples.lastOrNull()
        if (previous != null && previous.t == time && (coordinate == null || previous.coordinate == null || previous.coordinate == coordinate)) {
            if (coordinate != null) previous.coordinate = coordinate
        } else samples.add(RouteSample(time, coordinate))
    }
    for (i in 0 until details.size - 7 step 8) {
        val offset = details.u8(i + 1)
        total += if (last <= offset) offset - last else offset
        last = offset
        when (details.u8(i)) {
            0 -> {
                lon += details.i16(i + 2); lat += details.i16(i + 4)
                altitude = altitude?.plus(details.i16(i + 6))
                if (lon !in -540_000_000L..540_000_000L || lat !in -270_000_000L..270_000_000L) return emptyList()
                point(total, Coordinate(huamiDegrees(lon), huamiDegrees(lat), altitude))
            }
            1 -> if ((i + 3..i + 7).all { details.u8(it) == 0 }) point(total)
                else for (k in 2..6 step 2) point(details.u8(i + k))
        }
    }
    // HuamiActivityDetailsParser.fixupMissingTimestamps spreads the initial GPS burst up to the next timestamp.
    val initial = mutableListOf<RouteSample>()
    for (i in 0 until samples.size - 1) {
        if (samples[i].coordinate == null) continue
        initial.add(samples[i])
        if (samples[i].t != samples[i + 1].t) {
            val multiplier = kotlin.math.abs(samples[i + 1].t).toDouble() / initial.size
            initial.forEachIndexed { index, sample -> sample.t = Math.round(index * multiplier).toInt() }
            break
        }
    }
    return samples.mapNotNull { sample -> sample.coordinate?.let { WorkoutRoutePoint(sample.t, it.lat, it.lon, it.altitude?.toDouble()) } }
}
