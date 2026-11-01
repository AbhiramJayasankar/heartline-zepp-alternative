package dev.heartline.protocol

data class MicStats(val packets: Int = 0, val gaps: Int = 0, val frames: Int = 0, val errors: Int = 0, val skippedBytes: Int = 0)
data class SplitFrames(val frames: List<ByteArray>, val tail: ByteArray, val skippedBytes: Int)

private fun boundary(buf: ByteArray, i: Int): Boolean {
    if (i + 1 >= buf.size || buf.u8(i) !in 8..120 || buf.u8(i + 1) != 8) return false
    val j = i + 1 + buf.u8(i)
    return j + 1 >= buf.size || (buf.u8(j) in 8..120 && buf.u8(j + 1) == 8)
}

/** Exact audio.split_frames resynchronization, including retaining the final undecidable byte. */
fun splitFrames(buffer: ByteArray): SplitFrames {
    val frames = mutableListOf<ByteArray>()
    var i = 0
    var skipped = 0
    while (i < buffer.size) {
        if ((i + 1 < buffer.size && buffer.u8(i + 1) != 8) || buffer.u8(i) !in 8..120) {
            val start = i
            while (i < buffer.size - 1 && !boundary(buffer, i)) i++
            skipped += i - start
            if (i >= buffer.size - 1) return SplitFrames(frames, buffer.copyOfRange(i, buffer.size), skipped)
        }
        val n = buffer.u8(i)
        if (i + 1 + n > buffer.size) break
        frames.add(buffer.copyOfRange(i + 1, i + 1 + n))
        i += 1 + n
    }
    return SplitFrames(frames, buffer.copyOfRange(i, buffer.size), skipped)
}

/** audio.MicStream.feed_packet, excluding decoding. errors remains zero; decoding is the caller's job. */
class OpusFrameSplitter {
    private var tail = byteArrayOf()
    private var lastCounter: Int? = null
    var stats = MicStats()
        private set

    fun feed(packet: RawPacket.Mic): List<ByteArray> {
        require(packet.counter in 0..255) { "Mic counter must be a byte" }
        val gap = lastCounter != null && packet.counter != ((lastCounter!! + 1) and 255)
        if (gap) tail = byteArrayOf()
        lastCounter = packet.counter
        val split = splitFrames(tail + packet.payload)
        tail = split.tail
        stats = stats.copy(packets = stats.packets + 1, gaps = stats.gaps + if (gap) 1 else 0,
            frames = stats.frames + split.frames.size, skippedBytes = stats.skippedBytes + split.skippedBytes)
        return split.frames
    }
}
