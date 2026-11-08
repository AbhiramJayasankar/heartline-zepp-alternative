package dev.heartline.protocol

import org.junit.Test
import java.time.DayOfWeek.*
import kotlin.test.*

class AlarmsTest {
    @Test fun huamiQueueAlarmEnabledSlotNineOnce() {
        // queueAlarm: action=80 | position=09; non-repetitive=80.
        assertContentEquals(bytes(2, 0x89, 6, 45, 0x80), alarmCommand(9, true, 6, 45))
    }
    @Test fun huamiAlarmMondayThroughSundayBits() {
        assertContentEquals(bytes(2, 0x82, 23, 59, 0x51), alarmCommand(2, true, 23, 59, setOf(MONDAY, FRIDAY, SUNDAY)))
        assertEquals(0x7f, alarmCommand(0, days = java.time.DayOfWeek.values().toSet()).u8(4))
    }
    @Test fun disabledAlarmRetainsTimeAndRepetition() {
        assertContentEquals(bytes(2, 3, 8, 12, 2), alarmCommand(3, false, 8, 12, setOf(TUESDAY)))
    }
    @Test fun huamiUnusedAlarmClearsEnableDaysAndTime() {
        // queueAlarm's unused branch sets actionMask=daysMask=0; use midnight for the unused Calendar.
        assertContentEquals(bytes(2, 7, 0, 0, 0), alarmCommand(7, true, 10, 20, setOf(MONDAY), unused = true))
    }
    @Test fun gtr2eInheritsTenSlotsAndNoSmartWakeup() {
        assertEquals(10, ALARM_SLOTS)
        assertFalse(ALARM_SMART_WAKE_SUPPORTED)
    }
    @Test fun invalidSlotOrTimeIsRejected() {
        for (slot in listOf(-1, 10)) assertFailsWith<IllegalArgumentException> { alarmCommand(slot) }
        for ((hour, minute) in listOf(-1 to 0, 24 to 0, 0 to -1, 0 to 60))
            assertFailsWith<IllegalArgumentException> { alarmCommand(0, hour = hour, minute = minute) }
    }
    @Test fun queueAlarmSuccessIs100201IncludingStatusByte() {
        assertTrue(alarmWriteAccepted(bytes(0x10, 2, 1)))
        for (reply in listOf(byteArrayOf(), bytes(0x10, 2), bytes(0x10, 2, 0), bytes(0x10, 3, 1), bytes(0x11, 2, 1)))
            assertFalse(alarmWriteAccepted(reply))
    }

    @Test fun readsTheWatchesAlarmSwitches() {
        // The user's GTR 2e (2026-11-08): Heartline's slot 0 on, four alarms made on the watch (10 to 13) off.
        val reply = byteArrayOf(0x10, 0x0d, 0x01, 0x03, 0x0b, 0x80.toByte(), 0x66, 0x02, 0x05, 0x10, 0x0a, 0x0b, 0x0c, 0x0d)
        assertEquals(listOf(WatchAlarmSwitch(0, true), WatchAlarmSwitch(10, false), WatchAlarmSwitch(11, false), WatchAlarmSwitch(12, false), WatchAlarmSwitch(13, false)),
            parseAlarmSwitches(reply))
        assertEquals(emptyList(), parseAlarmSwitches(byteArrayOf(0x10, 0x0d, 0x01, 0x03, 0x0b, 0x80.toByte(), 0x66, 0x02, 0x00)))
        assertEquals(null, parseAlarmSwitches(byteArrayOf(0x10, 0x0d, 0x02)))
    }

    @Test fun watchMadeSlotsCanOnlyBeCleared() {
        assertContentEquals(byteArrayOf(2, 12, 0, 0, 0), alarmCommand(12, unused = true))
        kotlin.test.assertFailsWith<IllegalArgumentException> { alarmCommand(12, enabled = true, hour = 7) }
    }
}
