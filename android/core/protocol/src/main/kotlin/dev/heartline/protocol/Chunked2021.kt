package dev.heartline.protocol

/**
 * Huami's "2021" chunked transfer on characteristics 0016 (phone writes) and 0017 (watch notifies), as in
 * Gadgetbridge's Huami2021ChunkedEncoder/Decoder. The GTR 2e uses the plain (non-extended, unencrypted) header:
 * `03 | flags | handle | count`, and the first chunk adds `u32 length | u16 endpoint`. Flags: 0x01 first, 0x02 last,
 * 0x04 needs an ack, 0x08 encrypted. Acks are `04 00 handle 01 count`, written to 0017.
 */
object Endpoint2021 { const val ALEXA = 0x0011 }

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

fun chunked2021Ack(handle: Int, count: Int): ByteArray = bytes(4, 0, handle and 255, 1, count and 255)

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

/** Alexa on endpoint 0x0011 (Gadgetbridge ZeppOsAlexaService). */
object Alexa {
    const val START = 0x01; const val END = 0x02; const val START_ACK = 0x03; const val VOICE = 0x05
    const val REPLY_COMPLEX = 0x08; const val REPLY_SIMPLE = 0x09; const val ERROR = 0x0f
    val startAck: ByteArray get() = bytes(START_ACK, 0)
    fun replySimple(text: String): ByteArray = bytes(REPLY_SIMPLE) + text.toByteArray(Charsets.UTF_8) + bytes(0)
    /** A title / subtitle / body card. */
    fun replyRich(title: String, subtitle: String, text: String): ByteArray {
        val body = title.toByteArray(Charsets.UTF_8) + bytes(0) + subtitle.toByteArray(Charsets.UTF_8) + bytes(0) + text.toByteArray(Charsets.UTF_8) + bytes(0)
        return bytes(REPLY_COMPLEX, 6, 0, body.size and 255, (body.size shr 8) and 255, (body.size shr 16) and 255, (body.size shr 24) and 255) + body
    }
    fun error(code: Int, message: String): ByteArray = bytes(ERROR, code) + message.toByteArray(Charsets.UTF_8) + bytes(0)
}
