package dev.heartline.protocol

import org.junit.Test
import java.time.*
import kotlin.test.*

class RemindersTest {
    private val utc = ZoneOffset.UTC
    private val event = CalendarReminder("Run", Instant.parse("2026-11-02T07:30:00Z").epochSecond, Instant.parse("2026-11-02T08:45:00Z").epochSecond)

    @Test fun huamiCalendarEventHasSlotFlagsTwoShortDatesAndNullTerminatedTitle() {
        // sendCalendarEventsAsReminders: 0b, id, LE32(08|04|01), two 6-byte dates, 00,title,00.
        assertContentEquals(bytes(0x0b, 21, 0x0d, 0, 0, 0, 0xea, 7, 11, 2, 7, 30, 0xea, 7, 11, 2, 8, 45, 0, 82, 117, 110, 0),
            calendarReminderCommand(21, event, utc))
    }
    @Test fun huamiCalendarDeletionUsesFlags08AndFourteenZeroBytes() {
        assertContentEquals(bytes(0x0b, 3, 8, 0, 0, 0) + ByteArray(14), calendarReminderCommand(3, null, utc))
    }
    @Test fun datesUseWatchLocalZoneAndCrossMidnight() {
        val payload = calendarReminderCommand(0, event, ZoneId.of("Pacific/Honolulu"))
        assertContentEquals(bytes(0xea, 7, 11, 1, 21, 30, 0xea, 7, 11, 1, 22, 45), payload.copyOfRange(6, 18))
    }
    @Test fun inheritedHuamiLimitsAndUtf8Title() {
        assertEquals(22, REMINDER_SLOTS)
        assertEquals(16, REMINDER_TITLE_LENGTH)
        val payload = calendarReminderCommand(0, event.copy(title = "é".repeat(20)), utc)
        assertEquals(52, payload.size) // 20 header/date/terminator bytes + 16 * 2 UTF-8 bytes.
        assertEquals("é".repeat(16), payload.copyOfRange(19, payload.size - 1).toString(Charsets.UTF_8))
    }
    @Test fun trimNullAndAvoidSplitSurrogateAtSixteenUtf16Characters() {
        assertEquals("A B", reminderTitle("  A\u0000B  "))
        assertEquals("x".repeat(15), reminderTitle("x".repeat(15) + "😀"))
        assertEquals("x".repeat(14) + "😀", reminderTitle("x".repeat(14) + "😀tail"))
    }
    @Test fun typeTwoChunksUseMtu23WithoutNotificationExtraHeader() {
        val payload = calendarReminderCommand(3, null, utc)
        val chunks = chunkedWrites(payload, mtu = 23, type = 2)
        // HuamiSupport.writeToChunkedOld: 17 payload bytes, first flags=02, last flags=82.
        assertContentEquals(bytes(0, 2, 0) + payload.copyOfRange(0, 17), chunks[0])
        assertContentEquals(bytes(0, 0x82, 1, 0, 0, 0), chunks[1])
        assertEquals(2, chunks.size)
    }
    @Test fun invalidSlotAndReverseTimesRejected() {
        for (slot in listOf(-1, 22)) assertFailsWith<IllegalArgumentException> { calendarReminderCommand(slot, event, utc) }
        assertFailsWith<IllegalArgumentException> { calendarReminderCommand(0, event.copy(end = event.start - 1), utc) }
    }
}
