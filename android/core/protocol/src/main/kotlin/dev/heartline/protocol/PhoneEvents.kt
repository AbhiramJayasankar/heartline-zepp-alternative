package dev.heartline.protocol

enum class PhoneEvent { FIND_START, FIND_STOP, PLAY, PAUSE, NEXT, PREVIOUS, VOLUME_UP, VOLUME_DOWN, MUSIC_OPEN, MUSIC_CLOSE, SILENT_ON, SILENT_OFF }

// HuamiSupport.handleDeviceEvent (HuamiDeviceEvent.FIND_PHONE_*, MUSIC_CONTROL).
fun phoneEvent(data: ByteArray): PhoneEvent? = when (data.firstOrNull()?.toInt()?.and(255)) {
    8 -> PhoneEvent.FIND_START
    15 -> PhoneEvent.FIND_STOP
    // HuamiDeviceEvent.SILENT_MODE: the watch's "mute phone" app.
    0x10 -> if (data.getOrNull(1)?.toInt() == 1) PhoneEvent.SILENT_ON else PhoneEvent.SILENT_OFF
    254 -> when (data.getOrNull(1)?.toInt()?.and(255)) {
        0 -> PhoneEvent.PLAY; 1 -> PhoneEvent.PAUSE; 3 -> PhoneEvent.NEXT; 4 -> PhoneEvent.PREVIOUS
        5 -> PhoneEvent.VOLUME_UP; 6 -> PhoneEvent.VOLUME_DOWN
        224 -> PhoneEvent.MUSIC_OPEN; 225 -> PhoneEvent.MUSIC_CLOSE
        else -> null
    }
    else -> null
}

// HuamiSupport.acknowledgeFindPhone -> AmazfitBipService.COMMAND_ACK_FIND_PHONE_IN_PROGRESS.
val FIND_PHONE_ACK: ByteArray get() = bytes(6, 0x14, 0, 0)

/** HuamiSupport.sendPhoneSilentMode: tells the watch whether the phone is silenced (06 19 00 on/off). */
fun phoneSilentCommand(silent: Boolean): ByteArray = bytes(6, 0x19, 0, if (silent) 1 else 0)

/** HuamiDeviceEvent.WORKOUT_STARTING: `14 ?? needsGps type`, sent when a workout is opened on the watch. */
data class WatchWorkoutStart(val typeCode: Int, val needsGps: Boolean) {
    /** HuamiWorkoutTrackActivityType. */
    val name: String get() = when (typeCode) {
        0x01 -> "Outdoor run"; 0x02 -> "Treadmill"; 0x03 -> "Outdoor cycling"; 0x04 -> "Walking"; 0x05 -> "Pool swimming"
        0x06 -> "Elliptical"; 0x07 -> "Rowing machine"; 0x08 -> "Jump rope"; 0x09 -> "Indoor cycling"; 0x0a -> "Yoga"; 0x0b -> "Freestyle"
        else -> "Workout"
    }
}

fun watchWorkoutStart(data: ByteArray): WatchWorkoutStart? =
    if (data.size >= 4 && data.u8(0) == 0x14) WatchWorkoutStart(data.u8(3), data.u8(2) == 1) else null
