package dev.heartline.app

import dev.heartline.ble.RawUpdate
import dev.heartline.protocol.RawPacket
import dev.heartline.ui.components.estimateBpm
import kotlin.math.*

class RingBuffer<T>(private val capacity: Int) {
    private val items = ArrayDeque<T>()
    init { require(capacity > 0) }
    fun add(value: T) { if (items.size == capacity) items.removeFirst(); items.addLast(value) }
    fun snapshot(): List<T> = items.toList()
}

/** web/sensors.js:headingDeg/calibration; extrema cover the entire session, buffers cover eight seconds. */
class CompassCalibration {
    private val min = DoubleArray(3) { Double.POSITIVE_INFINITY }
    private val max = DoubleArray(3) { Double.NEGATIVE_INFINITY }
    fun add(v: FloatArray) { for (i in 0..2) { min[i] = minOf(min[i], v[i].toDouble()); max[i] = maxOf(max[i], v[i].toDouble()) } }
    val fraction: Double get() = ((minOf(max[0] - min[0], max[1] - min[1])) / 2000).coerceIn(0.0, 1.0)
    fun heading(mag: FloatArray?, accel: FloatArray?): Double? {
        if (mag == null) return null
        val m = DoubleArray(3) { mag[it] - (min[it] + max[it]) / 2 }
        var x = m[0]; var y = m[1]
        if (accel != null) {
            val roll = atan2(accel[1].toDouble(), accel[2].toDouble())
            val pitch = atan2(-accel[0].toDouble(), hypot(accel[1].toDouble(), accel[2].toDouble()))
            x = m[0] * cos(pitch) + m[2] * sin(pitch)
            y = m[0] * sin(roll) * sin(pitch) + m[1] * cos(roll) - m[2] * sin(roll) * cos(pitch)
        }
        return (atan2(-y, x) * 180 / PI + 360) % 360
    }
}
class SensorBuffers {
    private val ppg = RingBuffer<Float>(200)
    private val accel = RingBuffer<FloatArray>(200)
    private val gyro = RingBuffer<FloatArray>(200)
    private val mag = RingBuffer<FloatArray>(200)
    private val mic = RingBuffer<Float>(400)
    private val compass = CompassCalibration()
    fun feed(update: RawUpdate) {
        update.levels.forEach { mic.add(it) }
        when (val p = update.packet) {
            is RawPacket.Ppg -> p.values.forEach { ppg.add(it.toFloat()) }
            is RawPacket.Accel -> p.samples.forEach { accel.add(floatArrayOf(it.x, it.y, it.z)) }
            is RawPacket.Gyro -> p.samples.forEach { gyro.add(floatArrayOf(it.x.toFloat(), it.y.toFloat(), it.z.toFloat())) }
            is RawPacket.Mag -> p.samples.forEach { val v = floatArrayOf(it.x.toFloat(), it.y.toFloat(), it.z.toFloat()); mag.add(v); compass.add(v) }
            else -> Unit
        }
    }
    fun snapshot(previous: SensorsUi): SensorsUi {
        val p = ppg.snapshot().toFloatArray(); val a = accel.snapshot(); val m = mag.snapshot()
        return previous.copy(ppg = p, accel = a.toTypedArray(), gyro = gyro.snapshot().toTypedArray(), mag = m.toTypedArray(),
            micDb = mic.snapshot().toFloatArray(), headingDeg = compass.heading(m.lastOrNull(), a.lastOrNull()), compassCalibration = compass.fraction, ppgBpm = estimateBpm(p))
    }
}
