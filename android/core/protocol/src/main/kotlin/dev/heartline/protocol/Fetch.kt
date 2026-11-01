package dev.heartline.protocol

import java.time.ZoneId
import java.time.ZonedDateTime

/** Consumed buffers advance on transfer, even without an acknowledgement (health.CONSUMED_ON_TRANSFER). */
enum class FetchType(val code: Int, val key: String, val consumedOnTransfer: Boolean = false) {
    ACTIVITY(0x01, "activity"), MANUAL_HR(0x02, "manual_hr"), WORKOUTS(0x05, "workouts"),
    WORKOUT_DETAILS(0x06, "workout_details"), PAI(0x0D, "pai"), STRESS_MANUAL(0x12, "stress_manual", true),
    STRESS(0x13, "stress", true), SPO2(0x25, "spo2", true), SPO2_SLEEP(0x26, "spo2_sleep", true),
    STATISTICS(0x2C, "statistics", true), TEMPERATURE(0x2E, "temperature", true),
    SLEEP_RESPIRATORY_RATE(0x38, "sleep_respiratory_rate", true), RESTING_HR(0x3A, "resting_hr", true),
    MAX_HR(0x3D, "max_hr", true), SLEEP_SESSION(0x48, "sleep_session", true), HRV(0x49, "hrv", true),
}

fun fetchStartCommand(code: Int, since: ZonedDateTime): ByteArray {
    require(code in 0..255) { "bytes must be in range(0, 256)" }
    return bytes(1, code) + activityTimeBytes(since)
}

data class FetchStart(val count: Long, val startEpoch: Long?)

/** Wire timezone is authoritative, including zero (= UTC); zone never replaces it (protocol._parse_time). */
@Suppress("UNUSED_PARAMETER")
fun parseFetchStart(reply: ByteArray, zone: ZoneId): FetchStart? {
    if (!reply.starts(0x10, 1, 1)) return null
    val count = (3 until minOf(7, reply.size)).fold(0L) { v, i -> v or (reply.u8(i).toLong() shl ((i - 3) * 8)) }
    val start = parseWatchTime(reply.drop(7).take(8).toByteArray())
    return FetchStart(count, if (count != 0L) start?.epoch else null)
}

val FETCH_GO: ByteArray get() = bytes(2)
// Never provide/send the 0x03 fetch ack: it deletes transferred data from the watch.

/**
 * One transfer, following watch._fetch_blob/_fetch_once. Feed ACT_DATA and ACT_CTRL separately so payload
 * bytes cannot be mistaken for the end marker. A counter gap invalidates the transfer until reset.
 * The Python connection code strips counters without checking them; this API additionally rejects gaps.
 */
class FetchAssembler {
    private val buffer = ArrayList<Byte>()
    private var lastCounter: Int? = null
    private var failed = false
    var complete: Boolean = false
        private set
    val payload: ByteArray get() = buffer.toByteArray()

    fun feedData(packet: ByteArray) {
        check(!failed) { "Fetch transfer is invalid; reset before reuse" }
        check(!complete) { "Fetch transfer already ended" }
        require(packet.isNotEmpty()) { "Empty fetch data packet" }
        val counter = packet.u8(0)
        val expected = lastCounter?.let { (it + 1) and 255 }
        if (expected != null && counter != expected) {
            failed = true
            throw IllegalArgumentException("Fetch counter gap: expected $expected, got $counter")
        }
        lastCounter = counter
        for (i in 1 until packet.size) buffer.add(packet[i])
    }

    /** Accepts the same three-byte prefix (with optional trailing bytes) as the Python end check. */
    fun feedControl(reply: ByteArray): Boolean {
        check(!failed) { "Fetch transfer is invalid; reset before reuse" }
        if (reply.starts(0x10, 1, 1)) return false
        if (!reply.starts(0x10, 2, 1)) {
            failed = true
            throw IllegalArgumentException("Transfer ended with ${reply.hex()}")
        }
        complete = true
        return true
    }

    fun reset() {
        buffer.clear()
        lastCounter = null
        failed = false
        complete = false
    }
}
