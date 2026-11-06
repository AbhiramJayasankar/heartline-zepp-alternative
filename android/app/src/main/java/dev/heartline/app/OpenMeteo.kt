package dev.heartline.app

import dev.heartline.protocol.WatchWeather
import dev.heartline.protocol.WeatherDay
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.*
import kotlin.math.roundToInt

/** [useLocation]: follow the phone's location ([PhoneLocation]); [place] is the picked place, used when that's off or unknown. */
data class WeatherConfig(val enabled: Boolean = false, val place: PlaceUi? = null, val useLocation: Boolean = true) {
    fun json(): String = JSONObject().put("enabled", enabled).put("use_location", useLocation).apply {
        place?.let { put("place", JSONObject().put("name", it.name).put("region", it.region).put("lat", it.lat).put("lon", it.lon)) }
    }.toString()
    companion object {
        const val KEY = "weather_settings"
        fun parse(value: String?): WeatherConfig = runCatching {
            val json = JSONObject(value ?: "{}")
            val p = json.optJSONObject("place")?.let {
                PlaceUi(it.getString("name"), it.optString("region").takeIf { s -> s.isNotBlank() && s != "null" }, it.getDouble("lat"), it.getDouble("lon"))
            }?.takeIf { validPlace(it) }
            WeatherConfig(json.optBoolean("enabled"), p, json.optBoolean("use_location", true))
        }.getOrDefault(WeatherConfig())
    }
}

fun validPlace(p: PlaceUi): Boolean = p.name.isNotBlank() && p.lat.isFinite() && p.lon.isFinite() && p.lat in -90.0..90.0 && p.lon in -180.0..180.0
data class FetchedWeather(val fetchedAt: Long, val weather: WatchWeather, val airQualityUnavailable: Boolean)

/** HTTPS requests contain the search text or explicitly chosen coordinates; no location permission is used. */
class OpenMeteo {
    suspend fun search(query: String): List<PlaceUi> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val json = get("https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(q, "UTF-8")}&count=8&language=en&format=json")
        currentCoroutineContext().ensureActive()
        val results = json.optJSONArray("results") ?: return@withContext emptyList()
        (0 until results.length()).map { i ->
            val p = results.getJSONObject(i)
            PlaceUi(p.getString("name"), listOf(p.optString("admin1"), p.optString("country")).filter { it.isNotBlank() }.joinToString(", ").ifBlank { null },
                p.getDouble("latitude"), p.getDouble("longitude"))
        }.filter(::validPlace).take(8)
    }

    suspend fun fetch(place: PlaceUi): FetchedWeather = withContext(Dispatchers.IO) {
        require(validPlace(place))
        val coords = "latitude=${place.lat}&longitude=${place.lon}"
        val json = get("https://api.open-meteo.com/v1/forecast?$coords&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,wind_direction_10m,is_day&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max&timezone=auto&forecast_days=7")
        currentCoroutineContext().ensureActive()
        // AQI is optional: a failed AQ request must not discard the forecast.
        val air = try {
            get("https://air-quality-api.open-meteo.com/v1/air-quality?$coords&current=pm2_5,pm10,nitrogen_dioxide,ozone&timezone=auto").getJSONObject("current")
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        currentCoroutineContext().ensureActive()
        val aqi = plumeAqi(air.number("pm2_5"), air.number("pm10"), air.number("nitrogen_dioxide"), air.number("ozone"))
        val weather = parseForecast(json, place, aqi)
        FetchedWeather(System.currentTimeMillis() / 1000, weather, aqi < 0)
    }

    private fun get(url: String): JSONObject {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000; connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            check(connection.responseCode in 200..299) { "Weather HTTP ${connection.responseCode}" }
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096); val out = StringBuilder()
                while (true) {
                    val n = reader.read(buffer); if (n < 0) break
                    out.append(buffer, 0, n); check(out.length <= 1_000_000) { "Weather response too large" }
                }
                out.toString()
            }
            return JSONObject(text).also { check(!it.optBoolean("error")) { "Weather service error" } }
        } finally { connection.disconnect() }
    }
}

private fun JSONObject?.number(key: String): Double? = this?.optDouble(key)?.takeIf { it.isFinite() }

internal fun parseForecast(json: JSONObject, place: PlaceUi, aqi: Int, phoneZone: ZoneId = ZoneId.systemDefault()): WatchWeather {
    val current = json.getJSONObject("current"); val daily = json.getJSONObject("daily")
    // Open-Meteo ISO values use the fixed utc_offset_seconds throughout this response.
    val offset = json.getInt("utc_offset_seconds")
    val timestamp = weatherTimestamp(current.getString("time"), offset)
    val count = daily.getJSONArray("time").length().coerceAtMost(7)
    require(count > 0)
    val days = (0 until count).map { i ->
        WeatherDay(daily.getJSONArray("weather_code").getInt(i), daily.getJSONArray("temperature_2m_max").getDouble(i).roundToInt(),
            daily.getJSONArray("temperature_2m_min").getDouble(i).roundToInt())
    }
    fun solar(key: String): LocalTime? = weatherSolarTime(daily.optJSONArray(key)?.optString(0), offset, phoneZone)
    return WatchWeather(timestamp, phoneZone.rules.getOffset(Instant.ofEpochSecond(timestamp)).totalSeconds, place.name,
        current.getDouble("temperature_2m").roundToInt(), current.getInt("weather_code"), days,
        current.getInt("relative_humidity_2m").coerceIn(0, 100), current.getDouble("wind_speed_10m"), aqi, solar("sunrise"), solar("sunset"))
}
