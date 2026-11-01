package dev.heartline.protocol

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val isoSeconds = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss", Locale.ROOT)
internal data class WatchTime(val iso: String, val epoch: Long)

internal fun localWatchTime(data: ByteArray): LocalDateTime? {
    if (data.size < 7 || data.u16(0) !in 1..9999) return null
    return try {
        LocalDateTime.of(data.u16(0), data.u8(2), data.u8(3), data.u8(4), data.u8(5), data.u8(6))
    } catch (_: DateTimeException) { null }
}

internal fun parseWatchTime(data: ByteArray): WatchTime? {
    val local = localWatchTime(data) ?: return null
    val quarters = data.getOrNull(7)?.toInt() ?: 0
    require(kotlin.math.abs(quarters) < 96) { "Timezone offset must be strictly between -24h and +24h" }
    // Python timezone permits offsets up to 24h, beyond java.time.ZoneOffset's 18h limit.
    val minutes = kotlin.math.abs(quarters) * 15
    val offset = String.format(Locale.ROOT, "%s%02d:%02d", if (quarters < 0) "-" else "+", minutes / 60, minutes % 60)
    return WatchTime(local.format(isoSeconds) + offset, local.toEpochSecond(ZoneOffset.UTC) - quarters * 900L)
}

fun activityTimeBytes(t: ZonedDateTime): ByteArray = bytes(
    t.year and 255, t.year shr 8, t.monthValue, t.dayOfMonth, t.hour, t.minute, 0,
    Math.floorDiv(t.offset.totalSeconds, 900) and 255,
)

/** health.current_time_bytes / HuamiSupport.setCurrentTime. */
fun currentTimeBytes(now: ZonedDateTime): ByteArray = bytes(
    now.year and 255, now.year shr 8, now.monthValue, now.dayOfMonth, now.hour, now.minute, now.second,
    now.dayOfWeek.value, 0, 0, Math.floorDiv(now.offset.totalSeconds, 900) and 255,
)

fun parseCurrentTime(data: ByteArray): String? = localWatchTime(data)?.format(isoSeconds)
