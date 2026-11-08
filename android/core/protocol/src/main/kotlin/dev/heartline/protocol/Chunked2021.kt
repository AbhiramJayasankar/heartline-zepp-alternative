package dev.heartline.protocol

/**
 * Huami's "2021" chunked transfer on characteristics 0016 (phone writes) and 0017 (watch notifies), as in
 * Gadgetbridge's Huami2021ChunkedEncoder/Decoder. The GTR 2e uses the plain (non-extended, unencrypted) header:
 * `03 | flags | handle | count`, and the first chunk adds `u32 length | u16 endpoint`. Flags: 0x01 first, 0x02 last,
 * 0x04 needs an ack, 0x08 encrypted. Acks are `04 handle 01 count`, written to 0017 (see [chunked2021Ack]).
 */
object Endpoint2021 {
    const val ALEXA = 0x0011
    /** Zepp's UserInfoModule (module 23): user info, step goal and wear location on watches without characteristic 0008. */
    const val USER_INFO = 0x0017
}

/**
 * A UserInfoModule write (Zepp 6.3.1 UserInfoParserKt.toBleBytes): 01, a little-endian u32 of flags, then each flagged
 * field in flag order. The watch answers 02 01 when it took it.
 */
private fun userInfo2021(flags: Int, fields: ByteArray): ByteArray =
    byteArrayOf(1, flags.toByte(), (flags shr 8).toByte(), (flags shr 16).toByte(), (flags shr 24).toByte()) + fields
/** Worn on the right or left wrist: (way << 7) | wear, wear 2 = wrist, way 1 = right (FLAG_SENSOR_LOCATION 0x20). */
fun wearLocation2021(right: Boolean): ByteArray = userInfo2021(0x20, byteArrayOf(if (right) 0x82.toByte() else 0x02))
/** Daily step goal as a little-endian u32 (FLAG_STEP_GOAL 0x10). */
fun stepGoal2021(goal: Int): ByteArray = userInfo2021(0x10, byteArrayOf(goal.toByte(), (goal shr 8).toByte(), (goal shr 16).toByte(), (goal shr 24).toByte()))
/**
 * Birth date, sex, height and weight (flags 0x0F) from a classic 4F user-info command ([userInfoCommand]): its bytes 3..11
 * are the same fields in the same order and encoding (year u16, month, day, sex 0/1/2, height u16 cm, weight × 200 u16).
 */
fun userInfo2021FromClassic(classic: ByteArray): ByteArray {
    require(classic.size >= 12 && classic[0] == 0x4F.toByte())
    return userInfo2021(0x0F, classic.copyOfRange(3, 12))
}
/** Whether a UserInfoModule reply says the watch took the write. */
fun userInfo2021Accepted(reply: ByteArray?): Boolean = reply != null && reply.size >= 2 && reply[0] == 2.toByte() && reply[1] == 1.toByte()

fun chunked2021Frames(endpoint: Int, payload: ByteArray, handle: Int, mtu: Int = 23): List<ByteArray> {
    val frames = ArrayList<ByteArray>()
    var offset = 0; var count = 0
    do {
        val header = if (count == 0) 10 else 4
        val room = (mtu - 3 - header).coerceAtLeast(1)
        val n = minOf(room, payload.size - offset)
        var flags = 0
        if (count == 0) flags = flags or 0x01
        if (offset + n >= payload.size) flags = flags or 0x06
        val head = if (count == 0) bytes(3, flags, handle and 255, count and 255,
            payload.size and 255, (payload.size shr 8) and 255, (payload.size shr 16) and 255, (payload.size shr 24) and 255,
            endpoint and 255, (endpoint shr 8) and 255)
            else bytes(3, flags, handle and 255, count and 255)
        frames += head + payload.copyOfRange(offset, offset + n)
        offset += n; count++
    } while (offset < payload.size)
    return frames
}

/**
 * Gadgetbridge sends `04 00 handle 01 count`, but that is the extended (Zepp OS) layout. The GTR 2e's frames have no
 * extended byte and its own acks are `04 handle 01 count` (status 01 ok, 06 refused), so it gets the same layout back;
 * the 5-byte form read as handle 00, left its start unacked and stalled its voice until it timed out.
 */
fun chunked2021Ack(handle: Int, count: Int): ByteArray = bytes(4, handle and 255, 1, count and 255)

/** A complete 2021 message: which endpoint it is for and its payload. */
class Message2021(val endpoint: Int, val payload: ByteArray)

/** Reassembles watch-to-phone frames. [feed] returns the ack to send (if the frame asked for one) and any complete message. */
class Chunked2021Decoder {
    private var handle: Int? = null
    private var endpoint = 0
    private var buffer = java.io.ByteArrayOutputStream()

