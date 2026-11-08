package dev.heartline.protocol

import java.time.DayOfWeek

// AmazfitGTR2eCoordinator inherits HuamiCoordinator; AbstractDeviceCoordinator disables smart wake-up.
const val ALARM_SLOTS = 10
/**
 * The watch numbers alarms 0 to 15 (4 bits). Heartline writes 0 to 9; alarms made on the watch itself took 10 and up
 * on the user's GTR 2e (2026-11-08: switches 0, 10, 11, 12, 13).
 */
const val WATCH_ALARM_SLOT_MAX = 15
const val ALARM_SMART_WAKE_SUPPORTED = false

/** HuamiSupport.queueAlarm: 02, action|slot, hour, minute, Mon..Sun bits (80 = once, 00 = unused). */
fun alarmCommand(
    slot: Int, enabled: Boolean = false, hour: Int = 0, minute: Int = 0,
    days: Set<DayOfWeek> = emptySet(), unused: Boolean = false,
): ByteArray {
    require(slot in 0..WATCH_ALARM_SLOT_MAX)
    require(hour in 0..23 && minute in 0..59)
    if (unused) return bytes(2, slot, 0, 0, 0)
    require(slot < ALARM_SLOTS) { "Heartline sets alarms in slots 0 to ${ALARM_SLOTS - 1}" }
    val mask = if (days.isEmpty()) 0x80 else days.fold(0) { bits, day -> bits or (1 shl (day.value - 1)) }
    // Keep snoozing enabled: queueAlarm only sets action bit 40 when snoozing is disabled.
    return bytes(2, slot or (if (enabled) 0x80 else 0), hour, minute, mask)
}

/** HuamiSupport.queueAlarm documents the success notification as 10 02 01. */
fun alarmWriteAccepted(reply: ByteArray): Boolean = reply.starts(0x10, 0x02, 0x01)

/** Asks the watch for its settings summary on CONFIG; the answer ends with every alarm's slot and switch. */
val ALARM_QUERY: ByteArray get() = bytes(0x0d)

/** One alarm the watch holds: its [slot] and whether it is switched [on]. The watch doesn't say its time. */
data class WatchAlarmSwitch(val slot: Int, val on: Boolean)

/**
 * The alarms in the watch's answer to [ALARM_QUERY]: `10 0d 01`, then Zepp's settings (MiLiProProfile.getDeviceSettingsNew:
 * version, u16 flags, watch face, a language byte from version 3), the alarm count and one byte per alarm, slot in the
 * low 4 bits and 0x10 when on. Null when the answer isn't that.
 */
fun parseAlarmSwitches(reply: ByteArray?): List<WatchAlarmSwitch>? {
    if (reply == null || reply.size < 8 || !reply.starts(0x10, 0x0d, 0x01)) return null
    val data = reply.copyOfRange(3, reply.size)
    val at = if (data.u8(0) >= 3) 5 else 4
    if (data.size <= at) return null
    val count = data.u8(at)
    if (data.size < at + 1 + count) return null
    return (0 until count).map { i -> data.u8(at + 1 + i).let { WatchAlarmSwitch(it and 15, it and 0x10 != 0) } }
}
