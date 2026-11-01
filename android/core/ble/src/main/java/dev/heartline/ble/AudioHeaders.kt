package dev.heartline.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

object AudioHeaders {
    const val SAMPLE_RATE = 48_000
    fun opusHead(preSkip: Int = 0): ByteArray = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        .put("OpusHead".toByteArray(Charsets.US_ASCII)).put(1).put(1).putShort(preSkip.toShort())
        .putInt(SAMPLE_RATE).putShort(0).put(0).array()
    fun nanos(value: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(value).array()
    fun wav(pcmBytes: Long): ByteArray {
        require(pcmBytes in 0..0xffff_ffffL - 36 && pcmBytes % 2L == 0L)
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt((36 + pcmBytes).toInt()).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(pcmBytes.toInt()).array()
    }
    /** server/audio.py:level_dbfs, including the one-count RMS floor. */
    fun dbfs(pcm: ShortArray): Float {
        if (pcm.isEmpty()) return -120f
        val rms = sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)
        return (Math.rint(200 * log10(max(rms, 1.0) / 32768)) / 10).toFloat()
    }
}
