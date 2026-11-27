package dev.heartline.app

import dev.heartline.analytics.LiveCheck
import dev.heartline.analytics.LiveCheckSamples
import dev.heartline.analytics.liveCheck
import dev.heartline.ble.TimedSamples
import dev.heartline.protocol.RawPacket
import java.io.File

/**
 * Gathers the raw samples of the heart check that is recording, one recording at a time, and works out what it has
 * found so far ([LiveCheck]). Samples are kept only while [active] says a check is running: a workout capture streams
 * for hours and its samples are not wanted here.
 */
class LiveCheckFeed(private val active: () -> Boolean) {
    private var file: File? = null
    private var samples: LiveCheckSamples? = null

    @Synchronized fun feed(t: TimedSamples) {
        if (!active()) { file = null; samples = null; return }
        if (t.file != file) { file = t.file; samples = LiveCheckSamples() }
        val s = samples ?: return
        when (val p = t.packet) {
            is RawPacket.Ppg -> s.addPpg(t.epoch, t.watch, DoubleArray(p.values.size) { p.values[it].toDouble() })
            is RawPacket.Accel -> s.addAccel(t.epoch, t.watch, p.samples.map { floatArrayOf(it.x, it.y, it.z) })
            is RawPacket.Gyro -> s.addGyro(t.epoch, t.watch, p.samples.map { floatArrayOf(it.x.toFloat(), it.y.toFloat(), it.z.toFloat()) })
            else -> Unit
        }
    }

    /** Null when no check is recording or it has too little signal yet. Call off the main thread. */
    fun now(): LiveCheck? {
        val s = synchronized(this) { if (!active()) { file = null; samples = null }; samples } ?: return null
        return runCatching { liveCheck(s.recording()) }.getOrNull()
    }
}
