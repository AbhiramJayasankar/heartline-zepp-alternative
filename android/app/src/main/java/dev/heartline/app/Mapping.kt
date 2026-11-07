package dev.heartline.app

import dev.heartline.analytics.*
import dev.heartline.data.model.ActivityRow
import dev.heartline.protocol.ActivityMinute
import dev.heartline.protocol.activityStates
import dev.heartline.protocol.validHr
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

fun ActivityRow.protocolMinute() = ActivityMinute(ts, kind ?: 0, intensity ?: 0, steps ?: 0, hr, unknown1 ?: 0, sleep ?: 0, deep ?: 0, rem ?: 0)
fun mappedMinutes(rows: List<ActivityRow>, prevKind: Int? = null): List<MinuteUi> {
    val minutes = rows.map { it.protocolMinute() }
    return minutes.zip(activityStates(minutes, prevKind)).map { (row, state) ->
        MinuteUi(row.ts, MinuteState.valueOf(state.name), row.steps, row.hr.takeIf { validHr(it) })
    }
}
fun analyticsMinutes(rows: List<ActivityRow>, prevKind: Int? = null): List<MinuteSample> =
    rows.zip(mappedMinutes(rows, prevKind)).map { (r, m) ->
        MinuteSample(r.ts, r.kind ?: 0, r.intensity ?: 0, m.steps, m.hr, r.sleep ?: 0, r.deep ?: 0, r.rem ?: 0, m.state.name.lowercase())
    }
fun rhythmStatus(value: String?): RhythmStatus = when (value) {
    "regular" -> RhythmStatus.REGULAR
    "irregular" -> RhythmStatus.IRREGULAR
    "possible_af" -> RhythmStatus.POSSIBLE_AF
    else -> RhythmStatus.INSUFFICIENT
}
fun DayResult.sleepUi(): SleepNightUi? = sleepStart?.let { start -> sleepEnd?.let { end ->
    SleepNightUi(start, end, deepMin ?: 0, lightMin ?: 0, remMin ?: 0, awakeMin ?: 0, sleepOngoing)
} }
fun DailyResult.todayUi(date: LocalDate): TodayUi {
    val day = days.lastOrNull { it.date == date.toString() }
    val last = days.lastOrNull { it.sleepStart != null }
    val readiness = today.lastReadiness
    return TodayUi(today.readiness ?: readiness?.value,
        if (today.readiness == null && readiness != null) "Readiness, ${LocalDate.parse(readiness.date).format(DateTimeFormatter.ofPattern("d MMM"))}" else "Readiness",
        today.recommendation, today.strain, today.sleepScore, today.bodyBattery, day?.restingHr?.roundToInt(),
        // The analytics call it resting heart rate; it is measured asleep, so the app says so.
        today.readinessFactors.map { Factor(if (it.name == "Resting heart rate") "Sleeping heart rate" else it.name, when (it.impact) { "positive" -> Impact.POSITIVE; "negative" -> Impact.NEGATIVE; else -> Impact.NEUTRAL }, it.detail,
            it.z, it.weight, it.points, it.value, it.usual, it.unit) },
        alerts.map { AlertUi(it.level == "warn" || it.level == "warning", it.title, it.detail) }, last?.sleepUi(), day?.steps, day?.activeMinutes,
        day?.awakeRestingHr?.roundToInt())
}
fun DailyResult.trendsUi(health: Map<String, Map<LocalDate, Double>>): TrendsUi {
    fun series(key: String, title: String, unit: String = "", baseline: Double? = null, bars: Boolean = false, accent: Boolean = false, value: (DayResult) -> Double?) =
        TrendSeries(key, title, unit, days.map { DayPoint(LocalDate.parse(it.date), value(it)) }, baseline, bars, accent)
    return TrendsUi(trends.vo2maxEstimate, trends.fitnessAge, trends.sleepRegularity?.roundToInt(), trends.cardioLoad7d, trends.acuteChronicRatio, listOf(
        series("readiness", "Readiness") { it.readiness?.toDouble() },
        series("rhr", "Sleeping heart rate", "bpm", trends.restingHr28d) { it.restingHr },
        series("awake_rhr", "Awake resting heart rate", "bpm", days.takeLast(28).mapNotNull { it.awakeRestingHr }.takeIf { it.isNotEmpty() }?.average()) { it.awakeRestingHr },
        series("hrv", "HRV", "ms", trends.hrv28d) { it.hrvRmssd },
        series("sleep", "Sleep", "h", bars = true) { it.sleepMinutes?.div(60.0) },
        series("sleep_score", "Sleep score") { it.sleepScore?.toDouble() },
        series("steps", "Steps", bars = true) { it.steps?.toDouble() },
        series("strain", "Strain", accent = true) { it.strain },
        series("active", "Active time", "min", bars = true, accent = true) { it.activeMinutes?.toDouble() },
        series("spo2", "Blood oxygen", "%") { it.spo2Avg },
        series("stress", "Stress", accent = true) { it.stressAvg },
        series("pai", "PAI", bars = true) { health["pai"]?.get(LocalDate.parse(it.date)) },
        series("temp", "Skin temperature", "°C") { health["temperature"]?.get(LocalDate.parse(it.date)) },
    ), days.map { NightSpan(LocalDate.parse(it.date), it.sleepStart, it.sleepEnd, it.sleepMinutes, it.sleepOngoing) })
}
fun SessionResult.sessionUi(id: String = file) = SessionUi(id, start.toLong(), durationS, signalQuality, hrBpm,
    hrv.rmssdMs, hrv.sdnnMs, hrv.pnn50, hrv.stressIndex, hrv.lfHf, respiratoryRate, rhythmStatus(rhythm.status),
    rhythm.ectopicBeats, rhythm.notes, rr.map { RrUi(it.t, it.rrMs, it.ectopic) }, ppgPreview.map { it.toFloat() }.toFloatArray(),
    previewFs ?: 25.0, beatTimes.toDoubleArray(), usableS, beatGood.toBooleanArray())

fun quietNow(minute: Int, start: Int?, end: Int?): Boolean {
    if (start == null || end == null || start == end) return false
    return if (start < end) minute in start until end else minute >= start || minute < end
}

/** Monotonic-clock window for identical notification title/text pairs across posting packages. */
class NotificationDeduplicator {
    private val seen = mutableMapOf<Pair<String, String>, Long>()
    fun accept(title: String, text: String, elapsedMs: Long): Boolean {
        seen.entries.removeAll { elapsedMs - it.value >= 10_000 }
        val key = title to text
        if (key in seen) return false
        seen[key] = elapsedMs
        return true
    }
}

/** The watch reports its last charge as an ISO timestamp; the UI shows "31 Oct, 04:10" in the phone's zone. */
fun chargedLabel(iso: String?, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String? = iso?.let {
    runCatching { java.time.OffsetDateTime.parse(it).atZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("d MMM, HH:mm")) }.getOrDefault(it)
}
