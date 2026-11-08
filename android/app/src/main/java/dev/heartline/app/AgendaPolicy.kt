package dev.heartline.app

import dev.heartline.protocol.*
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

internal data class CalendarInstance(
    val id: Long, val calendar: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean,
)

internal fun normalizedAlarm(alarm: AlarmUi): AlarmUi {
    alarmCommand(alarm.slot, alarm.enabled, alarm.hour, alarm.minute, alarm.days)
    return alarm.copy(smart = false, days = alarm.days.toSet())
}

internal fun alarmSlots(alarms: List<AlarmUi>): List<ByteArray> {
    val bySlot = alarms.associateBy { it.slot }
    return (0 until ALARM_SLOTS).map { slot ->
        bySlot[slot]?.let { alarmCommand(slot, it.enabled, it.hour, it.minute, it.days) } ?: alarmCommand(slot, unused = true)
    }
}

private fun hexOf(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

/**
 * Heartline's [stored] alarms after reading the watch's [switches]: an alarm switched on or off, or deleted, on the watch
 * since Heartline last wrote it ([written], slot to hex) follows the watch, so the next sync doesn't undo it. An alarm
 * changed in the app and not written yet keeps the app's version. Returns the new list and the writes to record.
 */
internal fun followWatchAlarms(stored: List<AlarmUi>, written: Map<Int, String>, switches: List<WatchAlarmSwitch>): Pair<List<AlarmUi>, Map<Int, ByteArray>> {
    val onWatch = switches.associateBy { it.slot }
    val record = LinkedHashMap<Int, ByteArray>()
    val next = stored.mapNotNull { a ->
        if (written[a.slot] != hexOf(alarmCommand(a.slot, a.enabled, a.hour, a.minute, a.days))) return@mapNotNull a
        val s = onWatch[a.slot]
        when {
            s == null -> { record[a.slot] = alarmCommand(a.slot, unused = true); null }
            s.on != a.enabled -> a.copy(enabled = s.on).also { record[a.slot] = alarmCommand(a.slot, it.enabled, it.hour, it.minute, it.days) }
            else -> a
        }
    }
    return next to record
}

/** The first of Heartline's slots that neither Heartline nor the watch is using, or null when all are taken. */
internal fun freeAlarmSlot(stored: List<AlarmUi>, switches: List<WatchAlarmSwitch>): Int? =
    (0 until ALARM_SLOTS).firstOrNull { slot -> stored.none { it.slot == slot } && switches.none { it.slot == slot } }

internal fun upcomingReminders(
    instances: List<CalendarInstance>, selected: Set<Long>, now: Instant, zone: ZoneId,
): List<CalendarReminder> {
    val end = now.plusSeconds(14 * 86400L).epochSecond
    return instances.asSequence().filter { it.calendar in selected }.distinctBy { Triple(it.calendar, it.id, it.begin) }
        .map { event ->
            // Android represents all-day dates at UTC midnight. Keep the date, schedule at 09:00 local.
            val start = if (event.allDay) Instant.ofEpochMilli(event.begin).atZone(ZoneOffset.UTC).toLocalDate().atTime(9, 0).atZone(zone).toEpochSecond()
                else event.begin / 1000
            val finish = if (event.allDay) Instant.ofEpochMilli(event.end).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).toEpochSecond()
                else event.end / 1000
            CalendarReminder(reminderTitle(event.title), start, maxOf(start, finish))
        }.filter { it.start >= now.epochSecond && it.start < end }
        .sortedWith(compareBy<CalendarReminder> { it.start }.thenBy { it.end }.thenBy { it.title })
        .take(REMINDER_SLOTS).toList()
}

/** Desired bytes differ from the last completed write until an offline edit/deletion has reached the watch. */
internal class AgendaSlots(private val completed: MutableMap<Int, String> = mutableMapOf()) {
    fun changed(desired: List<ByteArray>, force: Boolean = false): List<Int> = desired.indices.filter { force || completed[it] != hex(desired[it]) }
    fun written(slot: Int, bytes: ByteArray) { completed[slot] = hex(bytes) }
    fun snapshot(): Map<Int, String> = completed.toMap()
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
