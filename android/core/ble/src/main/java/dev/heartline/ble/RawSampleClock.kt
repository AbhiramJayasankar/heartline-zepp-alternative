package dev.heartline.ble

import dev.heartline.protocol.*
import kotlin.math.*

internal data class TimedRawPacket(val packet: RawPacket, val epoch: DoubleArray, val watch: DoubleArray?)

/** One pending packet per sensor. Timestamp counters have a fixed offset from data counters,
 * not the same value. Keep that offset so a delayed/duplicate stamp cannot timestamp newer data. */
internal class RawSampleClock(
    private var fastPpg: Boolean,
    private val emit: (TimedRawPacket) -> Unit,
) {
    private data class Pending(val packet: RawPacket, val arrival: Double, val count: Int, val seq: Int?)
    private class Sensor(var period: Double) {
        var pending: Pending? = null
        var counterOffset: Int? = null
        var stamp: Double? = null
        var seq: Int? = null
        var stampSeq: Int? = null
        var lastEpoch = Double.NEGATIVE_INFINITY
        var lastWatch = Double.NEGATIVE_INFINITY
        val periods = ArrayDeque<Pair<Double, Int>>()
        val changed = ArrayDeque<Pair<Double, Int>>()
    }
    private val sensors = mutableMapOf<Int, Sensor>()
    private var watchHigh: Double? = null
    private val mapping = WatchEpochFit()

    fun feed(packet: RawPacket, arrival: Double) {
        expire(arrival)
        if (packet is RawPacket.Timestamp) { timestamp(packet, arrival); return }
        val (type, count, seq) = when (packet) {
            is RawPacket.Accel -> Triple(0, packet.samples.size, packet.seq)
            is RawPacket.Ppg -> Triple(1, packet.values.size, packet.seq)
            is RawPacket.Gyro -> Triple(4, packet.samples.size, packet.seq)
            is RawPacket.Mag -> Triple(6, packet.samples.size, packet.seq)
            else -> return
        }
        if (count == 0) return
        val s = sensors.getOrPut(type) { Sensor(1 / (rawNominalRateHz(type, fastPpg) ?: 25.0)) }
        s.pending?.let { write(s, it, null) }
        s.pending = Pending(packet, arrival, count, seq)
    }

    /** Normal stamps follow immediately; do not retain an unstamped tail indefinitely. */
    fun expire(now: Double) {
        for (s in sensors.values) s.pending?.takeIf { now - it.arrival > 1.5 }?.let {
            write(s, it, null); s.pending = null
        }
    }

    fun flush() {
        for (s in sensors.values) s.pending?.let { write(s, it, null); s.pending = null }
    }

    /** Called for an actual raw restart, including the slow-PPG retry. Keep the epoch mapping. */
    fun restart(fast: Boolean) {
        flush(); fastPpg = fast
        sensors.clear()
    }

    private fun timestamp(packet: RawPacket.Timestamp, arrival: Double) {
        val s = sensors[packet.sensor] ?: return
        val p = s.pending ?: return
        val offset = p.seq?.let { (packet.seq - it) and 255 }
        if (s.counterOffset != null && offset != s.counterOffset) return
        val raw = packet.watchMs / 1000.0
        val wrap = 4294967.296
        val high = watchHigh
        val stamp = if (high == null) raw else raw + round((high - raw) / wrap) * wrap
        // Reject stale stamps and clock resets. A reconnect creates a new recording/clock.
        // A watch reboot on the same connection falls back until a new recording.
        if (high != null && stamp < high - 10) return
        if (s.stamp != null && stamp <= s.stamp!!) return
        watchHigh = max(high ?: stamp, stamp)
        val previous = s.stamp
        val consecutive = p.seq != null && s.seq != null && (p.seq - s.seq!!) and 255 == 1 &&
            s.stampSeq != null && (packet.seq - s.stampSeq!!) and 255 == 1
        if (previous != null && consecutive) {
            val dt = stamp - previous
            val period = dt / p.count
            if (period in .002..1.0) updatePeriod(s, dt, p.count)
        }
        mapping.add(stamp, arrival)
        // Stamp anchors are authoritative. Prevent a changed rate/rounded stamp from
        // making the first sample overlap the preceding packet.
        val available = (stamp - s.lastWatch) / p.count
        val step = min(s.period, available).coerceAtLeast(.000001)
        write(s, p, stamp, step)
        s.counterOffset = offset
        s.stamp = stamp; s.seq = p.seq; s.stampSeq = packet.seq; s.pending = null
    }

    private fun updatePeriod(s: Sensor, dt: Double, count: Int) {
        val candidate = dt / count
        if (s.periods.isNotEmpty() && abs(candidate / s.period - 1) > .3) {
            s.changed.addLast(dt to count)
            while (s.changed.size > 3) s.changed.removeFirst()
            // Three consistent packets distinguish an actual 25->50 Hz switch from a stall.
            if (s.changed.size < 3 || s.changed.any { abs((it.first / it.second) / candidate - 1) > .2 }) return
            s.periods.clear(); s.periods.addAll(s.changed); s.changed.clear()
        } else {
            s.changed.clear(); s.periods.addLast(dt to count)
        }
        while (s.periods.size > 15) s.periods.removeFirst()
        val sorted = s.periods.map { it.first / it.second }.sorted()
        val median = sorted[sorted.size / 2]
        val good = s.periods.filter { abs((it.first / it.second) / median - 1) < .2 }
        s.period = good.sumOf { it.first } / good.sumOf { it.second }
    }

    private fun write(s: Sensor, p: Pending, stamp: Double?, period: Double = s.period) {
        val watch = stamp?.let { end -> DoubleArray(p.count) { end - (p.count - 1 - it) * period } }
        // Missing stamps retain arrival-based packet timing, at the measured/nominal sensor rate.
        val epoch = DoubleArray(p.count) { i ->
            val time = watch?.let { mapping.epoch(it[i]) } ?: (p.arrival - (p.count - 1 - i) * period)
            max(time, s.lastEpoch + .000001).also { s.lastEpoch = it }
        }
        if (watch != null) s.lastWatch = watch.last()
        emit(TimedRawPacket(p.packet, epoch, watch))
    }
}