    class Result(val ack: ByteArray?, val message: Message2021?)

    fun feed(data: ByteArray): Result {
        if (data.size < 4 || data.u8(0) != 3) return Result(null, null)
        val flags = data.u8(1); val h = data.u8(2); val count = data.u8(3)
        val ack = if (flags and 0x04 != 0) chunked2021Ack(h, count) else null
        if (flags and 0x08 != 0) return Result(ack, null) // encrypted: this watch never sends these
        var i = 4
        if (flags and 0x01 != 0) {
            if (data.size < 10) return Result(ack, null)
            endpoint = data.u8(8) or (data.u8(9) shl 8); handle = h; buffer = java.io.ByteArrayOutputStream(); i = 10
        } else if (handle != h) return Result(ack, null)
        buffer.write(data, i, data.size - i)
        if (flags and 0x02 == 0) return Result(ack, null)
        handle = null
        return Result(ack, Message2021(endpoint, buffer.toByteArray()))
    }
}

/**
 * Alexa on endpoint 0x0011. Commands from Gadgetbridge's ZeppOsAssistantService, completed from the Zepp app (10.9.0):
 * 06 is Alexa's StopCapture (Listening to Thinking), 0b its ExpectSpeech (listen again, sent ~600 ms after a reply),
 * 07 shows up to 50 characters of text with a flag, 0a carries spoken audio, 14 / 15 set and clear the notification
 * indicator. Replies under 08 carry a u16 subtype and a u32 length: 1 weather, 2 alerts, 3 list, 4 local actions,
 * 5 audio items, 6 a card; Zepp sends the card only to watches reporting assistant version 3 or more (the GTR 2e
 * reports 2 and shows plain text instead).
 */
object Alexa {
    const val START = 0x01; const val END = 0x02; const val START_ACK = 0x03; const val VOICE = 0x05; const val TRIGGERED = 0x06
    const val SHOW_TEXT = 0x07; const val REPLY_COMPLEX = 0x08; const val REPLY_SIMPLE = 0x09; const val REPLY_VOICE = 0x0a
    const val LISTEN_AGAIN = 0x0b; const val ERROR = 0x0f; const val SET_INDICATOR = 0x14; const val CLEAR_INDICATOR = 0x15
    const val LANGUAGES_REQUEST = 0x10; const val LANGUAGES_RESPONSE = 0x11; const val SET_LANGUAGE = 0x12; const val SET_LANGUAGE_ACK = 0x13
    const val CAPABILITIES_REQUEST = 0x20; const val CAPABILITIES_RESPONSE = 0x21
    const val ALERT_ALARM = 1; const val ALERT_REMINDER = 2; const val ALERT_TIMER = 3
    /** Repeat days for alarms and reminders, as Zepp maps them: bit 0 Monday ... bit 6 Sunday. */
    const val EVERY_DAY = 127; const val WEEKDAYS = 31; const val WEEKENDS = 96
    val startAck: ByteArray get() = bytes(START_ACK, 0)
    /**
     * Opus packets in one voice message: `05 | u32 length | (u8 size, packet)*`. On the GTR 2e every packet is SILK
     * narrowband, 20 ms, mono (TOC 0x08), the same as the raw mic stream; a message carries ten of them.
     */
    fun voicePackets(payload: ByteArray): List<ByteArray> {
        if (payload.size < 5 || payload.u8(0) != VOICE) return emptyList()
        val end = minOf(payload.size, 5 + (payload.u8(1) or (payload.u8(2) shl 8) or (payload.u8(3) shl 16) or (payload.u8(4) shl 24)))
        val out = ArrayList<ByteArray>()
        var i = 5
        while (i < end) {
            val n = payload.u8(i)
            if (n == 0 || i + 1 + n > end) break
            out += payload.copyOfRange(i + 1, i + 1 + n); i += 1 + n
        }
        return out
    }
    fun replySimple(text: String): ByteArray = bytes(REPLY_SIMPLE) + text.toByteArray(Charsets.UTF_8) + bytes(0)
    /** A title / subtitle / body card. */
    fun replyRich(title: String, subtitle: String, text: String): ByteArray {
        val body = title.toByteArray(Charsets.UTF_8) + bytes(0) + subtitle.toByteArray(Charsets.UTF_8) + bytes(0) + text.toByteArray(Charsets.UTF_8) + bytes(0)
        return bytes(REPLY_COMPLEX, 6, 0, body.size and 255, (body.size shr 8) and 255, (body.size shr 16) and 255, (body.size shr 24) and 255) + body
    }
    fun setLanguage(tag: String): ByteArray = bytes(SET_LANGUAGE) + tag.replace('_', '-').toByteArray(Charsets.UTF_8) + bytes(0)
    fun error(code: Int, message: String): ByteArray = bytes(ERROR, code) + message.toByteArray(Charsets.UTF_8) + bytes(0)
    val listenAgain: ByteArray get() = bytes(LISTEN_AGAIN)
    /** Up to 50 characters, e.g. what the speaker said; Zepp sends it with [flag] true for the final text. */
    fun showText(text: String, flag: Boolean = true): ByteArray = bytes(SHOW_TEXT, if (flag) 1 else 0) + z(text.take(50))
    fun indicator(on: Boolean): ByteArray = bytes(if (on) SET_INDICATOR else CLEAR_INDICATOR)

