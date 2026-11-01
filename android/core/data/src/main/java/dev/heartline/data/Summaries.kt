package dev.heartline.data

import dev.heartline.data.model.*
import java.math.BigDecimal
import java.math.RoundingMode

internal fun roundOne(value: Double): Double = BigDecimal(value).setScale(1, RoundingMode.HALF_EVEN).toDouble()

/** server/store.py:hr_stats. Filtering bpm > 0 belongs to the repository query. */
fun hrStats(samples: List<HrSample>): HrStats {
    val bpms = samples.map { it.bpm }
    return HrStats(bpms.minOrNull(), bpms.maxOrNull(),
        bpms.takeIf { it.isNotEmpty() }?.let { roundOne(it.map(Int::toDouble).sum() / it.size) }, bpms.size)
}

/** server/protocol.py:activity_states, including MiBand2 repeat markers and the REM fallback. */
fun activityMinutes(rows: List<ActivityRow>, prevKind: Int? = null): List<ActivityMinute> {
    var last = prevKind
    return rows.map { row ->
        var kind = row.kind?.and(15)
        if (kind == 0 || kind == 10) kind = last else last = kind
        val hr = row.hr?.takeIf { it in 1..254 }
        var state = when (kind) {
            1, 2, 4, 12 -> "active"
            3 -> "not_worn"
            6 -> "charging"
            9 -> "sleep_light"
            11 -> "sleep_deep"
            else -> if (hr != null) "idle" else "unknown"
        }
        if (state == "sleep_light" && (row.rem?.and(127) ?: 0) > 55) state = "sleep_rem"
        ActivityMinute(row.ts, state, hr, row.steps ?: 0)
    }
}

/** server/store.py:activity_summary accepts already classified minutes. */
fun activitySummary(samples: List<ActivityMinute>): ActivitySummary {
    val hrs = samples.mapNotNull { it.hr }
    return ActivitySummary(
        samples.sumOf { it.steps.toLong() }, hrs.minOrNull(),
        hrs.takeIf { it.isNotEmpty() }?.let { roundOne(it.map(Int::toDouble).sum() / it.size) }, hrs.maxOrNull(),
        samples.count { it.steps > 0 || it.state == "active" },
        samples.count { it.state !in setOf("not_worn", "charging", "unknown") },
        samples.count { it.state in setOf("sleep_light", "sleep_deep", "sleep_rem") },
    )
}
