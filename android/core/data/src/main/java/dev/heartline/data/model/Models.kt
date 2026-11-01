package dev.heartline.data.model

data class HrSample(val ts: Double, val bpm: Int)
data class HrStats(val min: Int?, val max: Int?, val avg: Double?, val count: Int)
data class Event(val ts: Double, val level: String, val msg: String)

data class ActivityRow(
    val ts: Long, val kind: Int?, val intensity: Int?, val steps: Int?, val hr: Int?,
    val unknown1: Int?, val sleep: Int?, val deep: Int?, val rem: Int?,
)

data class ActivityMinute(val ts: Long, val state: String, val hr: Int?, val steps: Int)
data class ActivitySummary(
    val steps: Long, val hrMin: Int?, val hrAvg: Double?, val hrMax: Int?,
    val activeMinutes: Int, val wornMinutes: Int, val sleepMinutes: Int,
)

/** JSON fields are kept verbatim so unknown Python/watch fields survive a round trip. */
data class HealthPoint(val type: String, val ts: Long, val value: Double?, val extra: String? = "{}")
data class Workout(val start: Long, val data: String, val track: String? = null)
data class RawFetch(val ts: Double, val type: String, val start: Double?, val reply: String?, val data: ByteArray?) {
    override fun equals(other: Any?): Boolean = other is RawFetch && ts == other.ts && type == other.type &&
        start == other.start && reply == other.reply &&
        (if (data == null) other.data == null else other.data != null && data.contentEquals(other.data))
    override fun hashCode(): Int = listOf(ts, type, start, reply, data?.contentHashCode()).hashCode()
}

data class InsightSession(
    val file: String, val mtime: Double?, val start: Double?, val durationS: Double?, val quality: Double?,
    val usableS: Double?, val hr: Double?, val rmssd: Double?, val sdnn: Double?, val rhythm: String?,
    val resp: Double?, val result: String?,
)
data class RhythmReading(val status: String?, val start: Double?)
data class Recording(
    val file: String, val start: Double?, val durationS: Double?, val sensors: String?,
    val hasAudio: Boolean, val analysis: String? = null,
)
data class Check(
    val start: Double, val file: String?, val hr: Double?, val rmssd: Double?, val sdnn: Double?,
    val rhythm: String?, val json: String?,
)
data class ImportReport(val counts: Map<String, Int>, val updatedCounts: Map<String, Int>) {
    val added: Int get() = counts.values.sum()
    val updated: Int get() = updatedCounts.values.sum()
}
