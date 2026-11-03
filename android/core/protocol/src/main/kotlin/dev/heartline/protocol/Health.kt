package dev.heartline.protocol

import kotlin.math.abs

data class HealthPoint(val type: String, val ts: Long, val value: Double?, val extra: Map<String, String> = emptyMap())

/** health.parse_manual_hr / FetchHeartRateManualOperation (six bytes). */
fun parseManualHr(data: ByteArray): List<HealthPoint> = (0 until data.size / 6).map { n ->
    val i = n * 6
    HealthPoint("manual_hr", data.u32(i), data.u8(i + 5).toDouble(), mapOf("tz_q" to data[i + 4].toString()))
}

fun parseStress(data: ByteArray, startEpoch: Long): List<HealthPoint> = data.indices.filter { data.u8(it) != 255 }.map { i ->
    HealthPoint("stress", minute(startEpoch) + 60L * i, data.u8(i).toDouble())
}

fun parseStressManual(data: ByteArray): List<HealthPoint> = (0 until data.size / 5).map { n ->
    val i = n * 5
    HealthPoint("stress_manual", data.u32(i), data.u8(i + 4).toDouble())
}

/** health.parse_pai / FetchPaiOperation; pre-reset records are skipped. */
fun parsePai(data: ByteArray): List<HealthPoint> = (0 until data.size / 102).mapNotNull { n ->
    val i = n * 102
    if (data.u8(i) != 5) return@mapNotNull null
    HealthPoint("pai", data.u32(i + 1), pyRound(data.f32(i + 55), 2), linkedMapOf(
        "total" to pyRound(data.f32(i + 59), 2).toString(),
        "low" to pyRound(data.f32(i + 37), 2).toString(),
        "moderate" to pyRound(data.f32(i + 41), 2).toString(),
        "high" to pyRound(data.f32(i + 45), 2).toString(),
        "minutes_low" to data.i16(i + 49).toString(), "minutes_moderate" to data.i16(i + 51).toString(),
        "minutes_high" to data.i16(i + 53).toString(),
    ))
}

/** health.parse_spo2: version-2 normal/sleep records, or this watch's unversioned six-byte records. */
fun parseSpo2(data: ByteArray, type: String = "spo2"): List<HealthPoint> {
    val normal = data.isNotEmpty() && data.u8(0) == 2 && (data.size - 1) % 65 == 0
    val sleep = !normal && data.isNotEmpty() && data.u8(0) == 2 && (data.size - 1) % 30 == 0
    val size = when { normal -> 65; sleep -> 30; data.size % 6 == 0 -> 6; else -> return emptyList() }
    val first = if (normal || sleep) 1 else 0
    return (first until data.size step size).mapNotNull { i ->
        val raw = data[i + 4].toInt()
        val value = if (normal && raw < 0) raw + 128 else data.u8(i + 4)
        if (value !in 50..100) return@mapNotNull null
        val extra = when {
            normal -> mapOf("auto" to (raw < 0).toString())
            sleep -> mapOf("duration" to data.u8(i + 5).toString())
            else -> mapOf("extra" to data.u8(i + 5).toString())
        }
        HealthPoint(type, data.u32(i), value.toDouble(), extra)
    }
}

fun parseSpo2Sleep(data: ByteArray): List<HealthPoint> = parseSpo2(data, "spo2_sleep")

/** health.parse_temperature: the ring-buffer header can be years old; anchor inconsistent headers to END. */
fun parseTemperature(data: ByteArray, startEpoch: Long?, endEpoch: Long): List<HealthPoint> {
    val n = data.size / 8
    val end = minute(endEpoch)
    val base = if (startEpoch == null || abs(minute(startEpoch) + 60L * (n - 1) - end) > 3600)
        end - 60L * (n - 1) else minute(startEpoch)
    return (0 until n).mapNotNull { i ->
        val sensor2 = data.i16(8 * i)
        val temp = data.i16(8 * i + 2)
        if (temp == 0x5A5A || temp == 0x7FFF || temp !in 1000..4500) return@mapNotNull null
        HealthPoint("temperature", base + 60L * i, temp / 100.0,
            mapOf("sensor2" to if (sensor2 in 1000..4500) (sensor2 / 100.0).toString() else "null"))
    }
}

/** Opaque blobs are stored losslessly as hex, like watch._parse_health's raw_fetches path. */
/** Kept raw (the event log is mostly unidentified), plus the workout starts and ends it reveals ([watchWorkoutMarks]). */
fun parseStatistics(data: ByteArray, fetchedAt: Long): List<HealthPoint> =
    rawHealth(FetchType.STATISTICS, data, fetchedAt) + watchWorkoutMarks(parseStatisticsEvents(data))

private fun rawHealth(type: FetchType, data: ByteArray, fetchedAt: Long): List<HealthPoint> =
    if (data.isEmpty()) emptyList() else listOf(HealthPoint(type.key, fetchedAt, null, mapOf("raw" to data.hex())))

/** Dispatches scalar health types. Undecoded types retain raw bytes; workouts have their own model. */
fun parseHealth(type: FetchType, data: ByteArray, startEpoch: Long?, endEpoch: Long): List<HealthPoint> = when (type) {
    FetchType.MANUAL_HR -> parseManualHr(data)
    FetchType.STRESS -> if (startEpoch != null && startEpoch != 0L) parseStress(data, startEpoch) else emptyList()
    FetchType.STRESS_MANUAL -> parseStressManual(data)
    FetchType.PAI -> parsePai(data)
    FetchType.SPO2 -> parseSpo2(data)
    FetchType.SPO2_SLEEP -> parseSpo2Sleep(data)
    FetchType.TEMPERATURE -> parseTemperature(data, startEpoch, endEpoch)
    FetchType.STATISTICS -> parseStatistics(data, endEpoch)
    FetchType.SLEEP_RESPIRATORY_RATE, FetchType.RESTING_HR, FetchType.MAX_HR,
    FetchType.SLEEP_SESSION, FetchType.HRV -> rawHealth(type, data, endEpoch)
    else -> throw IllegalArgumentException("${type.key} has a dedicated parser")
}
