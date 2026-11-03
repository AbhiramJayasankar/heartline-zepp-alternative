package dev.heartline.ble

import dev.heartline.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

class RawSampleClockTest {
    @Test fun probeFixtureAssignsLastSamplesAndMeasuresAllFourMasks() {
        val rows = javaClass.getResourceAsStream("/raw/probe-1103b.txt")!!.bufferedReader().readLines()
        val pattern = Regex("(\\d+):(\\d+):(\\d+\\.\\d+) +-- +probe 0x(\\w+) pkt (\\w+)")
        val runs = rows.mapNotNull { pattern.matchEntire(it) }.groupBy { it.groupValues[4] }
        assertEquals(setOf("02", "82", "83", "81"), runs.keys)
        for ((mask, packets) in runs) {
            val output = ArrayList<TimedRawPacket>()
            val clock = RawSampleClock(mask != "02", output::add)
            var anchors = 0
            for (row in packets) {
                val v = row.groupValues
                val arrival = 1_800_000_000 + v[1].toInt() * 3600 + v[2].toInt() * 60 + v[3].toDouble()
                val data = v[5].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val p = parseRaw(data)
                clock.feed(p, arrival)
                if (p is RawPacket.Timestamp) {
                    anchors++
                    assertEquals(p.watchMs / 1000.0, output.last().watch!!.last(), 1e-8)
                }
            }
            clock.flush()
            assertEquals(anchors, output.size)
            fun rate(ppg: Boolean): Double {
                val sensor = output.filter { (it.packet is RawPacket.Ppg) == ppg }
                val samples = sensor.drop(1).sumOf { it.watch!!.size }
                val hz = samples / (sensor.last().watch!!.last() - sensor.first().watch!!.last())
                assertTrue(sensor.flatMap { it.watch!!.toList() }.zipWithNext().all { (a, b) -> b > a })
                assertTrue(sensor.flatMap { it.epoch.toList() }.zipWithNext().all { (a, b) -> b > a })
                return hz
            }
            when (mask) {
                "02" -> assertEquals(25.1, rate(true), .2)
                "83" -> { assertEquals(50.0, rate(true), .5); assertEquals(26.2, rate(false), .1) }
                "81" -> assertEquals(26.2, rate(false), .1)
                "82" -> {
                    assertEquals(39.4849, rate(true), .001)
                    fun packetRate(p: TimedRawPacket) = (p.watch!!.size - 1) / (p.watch.last() - p.watch.first())
                    assertEquals(25.1, packetRate(output[20]), .5)
                    assertEquals(50.1, packetRate(output[65]), .5)
                }
            }
        }
    }

    private fun ppg(seq: Int, n: Int = 5) = RawPacket.Ppg(IntArray(n) { 27000 }, seq)

    @Test fun missingLateDuplicateStampsAndStopTailUseArrivalFallback() {
        val out = ArrayList<TimedRawPacket>()
        val clock = RawSampleClock(true, out::add)
        clock.feed(ppg(0), 1000.0); clock.feed(RawPacket.Timestamp(1, 100, 10000), 1000.001)
        clock.feed(ppg(1), 1000.1)
        clock.feed(ppg(2), 1000.2) // Packet 1 has no following stamp.
        assertNull(out.last().watch)
        clock.feed(RawPacket.Timestamp(1, 101, 10100), 1000.201) // Too late for packet 1.
        assertEquals(2, out.size)
        clock.feed(RawPacket.Timestamp(1, 102, 10200), 1000.202)
        assertEquals(10.2, out.last().watch!!.last(), 1e-9)
        clock.feed(RawPacket.Timestamp(1, 102, 10200), 1000.203)
        assertEquals(3, out.size)
        clock.feed(ppg(3), 1000.3); clock.flush()
        assertNull(out.last().watch)
        assertEquals(1000.3, out.last().epoch.last(), 1e-6)
        clock.feed(ppg(4), 1000.4); clock.expire(1002.0)
        assertNull(out.last().watch)
        assertEquals(5, out.size)
    }

