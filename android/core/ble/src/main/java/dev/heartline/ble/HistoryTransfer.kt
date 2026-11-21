package dev.heartline.ble

import dev.heartline.protocol.*
import kotlinx.coroutines.withTimeout
import java.time.ZoneId
import java.time.ZonedDateTime

internal data class HistoryFetch(val reply: ByteArray, val start: Long?, val data: ByteArray) {
    /**
     * The watch refused the transfer (10 01 07). It answers every history request this way while a workout is running:
     * on 21 Nov 2026 it refused all 170 requests between 17:20 and 19:50, through a walk, a run and a walk, which the
     * app had read as "nothing new".
     */
    val busy: Boolean get() = reply.size >= 3 && reply[0] == 0x10.toByte() && reply[1] == 1.toByte() && reply[2] == 7.toByte()
}

/** The watch is refusing history transfers, as it does during a workout; try again later. */
class WatchBusyException(message: String = "The watch is refusing transfers, as it does during a workout") : IllegalStateException(message)

/** watch.py:_fetch_once/_fetch_blob. Notification registration and cleanup belong to the caller. */
internal suspend fun transferHistory(
    type: FetchType,
    since: ZonedDateTime,
    send: suspend (ByteArray) -> Unit,
    receive: suspend (Long) -> GattNotification,
): HistoryFetch {
    send(fetchStartCommand(type.code, since))
    val header = receive(10_000)
    check(header.uuid == Uuids.ACT_CTRL) { "History data arrived before header" }
    val start = parseFetchStart(header.value, ZoneId.systemDefault())
    if (start == null || start.count == 0L) return HistoryFetch(header.value, null, byteArrayOf())
    val assembler = FetchAssembler()
    send(FETCH_GO)
    withTimeout(if (type == FetchType.ACTIVITY) 120_000L else 180_000L) {
        while (!assembler.complete) {
            val n = receive(180_000)
            if (n.uuid == Uuids.ACT_DATA) assembler.feedData(n.value) else assembler.feedControl(n.value)
        }
    }
    // Intentionally no acknowledgement, including cancellation and failed transfers.
    return HistoryFetch(header.value, start.startEpoch, assembler.payload)
}
