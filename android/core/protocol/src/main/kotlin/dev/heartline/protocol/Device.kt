package dev.heartline.protocol

import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Characteristic names match server/protocol.py and server/health.py. */
object Uuids {
    val AUTH: UUID = huami("0009")
    val BATTERY: UUID = huami("0006")
    val RAW_CTRL: UUID = huami("0001")
    val RAW_DATA: UUID = huami("0002")
    val ACT_CTRL: UUID = huami("0004")
    val ACT_DATA: UUID = huami("0005")
    val CONFIG: UUID = huami("0003")
    /** User settings: user info (4f), step goal (10), wear location (20). Gadgetbridge HuamiService.UUID_CHARACTERISTIC_8_USER_SETTINGS. */
    val USER_SETTINGS: UUID = huami("0008")
    val REALTIME_STEPS: UUID = huami("0007")
    val DEVICE_EVENT: UUID = huami("0010")
    /** Watch workout lifecycle (Gadgetbridge HuamiWorkoutStatus): `11 status`, 02 start, 03 pause, 04 resume, 05 end. */
    val WORKOUT_STATUS: UUID = huami("000f")
    /** Huami 2021 chunked transfer: the phone writes on 0016, the watch notifies (and takes acks) on 0017. */
    val CHUNKED_2021_WRITE: UUID = huami("0016")
    val CHUNKED_2021_READ: UUID = huami("0017")
    val CHUNKED: UUID = huami("0020")
    val HR_MEASURE: UUID = standard("2a37")
    val HR_CONTROL: UUID = standard("2a39")
    val ALERT_LEVEL: UUID = standard("2a06")
    val CURRENT_TIME: UUID = standard("2a2b")
    val SERIAL: UUID = standard("2a25")
    val HARDWARE: UUID = standard("2a27")
    val SOFTWARE: UUID = standard("2a28")
    val SYSTEM_ID: UUID = standard("2a23")
    val PNP_ID: UUID = standard("2a50")

    private fun huami(short: String) = UUID.fromString("0000$short-0000-3512-2118-0009af100700")
    private fun standard(short: String) = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
}

val DEVICE_INFO: Map<String, UUID> = linkedMapOf(
    "serial" to Uuids.SERIAL, "hardware" to Uuids.HARDWARE, "software" to Uuids.SOFTWARE,
    "system_id" to Uuids.SYSTEM_ID, "pnp_id" to Uuids.PNP_ID,
)

object Auth {
    val REQUEST_RANDOM: ByteArray get() = bytes(0x82, 0, 2, 1, 0)
    fun response(key: ByteArray, randomReply: ByteArray): ByteArray {
        require(randomReply.starts(0x10, 0x82, 1) && randomReply.size >= 19) {
            "unexpected random reply ${randomReply.hex()}"
        }
        require(key.size == 16) { "Incorrect AES key length (${key.size} bytes)" }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return bytes(0x83, 0) + cipher.doFinal(randomReply.copyOfRange(3, 19))
    }
    fun isSuccess(reply: ByteArray): Boolean = reply.starts(0x10, 0x83, 1)
}

data class BatteryInfo(
    val level: Int, val charging: Boolean, val lastChargeA: String?, val lastChargeB: String?, val raw: String,
)

fun parseBattery(data: ByteArray): BatteryInfo = BatteryInfo(
    data.u8(1), data.size > 2 && data.u8(2) == 1,
    parseWatchTime(data.drop(3).take(8).toByteArray())?.iso,
    parseWatchTime(data.drop(11).take(8).toByteArray())?.iso, data.hex(),
)

fun parseHeartRate(data: ByteArray): Int = if (data.u8(0) and 1 != 0) {
    // int.from_bytes in protocol.parse_hr also accepts a one-byte u16 tail.
    data.drop(1).take(2).foldIndexed(0) { n, v, b -> v or ((b.toInt() and 255) shl (8 * n)) }
} else data.u8(1)

object HrCommands {
    val STOP_MANUAL: ByteArray get() = bytes(0x15, 2, 0)
    val START_CONTINUOUS: ByteArray get() = bytes(0x15, 1, 1)
    val STOP_CONTINUOUS: ByteArray get() = bytes(0x15, 1, 0)
    val PING: ByteArray get() = bytes(0x16)
}

fun decodeInfo(data: ByteArray): String {
    val text = data.dropLastWhile { it == 0.toByte() }
    return if (text.isNotEmpty() && text.all { it.toInt() in 32..126 })
        text.toByteArray().toString(Charsets.US_ASCII) else data.hex()
}

val ALERT_FIND: ByteArray get() = bytes(3)
val ALERT_OFF: ByteArray get() = bytes(0)

/** Name of a workout-status notification, or the raw hex when it isn't the documented `11 status` form. */
fun workoutStatusName(data: ByteArray): String =
    if (data.size >= 2 && data[0].toInt() == 0x11) when (data[1].toInt() and 255) {
        2 -> "started"; 3 -> "paused"; 4 -> "resumed"; 5 -> "ended"; else -> "status %02x".format(data[1].toInt() and 255)
    } else "unknown " + data.joinToString("") { "%02x".format(it) }
