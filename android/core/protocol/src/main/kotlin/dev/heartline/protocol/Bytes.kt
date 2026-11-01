package dev.heartline.protocol

import java.math.BigDecimal
import java.math.RoundingMode

internal fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
internal fun ByteArray.u8(i: Int) = this[i].toInt() and 255
internal fun ByteArray.u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
internal fun ByteArray.i16(i: Int) = u16(i).toShort().toInt()
internal fun ByteArray.u32(i: Int): Long = (0..3).fold(0L) { v, n -> v or (u8(i + n).toLong() shl (8 * n)) }
internal fun ByteArray.i32(i: Int) = u32(i).toInt()
internal fun ByteArray.f32(i: Int) = Float.fromBits(i32(i)).toDouble()
internal fun ByteArray.hex(): String {
    val digits = "0123456789abcdef"
    return CharArray(size * 2).also { out ->
        indices.forEach { i -> out[2 * i] = digits[u8(i) shr 4]; out[2 * i + 1] = digits[u8(i) and 15] }
    }.concatToString()
}
internal fun ByteArray.starts(vararg prefix: Int) = size >= prefix.size && prefix.indices.all { u8(it) == prefix[it] }
internal fun minute(ts: Long) = Math.floorDiv(ts, 60L) * 60L
// Python round rounds the actual binary value, with ties to even.
internal fun pyRound(value: Double, places: Int): Double {
    if (!value.isFinite()) return value
    val rounded = BigDecimal(value).setScale(places, RoundingMode.HALF_EVEN).toDouble()
    return if (rounded == 0.0) Math.copySign(0.0, value) else rounded
}
