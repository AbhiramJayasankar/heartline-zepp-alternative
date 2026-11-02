package dev.heartline.protocol

import org.junit.Assert.*
import org.junit.Test

class RestingHeartRateTest {
    @Test fun gadgetbridgeSixByteLayoutWithSignedQuarterHourOffsets() {
        // FetchHeartRateRestingOperation.handleActivityData: getInt LE, get signed byte, get unsigned HR.
        val rows = parseRestingHeartRate(byteArrayOf(0, 0xF1.toByte(), 0x53, 0x65, 22, 58,
            0x80.toByte(), 0x42, 0x55, 0x65, (-16).toByte(), 62))
        assertEquals(listOf(1700000000L, 1700086400L), rows.map { it.ts })
        assertEquals(listOf(58.0, 62.0), rows.map { it.value })
        assertEquals("19800", rows[0].extra["utc_offset_seconds"])
        assertEquals("-14400", rows[1].extra["utc_offset_seconds"])
    }
    @Test fun unsignedEpochAndInvalidBpmSentinels() {
        val rows = parseRestingHeartRate(byteArrayOf(-1, -1, -1, -1, 0, 70, 1, 0, 0, 0, 0, -1, 2, 0, 0, 0, 0, 0))
        assertEquals(4294967295L, rows.single().ts)
        assertEquals(70.0, rows.single().value)
        assertTrue(parseRestingHeartRate(byteArrayOf()).isEmpty())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsPartialRecordLikeGadgetbridge() {
        parseRestingHeartRate(byteArrayOf(0, 1, 2, 3, 4))
    }
}
