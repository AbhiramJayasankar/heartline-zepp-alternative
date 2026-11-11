package dev.heartline.ble

import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.Executors

/**
 * A plain-text log of everything said over Bluetooth, one file per day in `files/blelog/`:
 * `HH:mm:ss.SSS  <dir>  <characteristic>  <hex>`. dir is `rx` (watch notification), `rd` (a read), `tx` (phone to watch) or `--` (an app event).
 * The raw sensor stream is left out (it is already saved per recording, 25 packets a second); everything else is kept,
 * including messages Heartline does not understand yet, so unknown watch behaviour can be studied later.
 */
object BleLog {
    @Volatile private var dir: File? = null
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "ble-log").apply { isDaemon = true } }
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val skip = setOf(UUID.fromString("00000002-0000-3512-2118-0009af100700")) // RAW_DATA

    fun init(filesDir: File) { dir = File(filesDir, "blelog").apply { mkdirs() } }

    /**
     * Deletes the day files older than [keepDays] days and returns how many. They hold forwarded notification text and
     * the assistant's voice, and grew without end before (audit, 2026-11-12).
     */
    fun prune(keepDays: Long = KEEP_DAYS): Int {
        val oldest = LocalDate.now().minusDays(keepDays - 1)
        return dir?.listFiles { f -> f.name.endsWith(".log") }.orEmpty().count { f ->
            val day = runCatching { LocalDate.parse(f.name.removeSuffix(".log")) }.getOrNull()
            day != null && day.isBefore(oldest) && f.delete()
        }
    }
    const val KEEP_DAYS = 7L

    fun rx(uuid: UUID, value: ByteArray) = line("rx", uuid, value)
    fun tx(uuid: UUID, value: ByteArray) = line("tx", uuid, value)
    fun note(text: String) = append("--  $text")

    fun line(direction: String, uuid: UUID, value: ByteArray) {
        if (uuid in skip) return
        // The watch passcode (06 21 00 on, digits, 00) is never written out.
        val passcode = value.size > 4 && value[0] == 6.toByte() && value[1] == 0x21.toByte()
        val hex = (if (passcode) value.copyOf(4) else value).joinToString("") { "%02x".format(it) }
        append("$direction  ${short(uuid)}  $hex${if (passcode) " (passcode hidden)" else ""}")
    }
    private fun append(text: String) {
        val d = dir ?: return
        val stamp = LocalTime.now().format(time)
        writer.execute { runCatching { File(d, "${LocalDate.now()}.log").appendText("$stamp  $text\n") } }
    }
    /** Huami's 128-bit UUIDs differ only in the first group; standard ones keep their 16-bit form. */
    private fun short(uuid: UUID): String = uuid.toString().let { s ->
        if (s.endsWith("-0000-1000-8000-00805f9b34fb")) s.substring(4, 8) else s.substring(4, 8) + "h"
    }
}
