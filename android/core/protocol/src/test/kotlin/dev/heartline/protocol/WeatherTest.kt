package dev.heartline.protocol

import java.time.LocalTime
import org.junit.Test
import kotlin.test.*

class WeatherTest {
    private val weather = WatchWeather(0x04030201, 19800, "Pune", -7, 2,
        listOf(WeatherDay(0, 30, 10), WeatherDay(63, 20, -2)), 64, 12.0, 40, LocalTime.of(6, 30), LocalTime.of(18, 15))

    @Test fun currentFromHuamiSupportOnSendWeatherIsKind2TimestampLEWholeHourOffsetConditionSignedTempString() {
        // 2 + int timestamp + (19800/3600)*4 + SCATTERED_CLOUDS + -7 + UTF-8 NUL.
        assertContentEquals(bytes(2, 1, 2, 3, 4, 20, 1, 249) + "Partly cloudy\u0000".toByteArray(), weatherPackets(weather)[0].payload)
    }
    @Test fun aqiFromHuamiSupportIsKind4Int16LEAndWeatherMapperLabel() {
        assertContentEquals(bytes(4, 1, 2, 3, 4, 20, 40, 0) + "Fair\u0000".toByteArray(), weatherPackets(weather)[1].payload)
        assertContentEquals(bytes(4, 1, 2, 3, 4, 20, 255, 255) + "Unknown\u0000".toByteArray(), weatherPackets(weather.copy(aqi = -1))[1].payload)
    }
    @Test fun forecastFromHuamiSupportDuplicatesConditionAndUsesCurrentConditionForToday() {
        val expected = bytes(1, 1, 2, 3, 4, 20, 2, 1, 1, 30, 10) + "Partly cloudy\u0000".toByteArray() +
            bytes(8, 8, 20, 254) + "Rain\u0000".toByteArray()
        assertContentEquals(expected, weatherPackets(weather)[2].payload)
    }
    @Test fun locationFromHuamiSupportHasNoTimestampOrNotificationHeader() {
        assertContentEquals(bytes(8) + "Pune\u0000".toByteArray(), weatherPackets(weather)[3].payload)
        assertContentEquals(bytes(8) + "Zürich\u0000".toByteArray(), weatherPackets(weather.copy(location = "Zürich"))[3].payload)
    }
    @Test fun windHumidityFromHuamiSupportKind64UsesBeaufortString() {
        assertContentEquals(bytes(64, 1, 2, 3, 4, 20) + "2\u000064%\u0000".toByteArray(), weatherPackets(weather)[4].payload)
    }
    @Test fun sunriseSunsetFromGtr2SupportsSunriseSunsetWindHumidityKind16() {
        assertContentEquals(bytes(16, 1, 2, 3, 4, 20, 6, 30, 18, 15), weatherPackets(weather)[5].payload)
        assertEquals(5, weatherPackets(weather.copy(sunrise = null)).size) // No invented polar solar times.
    }
    @Test fun allWeatherKindsUseChunkType1OldChunkFlagsAtMtu23() {
        assertEquals(listOf(1, 1, 1, 1, 1, 1), weatherPackets(weather).map { it.type })
        val location = weatherPackets(weather)[3]
        // HuamiSupport.writeToChunkedOld: single = flags c0 | type 1, counter 0.
        assertContentEquals(bytes(0, 0xC1, 0, 8, 80, 117, 110, 101, 0), chunkedWrites(location.payload, type = location.type).single())
        val chunks = chunkedWrites(weatherPackets(weather)[0].payload, type = 1)
        assertEquals(20, chunks.first().size)
        assertContentEquals(bytes(0, 1, 0), chunks.first().take(3).toByteArray())
        assertContentEquals(bytes(0, 0x81, 1), chunks.last().take(3).toByteArray())
    }
    @Test fun forecastIsCappedAtTodayPlusSixAsInHuamiSupport() {
        assertEquals(7, weatherPackets(weather.copy(days = List(10) { WeatherDay(0, 1, 0) }))[2].payload[6].toInt())
        assertFailsWith<IllegalArgumentException> { weatherPackets(weather.copy(days = emptyList())) }
    }
    @Test fun negativeFractionalTimezoneRetainsGadgetbridgeIntegerHourQuirk() {
        assertEquals(244, weatherPackets(weather.copy(phoneOffsetSeconds = -12600))[0].payload[5].toInt() and 255)
    }
    @Test fun fahrenheitAsInHuamiSupportCastsConvertedCurrentAndDailyTemperaturesToInt() {
        val packets = weatherPackets(weather, fahrenheit = true)
        // -7C -> 19.4F cast to 19; 30C -> 86F; 10C -> 50F.
        assertContentEquals(bytes(2, 1, 2, 3, 4, 20, 1, 19) + "Partly cloudy\u0000".toByteArray(), packets[0].payload)
        assertContentEquals(bytes(1, 1, 2, 3, 4, 20, 2, 1, 1, 86, 50) + "Partly cloudy\u0000".toByteArray() +
            bytes(8, 8, 68, 28) + "Rain\u0000".toByteArray(), packets[2].payload)
        assertEquals(-7, weather.tempC) // The UI/cache stays Celsius.
    }
    @Test fun everyWmoWeatherGroupMapsToHuamiWeatherConditionsOwmEquivalent() {
        val groups = mapOf(0 to listOf(0), 1 to listOf(1, 2), 2 to listOf(3), 18 to listOf(45, 48),
            19 to listOf(51, 53, 55, 56, 57), 7 to listOf(61, 80), 8 to listOf(63, 66, 67, 81),
            9 to listOf(65, 82), 14 to listOf(71, 77), 15 to listOf(73), 16 to listOf(75), 6 to listOf(85, 86), 4 to listOf(95, 96, 99))
        groups.forEach { (huami, wmos) -> wmos.forEach { assertEquals(huami, wmoCondition(it).huami, "WMO $it") } }
        assertEquals("Partly cloudy", wmoCondition(2).text)
        assertEquals(WeatherCondition(0, "Unknown"), wmoCondition(-1))
    }
    @Test fun beaufortStrictBoundariesFromWeatherSpecToBeaufort() {
        val bounds = listOf(2, 6, 12, 20, 29, 39, 50, 62, 75, 89, 103, 118)
        bounds.forEachIndexed { i, n -> assertEquals(i, weatherBeaufort(n.toDouble())); assertEquals(i + 1, weatherBeaufort(n + 0.01)) }
    }
    @Test fun plumeLabelsMatchWeatherMapperBoundaries() {
        listOf(-1 to "Unknown", 0 to "Excellent", 19 to "Excellent", 20 to "Fair", 49 to "Fair", 50 to "Poor",
            99 to "Poor", 100 to "Unhealthy", 149 to "Unhealthy", 150 to "Very unhealthy", 249 to "Very unhealthy", 250 to "Dangerous")
            .forEach { (aqi, label) -> assertEquals(label, weatherAqiLabel(aqi)) }
    }
}