/** Robust running affine fit against notification arrival time. Delayed BLE bursts are
 * excluded from the fit; phase and drift changes are slewed, never applied as clock jumps. */
internal class WatchEpochFit {
    private val points = ArrayDeque<Pair<Double, Double>>()
    private var origin = Double.NaN
    private var epochOrigin = 0.0
    private var slope = 1.0
    private var last = 0.0

    fun add(watch: Double, arrival: Double) {
        if (!origin.isFinite()) { origin = watch; epochOrigin = arrival; last = watch }
        points.addLast(watch to arrival)
        while (points.size > 256 || (points.size > 2 && watch - points.first().first > 120)) points.removeFirst()
        val residuals = points.map { it.second - epoch(it.first) }.sorted()
        val floor = residuals[(residuals.size - 1) / 5]
        val good = points.filter { it.second - epoch(it.first) <= floor + .025 }
        val x = good.map { it.first - origin }.average()
        val y = good.map { it.second - epochOrigin }.average()
        val variance = good.sumOf { (it.first - origin - x).pow(2) }
        val targetSlope = if (variance > 100 && good.last().first - good.first().first > 10)
            (good.sumOf { (it.first - origin - x) * (it.second - epochOrigin - y) } / variance).coerceIn(.999, 1.001)
            else slope
        val predicted = epoch(watch)
        val target = epochOrigin + y + targetSlope * (watch - origin - x)
        val elapsed = max(0.0, watch - last)
        val correction = (target - predicted).coerceIn(-.001 * elapsed, .001 * elapsed)
        slope += (targetSlope - slope) * min(1.0, elapsed / 30)
        epochOrigin = predicted + correction; origin = watch; last = max(last, watch)
    }

    fun epoch(watch: Double): Double = epochOrigin + (watch - origin) * slope
}
