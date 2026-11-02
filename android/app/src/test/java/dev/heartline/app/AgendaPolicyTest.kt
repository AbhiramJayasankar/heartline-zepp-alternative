package dev.heartline.app

import dev.heartline.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class AgendaPolicyTest {
    private val now = Instant.parse("2026-11-02T00:00:00Z")
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun event(id: Long, seconds: Long, calendar: Long = 1, title: String = "Event") =
        CalendarInstance(id, calendar, title, (now.epochSecond + seconds) * 1000, (now.epochSecond + seconds + 3600) * 1000, false)

    @Test fun soonestSelectedInstancesWithinFourteenDaysCappedAtTwentyTwo() {
        val events = (30L downTo 1L).map { event(it, it * 3600) } + event(40, -1) + event(41, 14 * 86400L) + event(42, 2, 2)
        val reminders = upcomingReminders(events, setOf(1), now, zone)
        assertEquals(22, reminders.size)
        assertEquals(now.epochSecond + 3600, reminders.first().start)
        assertEquals(now.epochSecond + 22 * 3600, reminders.last().start)
    }
    @Test fun recurrenceInstancesStayDistinctButProviderDuplicatesAreRemoved() {
        val a = event(1, 60)
        val b = event(1, 86400)
        assertEquals(2, upcomingReminders(listOf(a, a, b), setOf(1), now, zone).size)
    }
    @Test fun allDayUsesUtcDateAtNineLocalIncludingMultidayEnd() {
        val begin = Instant.parse("2026-11-03T00:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-11-05T00:00:00Z").toEpochMilli()
        val reminder = upcomingReminders(listOf(CalendarInstance(1, 1, "Holiday", begin, end, true)), setOf(1), now, zone).single()
        assertEquals(LocalDate.of(2026, 11, 3).atTime(9, 0).atZone(zone).toEpochSecond(), reminder.start)
        assertEquals(LocalDate.of(2026, 11, 5).atStartOfDay(zone).toEpochSecond(), reminder.end)
    }
    @Test fun allDayKeepsItsDateWestOfUtcAndHonorsDst() {
        val west = ZoneId.of("America/New_York")
        val day = Instant.parse("2026-11-02T00:00:00Z").toEpochMilli()
        val reminder = upcomingReminders(listOf(CalendarInstance(1, 1, "Day", day, day + 86400000, true)), setOf(1), now, west).single()
        assertEquals(Instant.parse("2026-11-02T14:00:00Z").epochSecond, reminder.start)
    }
    @Test fun emptySelectionTitlesAndTiesAreDeterministic() {
        assertTrue(upcomingReminders(listOf(event(1, 60)), emptySet(), now, zone).isEmpty())
        val reminders = upcomingReminders(listOf(event(2, 60, title = "Z"), event(1, 60, title = " A very long calendar title ")), setOf(1), now, zone)
        assertEquals("A very long cale", reminders.first().title)
        assertEquals("Z", reminders.last().title)
    }
    @Test fun slotsEncodeSavedDisabledAndDeletedAlarmsAndNormalizeUnsupportedSmartFlag() {
        val alarm = normalizedAlarm(AlarmUi(9, false, 7, 15, setOf(DayOfWeek.FRIDAY), true))
        assertFalse(alarm.smart)
        val slots = alarmSlots(listOf(alarm))
        assertEquals(10, slots.size)
        assertArrayEquals(byteArrayOf(2, 9, 7, 15, 16), slots[9])
        assertArrayEquals(byteArrayOf(2, 9, 0, 0, 0), alarmSlots(emptyList())[9])
    }
    @Test fun changedSlotsAreAcknowledgedIndividuallyAndSurviveReconnect() {
        val desired = (0 until 22).map { calendarReminderCommand(it, null, zone) }
        val slots = AgendaSlots()
        assertEquals((0 until 22).toList(), slots.changed(desired))
        slots.written(0, desired[0]); slots.written(1, desired[1])
        // A link loss after slot 1 must leave the rest pending, even after reloading persisted state.
        val restored = AgendaSlots(slots.snapshot().toMutableMap())
        assertEquals((2 until 22).toList(), restored.changed(desired))
        desired.forEachIndexed { slot, payload -> restored.written(slot, payload) }
        assertTrue(restored.changed(desired).isEmpty())
        val changed = desired.toMutableList()
        changed[3] = calendarReminderCommand(3, CalendarReminder("New", now.epochSecond, now.epochSecond + 60), zone)
        assertEquals(listOf(3), restored.changed(changed))
        assertEquals(22, restored.changed(changed, force = true).size)
    }
    @Test fun deletingOrDisablingRemindersOnlyClearsPreviouslyOccupiedSlots() {
        val blank = (0 until 22).map { calendarReminderCommand(it, null, zone) }
        val active = blank.toMutableList()
        active[0] = calendarReminderCommand(0, CalendarReminder("Event", now.epochSecond, now.epochSecond + 60), zone)
        val slots = AgendaSlots()
        active.forEachIndexed { slot, bytes -> slots.written(slot, bytes) }
        assertEquals(listOf(0), slots.changed(blank))
    }
    @Test fun offlineAlarmEditAndDeletionRemainPendingUntilExactBytesAreWritten() {
        val slots = AgendaSlots()
        val original = alarmSlots(listOf(AlarmUi(2, true, 7, 0, emptySet())))
        original.forEachIndexed { slot, bytes -> slots.written(slot, bytes) }
        val edited = alarmSlots(listOf(AlarmUi(2, true, 8, 0, emptySet())))
        assertEquals(listOf(2), slots.changed(edited))
        slots.written(2, original[2]) // Completion of an older write cannot acknowledge the newer edit.
        assertEquals(listOf(2), slots.changed(edited))
        slots.written(2, edited[2])
        assertTrue(slots.changed(edited).isEmpty())
        assertEquals(listOf(2), slots.changed(alarmSlots(emptyList())))
        assertEquals(10, slots.changed(edited, force = true).size)
    }
}
