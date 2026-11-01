package dev.heartline.protocol

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.floor

data class Satellite(val id: String, val system: String, val elev: Double?, val az: Double?, val snr: Double?)
data class GpsState(
    val fix: Boolean = false, val quality: Int = 0, val lat: Double? = null, val lon: Double? = null,
    val altM: Double? = null, val speedMps: Double? = null, val course: Double? = null, val hdop: Double? = null,
    val satsUsed: Int = 0, val utc: String? = null, val satellites: List<Satellite> = emptyList(),
)

private val systems = mapOf("GP" to "GPS", "GL" to "GLONASS", "GA" to "Galileo", "GB" to "BeiDou",
    "BD" to "BeiDou", "GN" to "GNSS", "GQ" to "QZSS")

/** gps.Nmea; updates are announced by complete GGA sentences, not RMC or GSV. */
class Nmea {
    private var buffer = ""
    private val satellites = linkedMapOf<String, LinkedHashMap<String, Satellite>>()
    var state = GpsState()
        private set

    fun feed(text: String): Boolean {
        val lines = (buffer + text).replace("\r", "").split('\n')
        buffer = lines.last()
        var updated = false
        for (raw in lines.dropLast(1)) {
            val line = raw.trim().trimStart('$')
            if (line.isEmpty() || !checksumOk(line)) continue
            val fields = line.substringBefore('*').split(',')
            val kind = fields[0].drop(2)
            val talker = fields[0].take(2)
            try {
                when (kind) {
                    "GGA" -> { gga(fields); updated = true }
                    "RMC" -> rmc(fields)
                    "GSV" -> gsv(talker, fields)
                    "VTG" -> if (state.speedMps == null) state = state.copy(
                        speedMps = fields.getOrNull(7)?.toDoubleOrNull()?.let { pyRound(it / 3.6, 2) })
                }
            } catch (_: IndexOutOfBoundsException) {
                // gps.Nmea ignores malformed sentences and continues with the next line.
            } catch (_: IllegalArgumentException) { }
        }
        if (buffer.length > 4096) buffer = ""
        return updated
    }

    private fun checksumOk(line: String): Boolean {
        if ('*' !in line) return true
        val calc = line.substringBefore('*').fold(0) { v, ch -> v xor ch.code }
        return calc == line.substringAfter('*').take(2).toIntOrNull(16)
    }

    private fun deg(value: String, hemi: String): Double? = value.toDoubleOrNull()?.let { v ->
        val deg = floor(v / 100).toInt()
        val out = deg + (v - deg * 100) / 60
        pyRound(if (hemi == "S" || hemi == "W") -out else out, 7)
    }

    private fun gga(f: List<String>) {
        val quality = f[6].ifEmpty { "0" }.toInt()
        state = state.copy(quality = quality, fix = quality > 0, satsUsed = f[7].ifEmpty { "0" }.toInt(),
            hdop = f[8].toDoubleOrNull(), altM = f[9].toDoubleOrNull())
        if (quality > 0) state = state.copy(lat = deg(f[2], f[3]), lon = deg(f[4], f[5]))
        state = state.copy(satellites = satellites.values.flatMap { it.values })
    }

    private fun rmc(f: List<String>) {
        val valid = f[2] == "A"
        state = state.copy(speedMps = if (valid) f[7].toDoubleOrNull()?.let { pyRound(it * 0.514444, 2) } else null,
            course = if (valid) f[8].toDoubleOrNull() else null)
        val t = f[1]
        val d = f[9]
        if (t.isNotEmpty() && d.length == 6) {
            try {
                val utc = LocalDateTime.of(2000 + d.substring(4, 6).toInt(), d.substring(2, 4).toInt(),
                    d.substring(0, 2).toInt(), t.substring(0, 2).toInt(), t.substring(2, 4).toInt(), t.substring(4).toDouble().toInt())
                state = state.copy(utc = utc.format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss")) + "+00:00")
            } catch (_: DateTimeException) { }
        }
    }

    private fun gsv(talker: String, f: List<String>) {
        val system = systems[talker] ?: talker
        if (f[2] == "1") satellites[system] = linkedMapOf()
        val sats = satellites.getOrPut(system) { linkedMapOf() }
        for (i in 4 until f.size - 3 step 4) {
            val id = f[i]
            if (id.isEmpty()) continue
            sats[id] = Satellite(id, system, f[i + 1].toDoubleOrNull(), f[i + 2].toDoubleOrNull(), f[i + 3].toDoubleOrNull())
        }
    }
}