    class Icon(val name: String, val url: String = "")
    class Forecast(val epochSec: Long, val high: String, val low: String, val icon: Icon)

    /**
     * Weather (08/1): a u32 bit mask of the fields present, then the fields in order. Zepp sends 0x3fd: time (local
     * seconds), location, date line, description, icon (name, url), current, high, low, forecast days.
     */
    fun weather(epochSec: Long, location: String, dateLine: String, description: String, icon: Icon, current: String, high: String, low: String,
                forecast: List<Forecast>, tzOffsetSec: Int): ByteArray {
        val b = java.io.ByteArrayOutputStream()
        b.write(u32(0x3fd)); b.write(u32(epochSec + tzOffsetSec)); b.write(z(location)); b.write(z(dateLine)); b.write(z(description))
        b.write(z(icon.name)); b.write(z(icon.url)); b.write(z(current)); b.write(z(high)); b.write(z(low))
        b.write(forecast.size)
        forecast.forEach { f -> b.write(u32(f.epochSec + tzOffsetSec)); b.write(z(f.high)); b.write(z(f.low)); b.write(z(f.icon.name)); b.write(z(f.icon.url)) }
        return complex(1, b.toByteArray())
    }

    /** A list (08/3): title, rows of left and right text, subtitle. */
    fun list(title: String, subtitle: String, rows: List<Pair<String, String>>): ByteArray {
        val b = java.io.ByteArrayOutputStream()
        b.write(z(title)); b.write(rows.size)
        rows.forEach { (left, right) -> b.write(z(left)); b.write(z(right)); b.write(z("")) }
        b.write(z(subtitle))
        return complex(3, b.toByteArray())
    }

    /**
     * An alarm at [localEpochSec] (wall-clock time as seconds), repeating on [days] (0 once). [token] identifies it.
     * Voice version 3 and up (the GTR 2e) end it with an enabled byte, as Zepp's NormalV3Parameter does; without it the
     * watch stores the alarm switched off, so it never rings.
     */
    fun alarm(token: String, localEpochSec: Long, days: Int = 0, enabled: Boolean = true): ByteArray =
        alert(1, ALERT_ALARM, token, z("") + z("") + u32(localEpochSec) + u32(0) + u32(days.toLong()) + bytes(if (enabled) 1 else 0))
    fun reminder(token: String, localEpochSec: Long, label: String, days: Int = 0, enabled: Boolean = true): ByteArray =
        alert(1, ALERT_REMINDER, token, z(label) + z(label) + u32(localEpochSec) + u32(0) + u32(days.toLong()) + bytes(if (enabled) 1 else 0))
    fun timer(token: String, hours: Int, minutes: Int, seconds: Int): ByteArray = alert(1, ALERT_TIMER, token, bytes(hours, minutes, seconds))
    /**
     * Deletes alerts by token. Voice version 3 and up (the GTR 2e reports supportDeleteAlert) want a count before the
     * tokens, as Zepp's AlertContent sends it; without the count the watch acks the frames and keeps the alert.
     */
    fun deleteAlerts(kind: Int, tokens: List<String>): ByteArray =
        complex(2, bytes(2, kind, tokens.size) + tokens.fold(ByteArray(0)) { acc, t -> acc + z(t) })
    fun deleteAlert(kind: Int, token: String): ByteArray = deleteAlerts(kind, listOf(token))

    private fun alert(op: Int, kind: Int, token: String, details: ByteArray) = complex(2, bytes(op, kind) + z(token) + details)
    private fun complex(subtype: Int, body: ByteArray) = bytes(REPLY_COMPLEX, subtype and 255, subtype shr 8) + u32(body.size.toLong()) + body
    private fun z(s: String) = s.toByteArray(Charsets.UTF_8) + bytes(0)
    private fun u32(v: Long) = bytes((v and 255).toInt(), ((v shr 8) and 255).toInt(), ((v shr 16) and 255).toInt(), ((v shr 24) and 255).toInt())
    private fun u32(v: Int) = u32(v.toLong())
}
