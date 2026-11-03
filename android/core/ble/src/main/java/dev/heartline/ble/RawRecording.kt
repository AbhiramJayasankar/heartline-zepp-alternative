package dev.heartline.ble

import dev.heartline.data.Csv
import dev.heartline.protocol.*
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Preserve every raw value; analysis removes LED warm-up, never the recording. */
internal class RawRecording(folder: File, val sensors: Set<RawSensor>, fastPpg: Boolean, private val level: (Float) -> Unit) : AutoCloseable {
    val start = System.currentTimeMillis() / 1000.0
    val file: File
    private val csv: java.io.BufferedWriter
    private val nmea: java.io.BufferedWriter?
    private val splitter = OpusFrameSplitter()
    private val mic: MicDecoder?
    val gps = Nmea()
    var lastMic = System.nanoTime()
    var lastKick = lastMic
    val hasAudio: Boolean get() = mic != null
    var ppgPackets = 0L
        private set
    private val clock = RawSampleClock(fastPpg, ::writeTimed)
    init {
        folder.mkdirs()
        var stamp = LocalDateTime.now()
        var candidate = File(folder, stamp.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".csv")
        while (candidate.exists()) {
            stamp = stamp.plusSeconds(1)
            candidate = File(folder, stamp.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".csv")
        }
        file = candidate
        csv = file.bufferedWriter()
        Csv.writeRow(csv, listOf("ts", "kind", "ppg", "ax", "ay", "az", "wts"))
        nmea = if (RawSensor.GPS in sensors) File(folder, file.nameWithoutExtension + ".nmea").bufferedWriter() else null
        mic = try { if (RawSensor.MIC in sensors) MicDecoder(File(folder, file.nameWithoutExtension + ".wav"), onLevel = level) else null }
            catch (e: Throwable) { csv.close(); nmea?.close(); throw e }
    }
    fun feed(packet: RawPacket) {
        val now = System.currentTimeMillis() / 1000.0
        if (packet is RawPacket.Ppg && packet.values.isNotEmpty()) ppgPackets++
        clock.feed(packet, now)
        when (packet) {
            is RawPacket.GpsText -> { nmea?.write(packet.text); gps.feed(packet.text) }
            is RawPacket.Mic -> { lastMic = System.nanoTime(); splitter.feed(packet).forEach { mic?.feed(it) } }
            else -> Unit
        }
    }
    private fun writeTimed(timed: TimedRawPacket) {
        fun rows(kind: String, values: List<List<Any?>>) = values.forEachIndexed { i, v ->
            Csv.writeRow(csv, listOf(timed.epoch[i], kind) + v + listOf(timed.watch?.get(i)))
        }
        when (val p = timed.packet) {
            is RawPacket.Ppg -> rows("ppg", p.values.map { listOf(it, null, null, null) })
            is RawPacket.Accel -> rows("accel", p.samples.map { listOf(null, it.x, it.y, it.z) })
            is RawPacket.Gyro -> rows("gyro", p.samples.map { listOf(null, it.x, it.y, it.z) })
            is RawPacket.Mag -> rows("mag", p.samples.map { listOf(null, it.x, it.y, it.z) })
            else -> Unit
        }
    }
    fun restartClock(fastPpg: Boolean) = clock.restart(fastPpg)
    fun flush() { clock.expire(System.currentTimeMillis() / 1000.0); csv.flush(); nmea?.flush() }
    override fun close() { try { clock.flush(); mic?.close() } finally { try { nmea?.close() } finally { csv.close() } } }
}
