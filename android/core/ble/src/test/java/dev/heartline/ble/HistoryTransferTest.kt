package dev.heartline.ble

import dev.heartline.protocol.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class HistoryTransferTest {
    private val since = ZonedDateTime.parse("2026-10-31T02:28:59+05:30")
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private val capturedHeader = hex("1001014a020000ea070a1f021c0016")
    private fun control(bytes: ByteArray) = GattNotification(Uuids.ACT_CTRL, bytes)
    private fun data(vararg bytes: Int) = GattNotification(Uuids.ACT_DATA, bytes.map { it.toByte() }.toByteArray())
    @Test fun capturedHeaderAndInterleavedPacketsFollowPythonSequenceWithoutAck() = runBlocking {
        val received = ArrayDeque(listOf(control(capturedHeader), data(254, 1, 2), data(255, 3), data(0, 4), control(hex("100201aabb"))))
        val commands = mutableListOf<ByteArray>()
        val f = transferHistory(FetchType.ACTIVITY, since, { commands.add(it) }, { received.removeFirst() })
        assertEquals(2, commands.size); assertArrayEquals(hex("0101ea070a1f021c0016"), commands[0]); assertArrayEquals(byteArrayOf(2), commands[1])
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), f.data)
        assertEquals(ZonedDateTime.parse("2026-10-31T02:28:00+05:30").toEpochSecond(), f.start)
        assertTrue(commands.none { it.contentEquals(byteArrayOf(3)) })
    }
    @Test fun emptyHeaderDoesNotSendGo() = runBlocking {
        val commands = mutableListOf<ByteArray>()
        val f = transferHistory(FetchType.PAI, since, { commands.add(it) }, { control(hex("10010100000000ea070a1f021c0016")) })
        assertEquals(1, commands.size); assertTrue(f.data.isEmpty()); assertNull(f.start)
    }
    @Test fun refusalPreservesReplyAndDoesNotSendGo() = runBlocking {
        val commands = mutableListOf<ByteArray>(); val refusal = hex("100104")
        val f = transferHistory(FetchType.HRV, since, { commands.add(it) }, { control(refusal) })
        assertEquals(1, commands.size); assertArrayEquals(refusal, f.reply); assertTrue(f.data.isEmpty())
    }
    @Test fun counterGapRejectsEntireTransferAndNeverAcknowledges() = runBlocking {
        val received = ArrayDeque(listOf(control(capturedHeader), data(0, 1), data(2, 2)))
        val commands = mutableListOf<ByteArray>()
        try { transferHistory(FetchType.ACTIVITY, since, { commands.add(it) }, { received.removeFirst() }); fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals(2, commands.size); assertFalse(commands.any { it.contentEquals(byteArrayOf(3)) })
    }
    @Test fun cancellationDuringTransferNeverAcknowledges() = runBlocking {
        val commands = mutableListOf<ByteArray>(); var count = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            transferHistory(FetchType.TEMPERATURE, since, { commands.add(it) }, { if (count++ == 0) control(capturedHeader) else awaitCancellation() })
        }
        job.cancelAndJoin(); assertEquals(2, commands.size); assertArrayEquals(byteArrayOf(2), commands.last())
    }
    @Test fun badCompletionDoesNotReturnPartialData() = runBlocking {
        val received = ArrayDeque(listOf(control(capturedHeader), data(0, 1), control(hex("100204"))))
        try { transferHistory(FetchType.STRESS, since, {}, { received.removeFirst() }); fail() } catch (_: IllegalArgumentException) { }
    }
}
