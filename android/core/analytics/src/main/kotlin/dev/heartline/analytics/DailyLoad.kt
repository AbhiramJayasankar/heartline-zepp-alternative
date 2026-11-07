package dev.heartline.analytics

import kotlin.math.*

internal fun hrMaxOf(profile: Profile): Int? = profile.maxHr?.takeIf { it != 0 }
    ?: profile.age?.takeIf { it != 0 }?.let { rounded(208 - 0.7 * it).toInt() }

private val zoneBounds = doubleArrayOf(0.5, 0.6, 0.7, 0.8, 0.9, 10.0)
internal fun zoneOf(f: Double): Int = (0..4).firstOrNull { f >= zoneBounds[it] && f < zoneBounds[it + 1] } ?: -1
internal fun zoneMinutes(hr: List<Int>, hrMax: Int): HrZones {
    val z = IntArray(5)
    for (h in hr) { val i = zoneOf(h.toDouble() / hrMax); if (i >= 0) z[i]++ }
    return HrZones(z[0], z[1], z[2], z[3], z[4])
}
internal fun banisterTrimp(hr: List<Int>, rest: Double, max: Int, sex: String?): Double {
    val a = if (sex == "female") 0.86 else 0.64
    val b = if (sex == "female") 1.67 else 1.92
    var total = 0.0
    for (h in hr) {
        if (h < 0.5 * max || max <= rest) continue
        val r = ((h - rest) / (max - rest)).coerceIn(0.0, 1.0)
        total += r * a * exp(b * r)
    }
    return total
}
internal fun keytelKcal(hr: List<Int>, profile: Profile, max: Int): Int? {
    val age = profile.age?.takeIf { it != 0 } ?: return null
    val weight = profile.weightKg?.takeIf { it != 0.0 } ?: return null
    val sex = profile.sex?.takeIf { it.isNotEmpty() } ?: return null
    var kcal = 0.0
    for (h in hr) {
        if (h < 0.6 * max) continue
        val kj = if (sex == "male") -55.0969 + 0.6309 * h + 0.1988 * weight + 0.2017 * age
            else -20.4022 + 0.4472 * h - 0.1263 * weight + 0.074 * age
        kcal += max(0.0, kj / 4.184)
    }
    return rounded(kcal).toInt()
}
/**
 * A minute of real activity: walking at 100 or more steps a minute (the cadence that marks moderate intensity,
 * Tudor-Locke 2018), or, awake and worn, heart rate at 64% or more of max (moderate by ACSM), which catches cycling
 * and gym work. The watch's own "active" label isn't used: it repeats through most awake minutes.
 */
internal fun activeMinute(m: MinuteSample, hrMax: Int?): Boolean =
    m.steps >= 100 || (hrMax != null && hrMax != 0 && m.hr != null && m.state !in sleepStates && m.state !in noDataStates && m.hr >= 0.64 * hrMax)

internal fun vo2maxUth(max: Int?, rest: Double?, sex: String?): Double? {
    if (max == null || max == 0 || rest == null || rest == 0.0) return null
    val k = when (sex) { "male" -> 15.3; "female" -> 14.5; else -> 15.0 }
    return rounded(k * max / rest, 1)
}
internal fun fitnessAge(vo2: Double?, sex: String?): Int? {
    if (vo2 == null) return null
    val (base, slope) = when (sex) { "male" -> 54.0 to 0.36; "female" -> 44.0 to 0.28; else -> 49.0 to 0.32 }
    return rounded(20 + (base - vo2) / slope).toInt().coerceIn(20, 90)
}
internal fun bodyBattery(minutes: List<MinuteSample>, day: DayResult, hrMax: Int?): Int? {
    val score = day.sleepScore ?: return null
    val end = day.sleepEnd ?: return null
    var level = min(100.0, 20 + 0.8 * score)
    val extra = doubleArrayOf(0.05, 0.10, 0.20, 0.35, 0.50)
    for (m in minutes) {
        if (m.ts <= end || m.state in noDataStates) continue
        // The Python model deliberately clamps only on awake minutes, not during a nap.
        if (m.state in sleepStates) { level += 0.15; continue }
        var drain = 0.06
        if (hrMax != null && hrMax != 0 && m.hr != null && m.hr != 0) {
            val i = zoneOf(m.hr.toDouble() / hrMax)
            if (i >= 0) drain += extra[i]
        } else if (m.steps != 0) drain += min(0.3, m.steps * 0.002)
        level = (level - drain).coerceIn(0.0, 100.0)
    }
    return rounded(level).toInt()
}
internal fun recommendation(readiness: Int?): String? = when {
    readiness == null -> null
    readiness >= 80 -> "Well recovered. A good day for a hard workout."
    readiness >= 65 -> "Recovered enough for a normal training day."
    readiness >= 50 -> "Partly recovered. Keep effort moderate and get to bed on time."
    else -> "Your body is under strain. Favour rest, easy movement and an early night."
}
