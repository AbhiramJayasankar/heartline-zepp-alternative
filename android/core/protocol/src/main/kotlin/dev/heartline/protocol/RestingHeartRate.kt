package dev.heartline.protocol

/** Gadgetbridge FetchHeartRateRestingOperation.handleActivityData: LE uint32 epoch,
 * signed UTC offset in quarter-hours, uint8 bpm; exactly six bytes per sample. */
fun parseRestingHeartRate(data: ByteArray): List<HealthPoint> {
    require(data.size % 6 == 0) { "Resting heart rate payload must contain six-byte records" }
    return (data.indices step 6).mapNotNull { i ->
        val bpm = data[i + 5].toInt() and 255
        if (bpm !in 1..254) null else HealthPoint("resting_hr", data.u32(i), bpm.toDouble(),
            mapOf("utc_offset_seconds" to (data[i + 4].toInt() * 900).toString()))
    }
}
