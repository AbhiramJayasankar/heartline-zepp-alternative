package dev.heartline.protocol

data class StepsNow(val steps: Int, val distanceM: Int?, val calories: Int?)
fun parseRealtimeSteps(data: ByteArray): StepsNow? = if (data.size < 13) null
    else StepsNow(data.u32(1).toInt(), data.u32(5).toInt(), data.u32(9).toInt())

val DEVICE_EVENTS: Map<Int, String> = mapOf(
    0x01 to "fell_asleep", 0x02 to "woke_up", 0x03 to "steps_goal_reached", 0x04 to "button_pressed",
    0x06 to "start_nonwear", 0x07 to "call_reject", 0x08 to "find_phone_start", 0x09 to "call_ignore",
    0x0A to "alarm_toggled", 0x0B to "button_pressed_long", 0x0E to "tick_30min", 0x0F to "find_phone_stop",
    0x10 to "silent_mode", 0x14 to "workout_starting", 0x16 to "mtu_request", 0x1A to "alarm_changed", 0xFE to "music_control",
)
val MUSIC_COMMANDS: Map<Int, String> = mapOf(0xE0 to "music_open", 0xE1 to "music_close", 0 to "play", 1 to "pause",
    3 to "next", 4 to "previous", 5 to "volume_up", 6 to "volume_down")

fun deviceEventName(data: ByteArray): String {
    if (data.isEmpty()) return "empty"
    val name = DEVICE_EVENTS[data.u8(0)] ?: "unknown_${data.copyOfRange(0, 1).hex()}"
    return if (data.u8(0) == 0xFE && data.size > 1)
        name + ":" + (MUSIC_COMMANDS[data.u8(1)] ?: data.copyOfRange(1, 2).hex()) else name
}
