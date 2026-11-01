package dev.heartline.protocol

import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

class RawAudioTest {
    @Test fun capturedAccel() {
        val packet = parseRaw(hex("0000aefe780041f0")) as RawPacket.Accel
        assertEquals(listOf(Vec3f(-0.082f, 0.029f, -0.983f)), packet.samples)
    }
    @Test fun capturedPpg22And24Bytes() {
        val p22 = parseRaw(hex("01019f8c7f8c728c648c7c8c858c8f8c868c8b8c828c")) as RawPacket.Ppg
        assertEquals(10, p22.values.size)
        assertEquals(0x8C9F, p22.values.first())
        assertEquals(11, (parseRaw(hex("0102deaa") + ByteArray(20)) as RawPacket.Ppg).values.size)
    }
    @Test fun accelMatchesPythonRoundingAtZero() {
        val actual = (parseRaw(hex("0000ffff01000000")) as RawPacket.Accel).samples.single()
        assertEquals(Vec3f(-0.0f, 0.0f, 0.0f), actual)
        assertEquals((-0.0f).toRawBits(), actual.x.toRawBits())
    }
    @Test fun unknownAndShortPackets() {
        assertEquals(RawPacket.Unknown(7, 11), parseRaw(hex("070000f781c22500000000")))
        assertEquals(RawPacket.Unknown(-1, 0), parseRaw(byteArrayOf()))
        assertEquals(RawPacket.Unknown(1, 1), parseRaw(bytes(1)))
        assertEquals(emptyList(), (parseRaw(hex("0000ffff")) as RawPacket.Accel).samples)
        assertContentEquals(intArrayOf(), (parseRaw(hex("0100ff")) as RawPacket.Ppg).values)
    }
    @Test fun rawMaskAndStartCommands() {
        assertEquals(3, rawMask(setOf(RawSensor.ACCEL, RawSensor.PPG)))
        assertEquals(0x58, rawMask(setOf(RawSensor.GPS, RawSensor.GYRO, RawSensor.MAG)))
        assertEquals(3, rawMask(emptySet()))
        assertEquals(0x7B, rawMask(RawSensor.entries.toSet()))
        assertEquals(listOf("015819", "015800000019", "02"), rawStartCommands(0x58).map { it.hex() })
        assertEquals(listOf("010319", "010300000019", "02"), rawStartCommands().map { it.hex() })
        assertContentEquals(bytes(3), RAW_STOP)
        assertFailsWith<IllegalArgumentException> { rawStartCommands(256) }
    }
    @Test fun capturedGyroMagGpsMic() {
        val mag = parseRaw(hex("0600d904980ab8fbd704990abefb")) as RawPacket.Mag
        assertEquals(Vec3i(1241, 2712, -1096), mag.samples.first())
        assertEquals(2, mag.samples.size)
        assertEquals(listOf(Vec3i(-5, 2, 4), Vec3i(7, 0, 0)), (parseRaw(hex("0401fbff02000400070000000000")) as RawPacket.Gyro).samples)
        assertEquals(RawPacket.GpsText("\$GPGGA,1"), parseRaw(bytes(3, 0) + "\$GPGGA,1".toByteArray()))
        val mic = parseRaw(hex("05fe0a0800")) as RawPacket.Mic
        assertEquals(254, mic.counter)
        assertContentEquals(hex("0a0800"), mic.payload)
    }
    @Test fun rawMatches512PythonVectors() {
        oracle["raw"].arr().forEach { item ->
            val row = item.obj()
            val packet = row["packet"].str()
            val values = row["values"].arr()
            when (val actual = parseRaw(hex(packet))) {
                is RawPacket.Accel -> {
                    assertEquals("accel", row["kind"])
                    assertEquals(values.map { v -> v.arr().map { it.num().toFloat() }.let { Vec3f(it[0], it[1], it[2]) } }, actual.samples, packet)
                }
                is RawPacket.Ppg -> { assertEquals("ppg", row["kind"]); assertContentEquals(values.map { it.num().toInt() }.toIntArray(), actual.values, packet) }
                is RawPacket.Gyro -> { assertEquals("gyro", row["kind"]); assertEquals(values.map(::vector), actual.samples, packet) }
                is RawPacket.Mag -> { assertEquals("mag", row["kind"]); assertEquals(values.map(::vector), actual.samples, packet) }
                is RawPacket.GpsText -> { assertEquals("gps", row["kind"]); assertEquals(values.single(), actual.text, packet) }
                is RawPacket.Mic -> { assertEquals("mic", row["kind"]); assertEquals(packet, (bytes(5, actual.counter) + actual.payload).hex()) }
                is RawPacket.Unknown -> assertEquals("other", row["kind"])
                is RawPacket.Timestamp -> fail("Python does not decode timestamps")
            }
        }
    }
    private fun vector(v: Any?) = v.arr().map { it.num().toInt() }.let { Vec3i(it[0], it[1], it[2]) }

