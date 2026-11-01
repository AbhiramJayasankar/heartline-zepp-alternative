package dev.heartline.ble

import dev.heartline.data.Csv
import dev.heartline.protocol.*
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** server/watch.py:_open_raw_file/_on_raw, including 25 Hz packet-relative timestamps. */
internal class RawRecording(folder: File, val sensors: Set<RawSensor>, private val level: (Float) -> Unit) : AutoCloseable {
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
        Csv.writeRow(csv, listOf("ts", "kind", "ppg", "ax", "ay", "az"))
        nmea = if (RawSensor.GPS in sensors) File(folder, file.nameWithoutExtension + ".nmea").bufferedWriter() else null
        mic = try { if (RawSensor.MIC in sensors) MicDecoder(File(folder, file.nameWithoutExtension + ".wav"), onLevel = level) else null }
            catch (e: Throwable) { csv.close(); nmea?.close(); throw e }
    }
    fun feed(packet: RawPacket) {
        val now = System.currentTimeMillis() / 1000.0
        fun rows(kind: String, values: List<List<Any?>>) = values.forEachIndexed { i, v ->
            val ts = Math.rint((now - (values.size - 1 - i) / RAW_RATE_HZ) * 1000) / 1000
            Csv.writeRow(csv, listOf(ts, kind) + v)
        }
        when (packet) {
            is RawPacket.Ppg -> rows("ppg", packet.values.map { listOf(it, null, null, null) })
            is RawPacket.Accel -> rows("accel", packet.samples.map { listOf(null, it.x, it.y, it.z) })
            is RawPacket.Gyro -> rows("gyro", packet.samples.map { listOf(null, it.x, it.y, it.z) })
            is RawPacket.Mag -> rows("mag", packet.samples.map { listOf(null, it.x, it.y, it.z) })
            is RawPacket.GpsText -> { nmea?.write(packet.text); gps.feed(packet.text) }
            is RawPacket.Mic -> { lastMic = System.nanoTime(); splitter.feed(packet).forEach { mic?.feed(it) } }
            else -> Unit
        }
    }
    fun flush() { csv.flush(); nmea?.flush() }
    override fun close() { try { mic?.close() } finally { try { nmea?.close() } finally { csv.close() } } }
}
