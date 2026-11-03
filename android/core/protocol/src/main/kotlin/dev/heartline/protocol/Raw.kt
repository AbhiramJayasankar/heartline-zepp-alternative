package dev.heartline.protocol

enum class RawSensor(val mask: Int) { ACCEL(1), PPG(2), GPS(8), GYRO(16), MIC(32), MAG(64) }
fun rawMask(sensors: Set<RawSensor>, fastPpg: Boolean = false): Int =
    sensors.fold(0) { mask, sensor -> mask or sensor.mask }.let { if (it == 0) 3 else it }
        .let { if (fastPpg && it and RawSensor.PPG.mask != 0) it or 0x80 else it }
/** [rate] is the last byte of both start commands; every client sends 0x19 (25). Probe 3 showed the watch ignores it. */
fun rawStartCommands(mask: Int = 3, rate: Int = 0x19): List<ByteArray> {
    require(mask in 0..255 && rate in 0..255) { "bytes must be in range(0, 256)" }
    return listOf(bytes(1, mask, rate), bytes(1, mask, 0, 0, 0, rate), bytes(2))
}
val RAW_STOP: ByteArray get() = bytes(3)
const val ACCEL_ONE_G = 4100
/** Unknown-rate sensors use the legacy 25 Hz only until their clock can be measured. */
fun rawNominalRateHz(sensor: Int, fastPpg: Boolean = false): Double? = when (sensor) {
    0 -> 26.16
    1 -> if (fastPpg) 50.0 else 25.0
    else -> null
}
data class Vec3f(val x: Float, val y: Float, val z: Float)
data class Vec3i(val x: Int, val y: Int, val z: Int)

sealed interface RawPacket {
    data class Accel(val samples: List<Vec3f>, val seq: Int? = null) : RawPacket
    data class Ppg(val values: IntArray, val seq: Int? = null) : RawPacket
    data class Gyro(val samples: List<Vec3i>, val seq: Int? = null) : RawPacket
    data class Mag(val samples: List<Vec3i>, val seq: Int? = null) : RawPacket
    data class GpsText(val text: String) : RawPacket
    data class Mic(val counter: Int, val payload: ByteArray) : RawPacket
    /** The stamp follows the sensor packet and marks its LAST sample; counters are per type. */
    data class Timestamp(val sensor: Int, val seq: Int, val watchMs: Long) : RawPacket
    data class Unknown(val type: Int, val size: Int) : RawPacket
}

/** protocol.parse_raw: two-byte header, complete samples only, 4100 accel units per g. */
fun parseRaw(data: ByteArray): RawPacket {
    if (data.size < 2) return RawPacket.Unknown(data.firstOrNull()?.toInt()?.and(255) ?: -1, data.size)
    return when (val type = data.u8(0)) {
        0 -> RawPacket.Accel((0 until (data.size - 2) / 6).map { n ->
            val i = 2 + n * 6
            fun g(k: Int) = pyRound(data.i16(i + k) / 4100.0, 3).toFloat()
            Vec3f(g(0), g(2), g(4))
        }, data.u8(1))
        1 -> RawPacket.Ppg(IntArray((data.size - 2) / 2) { data.u16(2 + 2 * it) }, data.u8(1))
        4, 6 -> {
            val samples = (0 until (data.size - 2) / 6).map { n ->
                val i = 2 + n * 6
                Vec3i(data.i16(i), data.i16(i + 2), data.i16(i + 4))
            }
            if (type == 4) RawPacket.Gyro(samples, data.u8(1)) else RawPacket.Mag(samples, data.u8(1))
        }
        3 -> RawPacket.GpsText(buildString { for (i in 2 until data.size) append(if (data.u8(i) < 128) data.u8(i).toChar() else '\uFFFD') })
        5 -> RawPacket.Mic(data.u8(1), data.copyOfRange(2, data.size))
        7 -> if (data.size >= 7) RawPacket.Timestamp(data.u8(2), data.u8(1),
            (3..6).fold(0L) { value, i -> value or (data.u8(i).toLong() shl ((i - 3) * 8)) })
            else RawPacket.Unknown(type, data.size)
        else -> RawPacket.Unknown(type, data.size)
    }
}
