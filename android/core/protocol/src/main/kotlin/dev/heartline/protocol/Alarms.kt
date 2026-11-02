package dev.heartline.protocol

import java.time.DayOfWeek

// AmazfitGTR2eCoordinator inherits HuamiCoordinator; AbstractDeviceCoordinator disables smart wake-up.
const val ALARM_SLOTS = 10
const val ALARM_SMART_WAKE_SUPPORTED = false

/** HuamiSupport.queueAlarm: 02, action|slot, hour, minute, Mon..Sun bits (80 = once, 00 = unused). */
fun alarmCommand(
    slot: Int, enabled: Boolean = false, hour: Int = 0, minute: Int = 0,
    days: Set<DayOfWeek> = emptySet(), unused: Boolean = false,
): ByteArray {
    require(slot in 0 until ALARM_SLOTS)
    require(hour in 0..23 && minute in 0..59)
    if (unused) return bytes(2, slot, 0, 0, 0)
    val mask = if (days.isEmpty()) 0x80 else days.fold(0) { bits, day -> bits or (1 shl (day.value - 1)) }
    // Keep snoozing enabled: queueAlarm only sets action bit 40 when snoozing is disabled.
    return bytes(2, slot or (if (enabled) 0x80 else 0), hour, minute, mask)
}

/** HuamiSupport.queueAlarm documents the success notification as 10 02 01. */
fun alarmWriteAccepted(reply: ByteArray): Boolean = reply.starts(0x10, 0x02, 0x01)