    @Test fun framesSplitAcrossPackets() {
        val stream = bytes(10, 8) + ByteArray(9) + bytes(9, 8) + ByteArray(8) + bytes(12, 8)
        val split = splitFrames(stream)
        assertEquals(listOf(10, 9), split.frames.map { it.size })
        assertContentEquals(bytes(12, 8), split.tail)
        assertEquals(0, split.skippedBytes)
        val more = splitFrames(split.tail + ByteArray(11))
        assertEquals(listOf(12), more.frames.map { it.size })
        assertContentEquals(byteArrayOf(), more.tail)
    }
    @Test fun resyncAfterLostBytes() {
        val good = (0..3).flatMap { (bytes(10, 8) + ByteArray(9)).toList() }.toByteArray()
        val result = splitFrames(bytes(0x55, 0x99, 1) + good)
        assertEquals(4, result.frames.size)
        assertEquals(3, result.skippedBytes)
        assertContentEquals(byteArrayOf(), result.tail)
    }
    @Test fun splitMatches150PythonVectors() {
        oracle["splits"].arr().forEach { item ->
            val row = item.obj()
            val result = splitFrames(hex(row["data"].str()))
            assertEquals(row["frames"].arr(), result.frames.map { it.hex() }, row["data"].str())
            assertEquals(row["tail"], result.tail.hex())
            assertEquals(row["skipped"].num().toInt(), result.skippedBytes)
        }
    }
    @Test fun splitterRejectsFalseBoundaryAndRetainsUndecidableByte() {
        val good = bytes(10, 8) + ByteArray(9)
        val bad = bytes(7, 8, 0x99, 10, 8) + ByteArray(9) + bytes(1, 2, 3)
        val result = splitFrames(bad + good + good)
        assertEquals(2, result.frames.size)
        assertEquals(bad.size, result.skippedBytes)
        assertContentEquals(bytes(255), splitFrames(bytes(255)).tail)
        assertContentEquals(bytes(10), splitFrames(bytes(10)).tail)
        assertEquals(0, splitFrames(byteArrayOf()).frames.size)
    }
    @Test fun micCounterGapDropsTailAndWraps() {
        val mic = OpusFrameSplitter()
        assertEquals(0, mic.feed(RawPacket.Mic(254, bytes(10, 8, 0))).size)
        assertEquals(1, mic.feed(RawPacket.Mic(255, ByteArray(8))).size)
        assertEquals(1, mic.feed(RawPacket.Mic(0, bytes(10, 8) + ByteArray(9))).size)
        assertEquals(0, mic.feed(RawPacket.Mic(1, bytes(10, 8, 0))).size)
        assertEquals(1, mic.feed(RawPacket.Mic(3, bytes(0x55, 0x99, 1, 10, 8) + ByteArray(9))).size)
        assertEquals(MicStats(packets = 5, gaps = 1, frames = 3, skippedBytes = 3), mic.stats)
    }
    @Test fun duplicateMicCounterIsGap() {
        val mic = OpusFrameSplitter()
        mic.feed(RawPacket.Mic(0, bytes(10, 8, 0)))
        assertEquals(1, mic.feed(RawPacket.Mic(0, bytes(10, 8) + ByteArray(9))).size)
        assertEquals(1, mic.stats.gaps)
    }
    @Test fun captured2400HzToneYieldsExactly55Frames() {
        val frames = resource("mic_2400hz.json").obj()["frames"].arr().map { hex(it.str()) }
        assertEquals(55, frames.size)
        val payload = frames.flatMap { (bytes(it.size) + it).toList() }.toByteArray()
        assertIs<RawPacket.Mic>(parseRaw(bytes(5, 0) + payload.take(10)))
        val mic = OpusFrameSplitter()
        val actual = mic.feed(RawPacket.Mic(0, payload.copyOfRange(0, 37))) + mic.feed(RawPacket.Mic(1, payload.copyOfRange(37, payload.size)))
        assertEquals(frames.map { it.hex() }, actual.map { it.hex() })
        assertEquals(MicStats(packets = 2, frames = 55), mic.stats)
    }
    @Test fun toneAtEveryPossiblePacketBoundary() {
        val frames = resource("mic_2400hz.json").obj()["frames"].arr().map { hex(it.str()) }
        val payload = frames.flatMap { (bytes(it.size) + it).toList() }.toByteArray()
        for (cut in payload.indices) {
            val mic = OpusFrameSplitter()
            val actual = mic.feed(RawPacket.Mic(255, payload.copyOfRange(0, cut))) + mic.feed(RawPacket.Mic(0, payload.copyOfRange(cut, payload.size)))
            assertEquals(frames.map { it.hex() }, actual.map { it.hex() }, "cut=$cut")
        }
    }
    @Test fun realCaptureMatchesPythonPacketAndSampleCounts() {
        val file = captureFile()
        assumeTrue("Optional real capture is not available", file.exists())
        val packets = mutableMapOf<String, Int>()
        val samples = mutableMapOf<String, Int>()
        Json(file.readText()).parse().arr().forEach { item ->
            val p = parseRaw(hex(item.arr()[1].str()))
            val (kind, count) = when (p) {
                is RawPacket.Accel -> "accel" to p.samples.size
                is RawPacket.Ppg -> "ppg" to p.values.size
                is RawPacket.Gyro -> "gyro" to p.samples.size
                is RawPacket.Mag -> "mag" to p.samples.size
                is RawPacket.GpsText -> "gps" to 1
                is RawPacket.Mic -> "mic" to 1
                else -> "other" to 0
            }
            packets[kind] = (packets[kind] ?: 0) + 1
            samples[kind] = (samples[kind] ?: 0) + count
        }
        // Baseline: Python protocol.parse_raw over [timestamp, hex] pairs, 2026-11-01.
        // Reproduce with tools/reference_vectors.py (prints packet/sample Counters).
        assertEquals(mapOf("ppg" to 65, "other" to 223, "mag" to 128, "mic" to 142, "accel" to 15, "gyro" to 15, "gps" to 33), packets)
        assertEquals(mapOf("ppg" to 387, "other" to 0, "mag" to 384, "mic" to 142, "accel" to 385, "gyro" to 386, "gps" to 33), samples)
    }
}
