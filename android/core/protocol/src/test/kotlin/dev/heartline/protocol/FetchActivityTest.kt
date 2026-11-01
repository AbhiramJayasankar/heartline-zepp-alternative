package dev.heartline.protocol

import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Test
import kotlin.test.*

class FetchActivityTest {
    @Test fun fetchTimeAndCommand() {
        val t = ZonedDateTime.of(2026, 10, 31, 2, 28, 59, 0, ZoneId.of("Asia/Kolkata"))
        assertEquals("ea070a1f021c0016", activityTimeBytes(t).hex())
        assertEquals("0101ea070a1f021c0016", fetchStartCommand(1, t).hex())
        assertEquals("0102ea070a1f021c0016", fetchStartCommand(FetchType.MANUAL_HR.code, t).hex())
        assertFailsWith<IllegalArgumentException> { fetchStartCommand(256, t) }
    }
    @Test fun fetchStartCaptured() {
        val start = parseFetchStart(hex("1001014a020000ea070a1f021c0016"), ZoneOffset.UTC)!!
        assertEquals(586L, start.count)
        assertEquals(ZonedDateTime.parse("2026-10-31T02:28:00+05:30").toEpochSecond(), start.startEpoch)
        assertEquals(FetchStart(0, null), parseFetchStart(hex("10010100000000ea070a1f021c0016"), ZoneOffset.UTC))
    }
    @Test fun fetchRefusalsMalformedAndUnsignedCount() {
        assertNull(parseFetchStart(hex("100104"), ZoneOffset.UTC))
        assertNull(parseFetchStart(byteArrayOf(), ZoneOffset.UTC))
        assertEquals(FetchStart(0, null), parseFetchStart(hex("100101"), ZoneOffset.UTC))
        assertEquals(FetchStart(0xFFFFFFFFL, null), parseFetchStart(hex("100101ffffffff"), ZoneOffset.UTC))
        assertNull(parseFetchStart(hex("10010101000000ea070d1f021c0016"), ZoneOffset.UTC)!!.startEpoch)
    }
    @Test fun fetchWireOffsetOverridesCallerZone() {
        val zero = parseFetchStart(hex("10010101000000ea07010100000000"), ZoneId.of("Asia/Kolkata"))!!
        assertEquals(ZonedDateTime.parse("2026-01-01T00:00:00Z").toEpochSecond(), zero.startEpoch)
        val noOffset = parseFetchStart(hex("10010101000000ea070101000000"), ZoneId.of("America/New_York"))!!
        assertEquals(zero.startEpoch, noOffset.startEpoch)
        val negative = parseFetchStart(hex("10010101000000ea070101000000ea"), ZoneOffset.UTC)!!
        assertEquals(zero.startEpoch!! + 19800, negative.startEpoch)
    }
    @Test fun assemblerCountersPayloadAndEndPrefix() {
        val a = FetchAssembler()
        assertFalse(a.feedControl(hex("10010101000000")))
        a.feedData(bytes(254, 1, 2))
        a.feedData(bytes(255, 3))
        a.feedData(bytes(0, 4, 5))
        assertFalse(a.complete)
        assertTrue(a.feedControl(hex("100201aabb")))
        assertTrue(a.complete)
        assertContentEquals(bytes(1, 2, 3, 4, 5), a.payload)
        a.payload.fill(0)
        assertContentEquals(bytes(1, 2, 3, 4, 5), a.payload)
        assertContentEquals(bytes(2), FETCH_GO)
    }
    @Test fun assemblerGapIsRejectedUntilReset() {
        val a = FetchAssembler()
        a.feedData(bytes(0, 1))
        assertEquals("Fetch counter gap: expected 1, got 2", assertFailsWith<IllegalArgumentException> { a.feedData(bytes(2, 2)) }.message)
        assertContentEquals(bytes(1), a.payload)
        assertFailsWith<IllegalStateException> { a.feedData(bytes(1, 3)) }
        assertFailsWith<IllegalStateException> { a.feedControl(hex("100201")) }
        a.reset()
        assertContentEquals(byteArrayOf(), a.payload)
        assertFalse(a.complete)
        a.feedData(bytes(10, 9))
        assertTrue(a.feedControl(hex("100201")))
        assertContentEquals(bytes(9), a.payload)
    }
    @Test fun assemblerDuplicateCounterAndBadEnd() {
        val a = FetchAssembler()
        a.feedData(bytes(7))
        assertFailsWith<IllegalArgumentException> { a.feedData(bytes(7)) }
        a.reset()
        assertEquals("Transfer ended with 100204", assertFailsWith<IllegalArgumentException> { a.feedControl(hex("100204")) }.message)
        a.reset()
        assertFailsWith<IllegalArgumentException> { a.feedData(byteArrayOf()) }
        a.feedData(bytes(0, 0x10, 2, 1))
        assertFalse(a.complete) // marker bytes inside ACT_DATA are just payload
        a.feedControl(hex("100201"))
        assertFailsWith<IllegalStateException> { a.feedData(bytes(1)) }
    }
    @Test fun activityCapturedSamples() {
        val rows = parseActivity(bytes(6, 0, 0, 255, 5, 31, 128, 128, 240, 8, 3, 57, 5, 62, 159, 166), 1000)
        assertEquals(listOf(960L, 1020L), rows.map { it.ts })
        assertEquals(ActivityMinute(1020, 240, 8, 3, 57, 5, 62, 159, 166), rows[1])
        assertEquals(255, rows[0].hr)
    }
    @Test fun activityIncompleteTailsAndNegativeTimestamp() {
        assertEquals(1, parseActivity(ByteArray(15), 120).size)
        assertEquals(0, parseActivity(ByteArray(7), 120).size)
        assertEquals(-60L, parseActivity(ByteArray(8), -1).single().ts)
    }
    @Test fun activityStatesPythonVector() {
        val rows = listOf(row(240), row(6, 255), row(240, 255), row(3, 255), row(9), row(9, rem = 188), row(11), row(1), row(7), row(7, 255))
        assertEquals(listOf(ActivityState.IDLE, ActivityState.CHARGING, ActivityState.CHARGING, ActivityState.NOT_WORN,
            ActivityState.SLEEP_LIGHT, ActivityState.SLEEP_REM, ActivityState.SLEEP_DEEP, ActivityState.ACTIVE,
            ActivityState.IDLE, ActivityState.UNKNOWN), activityStates(rows))
        assertEquals(listOf(ActivityState.SLEEP_DEEP), activityStates(listOf(row(0)), 11))
    }
    @Test fun activityKindRepeatRemAndFallbackBoundaries() {
        assertEquals(listOf(ActivityState.ACTIVE, ActivityState.ACTIVE, ActivityState.ACTIVE), activityStates(listOf(row(0xF2), row(10), row(0))))
        assertEquals(listOf(ActivityState.ACTIVE, ActivityState.ACTIVE), activityStates(listOf(row(4), row(12))))
        assertEquals(listOf(ActivityState.SLEEP_LIGHT, ActivityState.SLEEP_REM, ActivityState.SLEEP_LIGHT), activityStates(listOf(row(9, rem = 55), row(10, rem = 56), row(0, rem = 128))))
        assertEquals(listOf(ActivityState.UNKNOWN, ActivityState.IDLE, ActivityState.IDLE, ActivityState.UNKNOWN, ActivityState.UNKNOWN),
            activityStates(listOf(row(7, 0), row(7, 1), row(7, 254), row(7, 255), row(7, null))))
    }
    @Test fun fetchTypeCodesAndConsumedFlags() {
        val expected = mapOf("activity" to 1, "manual_hr" to 2, "workouts" to 5, "workout_details" to 6, "pai" to 13,
            "stress_manual" to 18, "stress" to 19, "spo2" to 37, "spo2_sleep" to 38, "statistics" to 44,
            "temperature" to 46, "sleep_respiratory_rate" to 56, "resting_hr" to 58, "max_hr" to 61, "sleep_session" to 72, "hrv" to 73)
        assertEquals(expected, FetchType.entries.associate { it.key to it.code })
        assertEquals(setOf("temperature", "stress", "stress_manual", "spo2", "spo2_sleep", "statistics", "sleep_respiratory_rate", "resting_hr", "max_hr", "sleep_session", "hrv"),
            FetchType.entries.filter { it.consumedOnTransfer }.map { it.key }.toSet())
    }
    private fun row(kind: Int, hr: Int? = 60, rem: Int = 0) = ActivityMinute(0, kind, 0, 0, hr, 0, 0, 0, rem)
}
