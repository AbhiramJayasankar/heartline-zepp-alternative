package dev.heartline.ble

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioHeadersTest {
    @Test fun opusIdentificationHeaderMatchesMono48k() {
        val b = ByteBuffer.wrap(AudioHeaders.opusHead()).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(8); b.get(magic)
        assertEquals("OpusHead", String(magic)); assertEquals(19, b.capacity())
        assertEquals(1, b.get().toInt()); assertEquals(1, b.get().toInt()); assertEquals(0, b.short.toInt())
        assertEquals(48000, b.int); assertEquals(0, b.short.toInt()); assertEquals(0, b.get().toInt())
    }
    @Test fun opusPreskipIsLittleEndianSamples() { assertEquals(312, ByteBuffer.wrap(AudioHeaders.opusHead(312)).order(ByteOrder.LITTLE_ENDIAN).getShort(10).toInt()) }
    @Test fun csdTimingUsesNativeLongNanoseconds() { assertEquals(80_000_000, ByteBuffer.wrap(AudioHeaders.nanos(80_000_000)).order(ByteOrder.nativeOrder()).long) }
    @Test fun emptyWavAndClosedWavHaveCorrectSizes() {
        for (size in listOf(0L, 960L * 2 * 55)) {
            val bytes = AudioHeaders.wav(size); val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(44, bytes.size); assertEquals("RIFF", String(bytes, 0, 4)); assertEquals("WAVEfmt ", String(bytes, 8, 8))
            assertEquals(size + 36, b.getInt(4).toLong()); assertEquals(size, b.getInt(40).toLong())
            assertEquals(48000, b.getInt(24)); assertEquals(96000, b.getInt(28)); assertEquals(16, b.getShort(34).toInt())
            assertEquals(1, b.getShort(20).toInt()); assertEquals(1, b.getShort(22).toInt())
        }
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedWavRejected() { AudioHeaders.wav(0x1_0000_0000L) }
    @Test(expected = IllegalArgumentException::class) fun partialPcmSampleRejected() { AudioHeaders.wav(1) }
    @Test fun dbfsMatchesPythonRmsFloorAndRounding() {
        assertEquals(-120f, AudioHeaders.dbfs(shortArrayOf()), 0f)
        assertEquals(-90.3f, AudioHeaders.dbfs(ShortArray(960)), 0.01f)
        assertEquals(-6f, AudioHeaders.dbfs(shortArrayOf(16384, -16384)), 0.01f)
        assertEquals(0f, AudioHeaders.dbfs(shortArrayOf(32767, -32768)), 0.01f)
    }
}
