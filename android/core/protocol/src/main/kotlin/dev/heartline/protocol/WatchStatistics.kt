package dev.heartline.protocol

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The watch's event log, fetched as "statistics" (0x2C). Frames are `fe ef u16-length` followed by a protobuf the Zepp
 * app calls Hmstatistics.EventsStatistics: 1 serial, 2 device source, 3 device id, 4 version, 5 repeated events. Each
 * event (EventHole) has 1 id, 2 a wall-clock time, 3 a session time, 4 reserved and 5 repeated key/value params. Both
 * times are the watch's local time counted as seconds since 1970, so they read 5:30 h late as epochs in IST. Zepp uploads
 * these without reading them.
 *
 * Matched against saved workouts (2026-11-02): 0x0801 is a workout opened or started and 0x0901 one ended, each with the
 * watch's workout type code (as in [WORKOUT_TYPES]) in param 80 or 32; their session times fall within seconds of the
 * workout's own start and end once read as local time. Other ids are not identified yet.
 */
data class WatchStatEvent(val id: Int, val wallTs: Long, val sessionTs: Long, val params: Map<Int, Long>)

fun parseStatisticsEvents(data: ByteArray): List<WatchStatEvent> {
    val out = ArrayList<WatchStatEvent>()
    var i = 0
    while (i + 4 <= data.size && data.u8(i) == 0xFE && data.u8(i + 1) == 0xEF) {
        val n = data.u8(i + 2) or (data.u8(i + 3) shl 8)
        val end = minOf(data.size, i + 4 + n)
        runCatching { Proto(data, i + 4, end).fields { f, p -> if (f == 5) out += event(p.bytes()) } }
        i += 4 + n
    }
    return out
}

private fun event(b: ByteArray): WatchStatEvent {
    var id = 0; var wall = 0L; var session = 0L; val params = LinkedHashMap<Int, Long>()
    Proto(b, 0, b.size).fields { f, p ->
        when (f) {
            1 -> id = p.varint().toInt(); 2 -> wall = p.varint(); 3 -> session = p.varint()
            5 -> { var k = 0; var v = 0L; val pb = p.bytes(); Proto(pb, 0, pb.size).fields { g, q -> if (g == 1) k = q.varint().toInt() else if (g == 2) v = q.varint() else q.skip() }; params[k] = v }
            else -> p.skip()
        }
    }
    return WatchStatEvent(id, wall, session, params)
}

/**
 * Workout starts and ends from the event log, as health rows "watch_workout": value = type code, extra event = start/end.
 * Times are converted from the watch's local time with [zone], the phone's zone (the watch follows the phone's clock).
 */
fun watchWorkoutMarks(events: List<WatchStatEvent>, zone: ZoneId = ZoneId.systemDefault()): List<HealthPoint> = events.mapNotNull { e ->
    val (kind, code) = when (e.id) {
        0x0801 -> "start" to e.params[80]
        0x0901 -> "end" to e.params[32]
        else -> return@mapNotNull null
    }
    if (e.sessionTs <= 0) return@mapNotNull null
    val ts = LocalDateTime.ofEpochSecond(e.sessionTs, 0, ZoneOffset.UTC).atZone(zone).toEpochSecond()
    HealthPoint("watch_workout", ts, code?.toDouble(), mapOf("event" to kind, "type" to (code?.let { WORKOUT_TYPES[it.toInt()] } ?: "Workout")))
}

/** A minimal protobuf reader for the fields above. */
private class Proto(val b: ByteArray, var i: Int, val end: Int) {
    private var wire = 0
    fun fields(block: (Int, Proto) -> Unit) {
        while (i < end) { val key = varint(); wire = (key and 7).toInt(); block((key shr 3).toInt(), this) }
    }
    fun varint(): Long {
        var v = 0L; var s = 0
        while (true) { val x = b[i++].toInt() and 255; v = v or ((x and 0x7F).toLong() shl s); s += 7; if (x < 0x80) return v }
    }
    fun bytes(): ByteArray { val n = varint().toInt(); return b.copyOfRange(i, i + n).also { i += n } }
    fun skip() { when (wire) { 0 -> varint(); 1 -> i += 8; 2 -> bytes(); 5 -> i += 4; else -> error("wire type $wire") } }
}