    @Test fun countersAndUnsignedWatchClockWrapWithoutClosingGaps() {
        val out = ArrayList<TimedRawPacket>()
        val clock = RawSampleClock(true, out::add)
        clock.feed(ppg(255), 1000.0); clock.feed(RawPacket.Timestamp(1, 255, 0xfffffff0L), 1000.0)
        clock.feed(ppg(0), 1000.1); clock.feed(RawPacket.Timestamp(1, 0, 84), 1000.1)
        assertEquals(.1, out[1].watch!!.last() - out[0].watch!!.last(), 1e-8)
        clock.feed(ppg(2), 1010.0); clock.feed(RawPacket.Timestamp(1, 2, 9984), 1010.0)
        assertTrue(out.last().watch!!.first() - out[1].watch!!.last() > 9)
        clock.restart(false)
        clock.feed(ppg(0), 1012.0); clock.feed(RawPacket.Timestamp(1, 50, 11984), 1012.0)
        assertEquals(.04, out.last().watch!![1] - out.last().watch!![0], 1e-8)
    }

    @Test fun affineMappingTracksDriftWithoutArrivalBurstJitter() {
        val fit = WatchEpochFit()
        val mapped = (0..3000).map { i ->
            val watch = 200000 + i * .1
            val arrival = 1_800_000_000 + i * .10001 + .01 + if (i % 13 == 0 && i > 0) .3 else 0.0
            fit.add(watch, arrival)
            fit.epoch(watch)
        }
        assertTrue(mapped.zipWithNext().all { (a, b) -> abs((b - a) - .10001) < .001 })
        assertEquals(1_800_000_000 + 300.03 + .01, mapped.last(), .025)
    }

    @Test fun unknownGyroAndMagRatesAreMeasuredIndependentlyAndBackwardResetsFallBack() {
        val out = ArrayList<TimedRawPacket>()
        val clock = RawSampleClock(true, out::add)
        fun gyro(seq: Int) = RawPacket.Gyro(List(20) { Vec3i(1, 2, 3) }, seq)
        fun mag(seq: Int) = RawPacket.Mag(List(6) { Vec3i(4, 5, 6) }, seq)
        clock.feed(gyro(0), 1000.0); clock.feed(RawPacket.Timestamp(4, 10, 100000), 1000.0)
        clock.feed(mag(0), 1000.0); clock.feed(RawPacket.Timestamp(6, 20, 100000), 1000.0)
        clock.feed(gyro(1), 1000.2); clock.feed(RawPacket.Timestamp(4, 11, 100200), 1000.2)
        assertEquals(.01, out.last().watch!![1] - out.last().watch!![0], 1e-9)
        clock.feed(mag(1), 1000.5); clock.feed(RawPacket.Timestamp(6, 21, 100500), 1000.5)
        assertEquals(1 / 12.0, out.last().watch!![1] - out.last().watch!![0], 1e-9)
        clock.restart(true)
        clock.feed(ppg(0), 1001.0); clock.feed(RawPacket.Timestamp(1, 0, 100), 1001.0)
        clock.flush()
        assertNull(out.last().watch)
        assertEquals(1001.0, out.last().epoch.last(), 1e-8)
    }

    @Test fun csvRetainsWarmupAndFlushesUnstampedTail() {
        val folder = File(System.getProperty("java.io.tmpdir"), "raw-clock-${System.nanoTime()}")
        try {
            val r = RawRecording(folder, setOf(RawSensor.PPG), true) {}
            r.feed(RawPacket.Ppg(intArrayOf(3800, 27000), 0))
            r.feed(RawPacket.Timestamp(1, 23, 10000))
            r.feed(RawPacket.Ppg(intArrayOf(27100), 1))
            r.close()
            val rows = r.file.readLines()
            assertEquals("ts,kind,ppg,ax,ay,az,wts", rows[0])
            assertEquals(4, rows.size)
            assertTrue(rows[1].contains(",3800,"))
            assertEquals("10.0", rows[2].split(',').last())
            assertTrue(rows[3].endsWith(','))
        } finally { folder.listFiles()?.forEach { it.delete() }; folder.delete() }
    }

    @Test fun fastPpgRetriesOnlyOnceAndOnlyWhenPpgIsAbsent() {
        val mode = RawPpgMode(true, 1000)
        assertFalse(mode.retrySlow(5999, 0))
        assertTrue(mode.retrySlow(6000, 0)); assertFalse(mode.fast)
        assertFalse(mode.retrySlow(12000, 0))
        assertFalse(RawPpgMode(true, 0).retrySlow(6000, 1))
        assertFalse(RawPpgMode(false, 0).retrySlow(6000, 0))
        val restarted = RawPpgMode(true, 0)
        assertTrue(restarted.hasPpg(10))
        restarted.began(7000, 10)
        assertFalse(restarted.hasPpg(10))
        assertTrue(restarted.retrySlow(12000, 10))
    }
}
