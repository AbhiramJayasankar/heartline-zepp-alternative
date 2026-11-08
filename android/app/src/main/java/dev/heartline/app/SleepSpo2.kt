package dev.heartline.app

import dev.heartline.analytics.MinuteSample
import dev.heartline.analytics.Spo2Reading
import dev.heartline.analytics.cleanSpo2
import dev.heartline.analytics.movedMinutes

/** Seconds the watch measured a sleep SpO2 reading over: the stored extra JSON's "extra" (six-byte records) or "duration". */
internal fun spo2Seconds(extra: String?): Int? =
    extra?.let { Regex("\"(?:extra|duration)\"\\s*:\\s*\"?(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }

/**
 * Overnight SpO2 cleaned with [cleanSpo2]: [values] without the sleep readings that are pressure on the sensor, and those
 * set aside. Spot checks (type "spo2") are left alone.
 */
internal fun cleanSleepSpo2(values: List<DayHealthValue>, minutes: List<MinuteSample>): Pair<List<DayHealthValue>, List<DayHealthValue>> {
    val sleep = values.filter { it.type == "spo2_sleep" }
    if (sleep.isEmpty()) return values to emptyList()
    val aside = cleanSpo2(sleep.map { Spo2Reading(it.ts, it.value, spo2Seconds(it.extra)) }, movedMinutes(minutes)).setAside.map { it.ts }.toHashSet()
    return values.filter { !(it.type == "spo2_sleep" && it.ts in aside) } to sleep.filter { it.ts in aside }
}
