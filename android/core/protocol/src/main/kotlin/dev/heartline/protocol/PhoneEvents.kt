package dev.heartline.protocol

enum class PhoneEvent { FIND_START, FIND_STOP, PLAY, PAUSE, NEXT, PREVIOUS, VOLUME_UP, VOLUME_DOWN, MUSIC_OPEN, MUSIC_CLOSE }

// HuamiSupport.handleDeviceEvent (HuamiDeviceEvent.FIND_PHONE_*, MUSIC_CONTROL).
fun phoneEvent(data: ByteArray): PhoneEvent? = when (data.firstOrNull()?.toInt()?.and(255)) {
    8 -> PhoneEvent.FIND_START
    15 -> PhoneEvent.FIND_STOP
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
