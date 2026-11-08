package dev.heartline.analytics

/**
 * One overnight SpO2 reading: epoch seconds, percent, and how many seconds the watch measured it over, when known.
 * The seconds are the last byte of each six-byte record (fetch type 0x26); Zepp 6.3.1 parses that byte as
 * `Spo2OsaData.time` and stores it as the "OSA process" reading's duration (0 to about 64 s).
 */
data class Spo2Reading(val ts: Long, val value: Double, val seconds: Int? = null)

/** The readings kept, and those set aside: [shifted] stretches far below the night's level, and one-off [spikes]. */
data class Spo2Cleaned(val kept: List<Spo2Reading>, val shifted: List<Spo2Reading>, val spikes: List<Spo2Reading>) {
    val setAside: List<Spo2Reading> get() = (shifted + spikes).sortedBy { it.ts }
}

/** Below this, a stretch between two movements is set aside when it is also [SPO2_DROP] under the night's level. */
const val SPO2_LIMIT = 92.0
const val SPO2_DROP = 5.0
/** A reading this far from the median of its neighbours (within 5 minutes, same stretch) is a spike. */
const val SPO2_SPIKE = 4.0

/** Minutes (epoch seconds / 60) when the wrist moved or the watch changed the minute's activity type. */
fun movedMinutes(minutes: List<MinuteSample>): Set<Long> {
    val out = HashSet<Long>()
    var prev: MinuteSample? = null
    for (m in minutes.sortedBy { it.ts }) {
        if (m.intensity > 0 || (prev != null && m.ts - prev.ts <= 60 && m.kind != prev.kind)) out.add(m.ts / 60)
        prev = m
    }
    return out
}

/**
 * Sets aside overnight SpO2 readings that are pressure on the sensor rather than breathing.
 *
 * The GTR 2e measures SpO2 about once a minute through the night. Its low readings come in steps: when you settle into
 * a position the readings drop to a new level (often low 80s) and stay there, with a calm, steady heart rate, until you
 * move again and they jump back. A real fall in blood oxygen doesn't follow your sleeping position like that; lying on
 * the watch or pressing it into the mattress does. Pressure can only lower a reading, so the night's level is taken from
 * its upper readings: the 75th percentile of the readings measured over at least 30 s.
 *
 * The night is split into stretches at every movement (and at gaps over 10 minutes). A stretch whose median is under
 * both [SPO2_LIMIT] and the night's level minus [SPO2_DROP] is set aside as [Spo2Cleaned.shifted]. A night that is low
 * all through keeps its readings, since its level is low too. Within the kept stretches, a reading more than
 * [SPO2_SPIKE] from the median of its neighbours is a spike. Zepp does its own clean-up of the same readings in a
 * closed library (HealthCare.calculateSpo2ValidInfo), so this is Heartline's own rule, not a copy of Zepp's.
 */
fun cleanSpo2(readings: List<Spo2Reading>, moved: Set<Long>): Spo2Cleaned {
    val sorted = readings.sortedBy { it.ts }
    if (sorted.isEmpty()) return Spo2Cleaned(emptyList(), emptyList(), emptyList())
    fun level(rs: List<Spo2Reading>): Double {
        val good = rs.filter { (it.seconds ?: 60) >= 30 }.takeIf { it.size >= 5 } ?: rs
        val v = good.map { it.value }.sorted()
        return v[minOf(v.size - 1, (0.75 * v.size).toInt())]
    }
    val pooled = level(sorted)
    // Nights: runs of readings with no gap over 2 hours.
    val nights = ArrayList<MutableList<Spo2Reading>>()
    for (r in sorted) if (nights.isEmpty() || r.ts - nights.last().last().ts > 7200) nights.add(arrayListOf(r)) else nights.last().add(r)
    val kept = ArrayList<Spo2Reading>(); val shifted = ArrayList<Spo2Reading>(); val spikes = ArrayList<Spo2Reading>()
    for (night in nights) {
        val ref = if (night.size >= 10) level(night) else pooled
        val stretches = ArrayList<MutableList<Spo2Reading>>()
        for (r in night) {
            val last = stretches.lastOrNull()?.last()
            val split = last == null || r.ts - last.ts > 600 || (last.ts / 60 + 1..r.ts / 60).any { it in moved }
            if (split) stretches.add(arrayListOf(r)) else stretches.last().add(r)
        }
        for (s in stretches) {
            val med = median(s.map { it.value }.toDoubleArray())
            if (med < SPO2_LIMIT && med < ref - SPO2_DROP) { shifted.addAll(s); continue }
            for (r in s) {
                val near = s.filter { it !== r && kotlin.math.abs(it.ts - r.ts) <= 300 }.map { it.value }
                if (near.isNotEmpty() && kotlin.math.abs(r.value - median(near.toDoubleArray())) > SPO2_SPIKE) spikes.add(r) else kept.add(r)
            }
        }
    }
    return Spo2Cleaned(kept, shifted, spikes)
}
