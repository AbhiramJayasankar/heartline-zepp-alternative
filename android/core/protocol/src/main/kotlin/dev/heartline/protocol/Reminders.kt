package dev.heartline.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId

// AmazfitGTR2eCoordinator inherits both limits from HuamiCoordinator.
const val REMINDER_SLOTS = 22
const val REMINDER_TITLE_LENGTH = 16

data class CalendarReminder(val title: String, val start: Long, val end: Long)

fun reminderTitle(title: String): String {
    val text = title.replace('\u0000', ' ').trim().take(REMINDER_TITLE_LENGTH)
    // HuamiCoordinator's limit counts UTF-16 characters; do not split an emoji at the boundary.
    return if (text.lastOrNull()?.isHighSurrogate() == true) text.dropLast(1) else text
}

/** HuamiSupport.sendCalendarEventsAsReminders, short dates from BLETypeConversions.shortCalendarToRawBytes. */
fun calendarReminderCommand(slot: Int, event: CalendarReminder?, zone: ZoneId): ByteArray {
    require(slot in 0 until REMINDER_SLOTS)
    val title = event?.let { reminderTitle(it.title).toByteArray(Charsets.UTF_8) } ?: byteArrayOf()
    val out = ByteBuffer.allocate(20 + title.size).order(ByteOrder.LITTLE_ENDIAN)
    out.put(0x0b).put(slot.toByte()).putInt(if (event == null) 0x08 else 0x0d)
    if (event == null) out.put(ByteArray(12)) else {
        require(event.end >= event.start)
        for (epoch in listOf(event.start, event.end)) {
            val time = Instant.ofEpochSecond(epoch).atZone(zone)
            require(time.year in 1..65535)
            out.putShort(time.year.toShort()).put(time.monthValue.toByte()).put(time.dayOfMonth.toByte())
                .put(time.hour.toByte()).put(time.minute.toByte())
        }
    }
    out.put(0).put(title).put(0)
    return out.array()
}
