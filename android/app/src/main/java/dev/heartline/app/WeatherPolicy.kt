package dev.heartline.app

import kotlin.math.roundToInt
import java.time.*

object WeatherPolicy {
    const val SEND_INTERVAL_MS = 60 * 60_000L
    const val CACHE_SECONDS = 30 * 60L
    fun needsFetch(fetchedAt: Long?, now: Long): Boolean = fetchedAt == null || now < fetchedAt || now - fetchedAt > CACHE_SECONDS
}

fun weatherTimestamp(local: String, offsetSeconds: Int): Long = LocalDateTime.parse(local).toEpochSecond(ZoneOffset.ofTotalSeconds(offsetSeconds))

fun weatherSolarTime(local: String?, offsetSeconds: Int, phoneZone: ZoneId): LocalTime? = local?.let {
    runCatching { Instant.ofEpochSecond(weatherTimestamp(it, offsetSeconds)).atZone(phoneZone).toLocalTime() }.getOrNull()
}

/** WeatherSpec.AirQuality and WeatherMapper use Plume, not US or European AQI.
 * Plume Labs 2023 specification, table 3: piecewise linear, max of four pollutants.
 * Open-Meteo concentrations are in ug/m3, matching the table.
 */
fun plumeAqi(pm25: Double?, pm10: Double?, no2: Double?, ozone: Double?): Int {
    val levels = doubleArrayOf(0.0, 20.0, 50.0, 100.0, 150.0, 250.0)
    fun index(value: Double?, limits: DoubleArray): Double? {
        if (value == null || !value.isFinite() || value < 0) return null
        val upper = (1 until limits.size).firstOrNull { value <= limits[it] } ?: limits.lastIndex
        return levels[upper - 1] + (value - limits[upper - 1]) *
            (levels[upper] - levels[upper - 1]) / (limits[upper] - limits[upper - 1])
    }
    val values = listOf(
        index(pm25, doubleArrayOf(0.0, 5.0, 15.0, 30.0, 60.0, 150.0)),
        index(pm10, doubleArrayOf(0.0, 15.0, 45.0, 80.0, 160.0, 400.0)),
        index(no2, doubleArrayOf(0.0, 10.0, 25.0, 200.0, 400.0, 1000.0)),
        index(ozone, doubleArrayOf(0.0, 50.0, 100.0, 160.0, 240.0, 480.0)),
    )
    // Do not report a reassuring AQI from an incomplete set of pollutants.
    return if (values.any { it == null }) -1 else values.filterNotNull().max().roundToInt().coerceIn(0, 32767)
}
