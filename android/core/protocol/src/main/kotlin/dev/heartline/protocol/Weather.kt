package dev.heartline.protocol

import java.io.ByteArrayOutputStream
import java.time.LocalTime

data class WeatherCondition(val huami: Int, val text: String)

// WMO meanings: Open-Meteo docs. Icons: HuamiWeatherConditions.mapToAmazfitBipWeatherCode.
fun wmoCondition(code: Int): WeatherCondition = when (code) {
    0 -> WeatherCondition(0, "Clear sky")
    1 -> WeatherCondition(1, "Mainly clear")
    2 -> WeatherCondition(1, "Partly cloudy")
    3 -> WeatherCondition(2, "Overcast")
    45 -> WeatherCondition(18, "Fog")
    48 -> WeatherCondition(18, "Freezing fog")
    51 -> WeatherCondition(19, "Light drizzle")
    53 -> WeatherCondition(19, "Drizzle")
    55 -> WeatherCondition(19, "Heavy drizzle")
    56, 57 -> WeatherCondition(19, "Freezing drizzle")
    61, 80 -> WeatherCondition(7, "Light rain")
    63, 81 -> WeatherCondition(8, "Rain")
    65, 82 -> WeatherCondition(9, "Heavy rain")
    66, 67 -> WeatherCondition(8, "Freezing rain") // GB maps OWM 511 to MEDIUM_RAIN.
    71, 77 -> WeatherCondition(14, "Light snow")
    73 -> WeatherCondition(15, "Snow")
    75 -> WeatherCondition(16, "Heavy snow")
    85, 86 -> WeatherCondition(6, "Snow showers") // GB maps OWM 620..622 to RAIN_AND_SNOW.
    95 -> WeatherCondition(4, "Thunderstorm")
    96, 99 -> WeatherCondition(4, "Thunderstorm with hail")
    else -> WeatherCondition(0, "Unknown")
}

data class WeatherDay(val wmo: Int, val highC: Int, val lowC: Int)
data class WatchWeather(
    val timestamp: Long, val phoneOffsetSeconds: Int, val location: String,
    val tempC: Int, val wmo: Int, val days: List<WeatherDay>,
    val humidity: Int, val windKmh: Double, val aqi: Int = -1,
    val sunrise: LocalTime? = null, val sunset: LocalTime? = null,
)
data class WeatherPacket(val type: Int, val payload: ByteArray)

// HuamiSupport.windSpeedString -> WeatherSpec.toBeaufort (km/h; strict > thresholds).
fun weatherBeaufort(kmh: Double): Int = listOf(2, 6, 12, 20, 29, 39, 50, 62, 75, 89, 103, 118).count { kmh > it }

// HuamiSupport.onSendWeather -> WeatherMapper.getAqiLevelString (Plume scale).
fun weatherAqiLabel(aqi: Int): String = when {
    aqi < 0 -> "Unknown"
    aqi < 20 -> "Excellent"
    aqi < 50 -> "Fair"
    aqi < 100 -> "Poor"
    aqi < 150 -> "Unhealthy"
    aqi < 250 -> "Very unhealthy"
    else -> "Dangerous"
}

/** HuamiSupport.onSendWeather; GTR2e -> GTR2 supportsSunriseSunsetWindHumidity=true.
 * All packets use old chunked type 1. AmazfitGTSSupport.notificationHasExtraHeader applies
 * only to notifications: weather (and music) have no four-zero notification header.
 */
fun weatherPackets(weather: WatchWeather, fahrenheit: Boolean = false): List<WeatherPacket> {
    require(weather.days.isNotEmpty())
    val condition = wmoCondition(weather.wmo)
    // HuamiSupport.onSendWeather casts TemperatureUtils.celsiusToFahrenheit to int.
    fun temperature(celsius: Int): Int = if (fahrenheit) (celsius * 1.8 + 32).toInt() else celsius
    fun packet(kind: Int, dated: Boolean = true, body: ByteArrayOutputStream.() -> Unit): WeatherPacket {
        val out = ByteArrayOutputStream()
        out.write(kind)
        if (dated) {
            repeat(4) { out.write((weather.timestamp shr (8 * it)).toInt() and 255) }
            // Preserve HuamiSupport's integer hours BEFORE conversion to quarter-hours (incl. :30 zones).
            out.write((weather.phoneOffsetSeconds / 3600 * 4) and 255)
        }
        out.body()
        return WeatherPacket(1, out.toByteArray())
    }
    return buildList {
        add(packet(2) { write(condition.huami); write(temperature(weather.tempC) and 255); text0(condition.text) })
        add(packet(4) {
            write(weather.aqi and 255); write((weather.aqi shr 8) and 255); text0(weatherAqiLabel(weather.aqi))
        })
        add(packet(1) {
            val days = weather.days.take(7)
            write(days.size)
            days.forEachIndexed { index, day ->
                val c = if (index == 0) condition else wmoCondition(day.wmo)
                write(c.huami); write(c.huami); write(temperature(day.highC) and 255); write(temperature(day.lowC) and 255); text0(c.text)
            }
        })
        add(packet(8, false) { text0(weather.location) })
        add(packet(64) { text0(weatherBeaufort(weather.windKmh).toString()); text0("${weather.humidity}%") })
        if (weather.sunrise != null && weather.sunset != null) add(packet(16) {
            write(weather.sunrise.hour); write(weather.sunrise.minute); write(weather.sunset.hour); write(weather.sunset.minute)
        })
    }
}

private fun ByteArrayOutputStream.text0(value: String) {
    write(value.replace("\u0000", "").toByteArray(Charsets.UTF_8)); write(0)
}
