package dev.heartline.service

import dev.heartline.app.HeartlineController
import dev.heartline.app.MinuteState
import dev.heartline.app.MinuteUi
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only health lookups for the Alexa brain, from the same data the app's screens show. Each returns compact text,
 * one fact per line, times as HH:mm on the phone's clock; missing data is said, never guessed.
 */
class HealthTools(
    private val controller: HeartlineController,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val today: () -> LocalDate = { LocalDate.now(zone) },
) {
    private fun obj(vararg props: Pair<String, JSONObject>) =
        JSONObject().put("type", "object").put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } }).put("required", JSONArray())
    private val date = "date" to JSONObject().put("type", "string").put("description", "YYYY-MM-DD, or today or yesterday; today if left out")

    val tools = listOf(
        Tool("get_heart_rate", "Heart rate for a day: resting, lowest, average, highest, time in zones and hour by hour. Use it to describe or analyse heart rate.", obj(date)),
        Tool("get_stress", "Stress for a day (0 to 100): average, highest, time in each band and hour by hour. Use it for questions about stress.", obj(date)),
        Tool("get_sleep", "The night's sleep that ended on the morning of a date: bed and wake time, stages, awakenings and a stage timeline.", obj(date)),
        Tool("get_activity", "Steps, distance, calories, active minutes, PAI, steps by hour and the watch's workouts for a day.", obj(date)),
        Tool("get_spo2_and_temperature", "Blood oxygen (SpO2) readings and skin temperature for a day.", obj(date)),
        Tool("get_readiness", "Today's readiness score, what drove it, strain, sleep score, body battery, resting heart rate and Heartline's suggestion.", obj()),
        Tool("get_trends", "Daily values over recent days (readiness, resting heart rate, HRV, sleep, steps, strain, active time) with the usual level, plus VO2max and training load.",
            obj("days" to JSONObject().put("type", "integer").put("description", "How many days, 7 to 28; 14 if left out"))),
        Tool("get_workout_captures", "The newest workout captures recorded with Heartline (gym sessions with sets and reps).",
            obj("limit" to JSONObject().put("type", "integer").put("description", "How many, 1 to 10; 5 if left out"))),
    )

    /** Runs tool [name]; null if it isn't one of [tools]. Bad arguments come back as an error line. */
    suspend fun run(name: String, args: JSONObject): String? {
        if (tools.none { it.name == name }) return null
        val day = when (val d = args.optString("date").trim().lowercase(Locale.ROOT)) {
            "", "today", "null" -> today()
            "yesterday" -> today().minusDays(1)
            else -> runCatching { LocalDate.parse(d) }.getOrNull() ?: return "Error: date must be YYYY-MM-DD, today or yesterday"
        }
        if (day.isAfter(today())) return "Error: $day is in the future"
        return when (name) {
            "get_heart_rate" -> heartRate(day)
            "get_stress" -> stress(day)
            "get_sleep" -> sleep(day)
            "get_activity" -> activity(day)
            "get_spo2_and_temperature" -> spo2AndTemperature(day)
            "get_readiness" -> readiness()
            "get_trends" -> trends(args.optInt("days", 14).coerceIn(7, 28))
            else -> captures(args.optInt("limit", 5).coerceIn(1, 10))
        }
    }

    private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val dayName = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private fun at(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(zone).format(clock)
    private fun hm(min: Int) = if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
    private fun r(v: Double?, digits: Int = 0) = v?.let { if (digits == 0) it.roundToInt().toString() else "%.${digits}f".format(Locale.ROOT, it) } ?: "?"
    private fun label(d: LocalDate) = d.format(dayName) + if (d == today()) " (today)" else ""

    private suspend fun healthDay(d: LocalDate) = withTimeoutOrNull(8_000) { controller.healthDay(d).first() }
    private suspend fun activityDay(d: LocalDate) = withTimeoutOrNull(8_000) { controller.activityDay(d).first() }

    private suspend fun heartRate(d: LocalDate): String {
        val h = healthDay(d) ?: return "Heart rate for ${label(d)} isn't available."
        if (h.hr.isEmpty()) return "No heart rate recorded on ${label(d)}."
        val lines = arrayListOf("Heart rate on ${label(d)} (bpm): resting ${h.restingHr ?: "?"}, lowest ${h.hrMin ?: "?"}, average ${h.hrAvg ?: "?"}, highest ${h.hrMax ?: "?"}" +
            if (h.hrHighMinutes > 0) "; ${h.hrHighMinutes} min above the high threshold" else "")
        h.hr.maxByOrNull { it.t }?.let { lines += "Latest reading: ${r(it.v)} bpm at ${at(it.t.toLong())}" }
        h.hrZones.filter { it.seconds >= 60 }.takeIf { it.isNotEmpty() }?.let { z ->
            lines += "Time in zones: " + z.joinToString(", ") { "${it.name} (${it.lowBpm}${it.highBpm?.let { hi -> "-$hi" } ?: "+"}) ${hm(it.seconds / 60)}" }
        }
        lines += "By hour (min/avg/max):"
        h.hrByHour.filter { it.avg != null }.forEach { lines += "%02d:00 ${r(it.min)}/${r(it.avg)}/${r(it.max)}".format(it.hour) }
        return lines.joinToString("\n")
    }

    private suspend fun stress(d: LocalDate): String {
        val h = healthDay(d) ?: return "Stress for ${label(d)} isn't available."
        if (h.stress.isEmpty()) return "No stress readings on ${label(d)}."
        val lines = arrayListOf("Stress on ${label(d)} (0 to 100): average ${h.stressAvg ?: "?"}, highest ${h.stressMax ?: "?"}, ${h.stress.size} readings")
        h.stressBands.filter { it.minutes > 0 }.takeIf { it.isNotEmpty() }?.let { b -> lines += "Time in bands: " + b.joinToString(", ") { "${it.name} (${it.low}-${it.high}) ${hm(it.minutes)}" } }
        val hours = h.stressByHour.filter { it.avg != null }
        hours.sortedByDescending { it.avg }.take(3).takeIf { it.isNotEmpty() }?.let { top ->
            lines += "Most stressful hours: " + top.joinToString(", ") { "%02d:00 (average ${r(it.avg)})".format(it.hour) }
        }
        lines += "By hour (avg/max):"
        hours.forEach { lines += "%02d:00 ${r(it.avg)}/${r(it.max)}".format(it.hour) }
        return lines.joinToString("\n")
    }

    private suspend fun sleep(d: LocalDate): String {
        val day = activityDay(d) ?: return "Sleep for the night before ${label(d)} isn't available."
        val n = day.sleep ?: return "No sleep recorded for the night before ${label(d)}."
        val asleep = n.deepMin + n.lightMin + n.remMin
        val lines = arrayListOf("Sleep ending ${label(d)}: in bed ${at(n.start)} to ${at(n.end)}, ${hm(asleep)} asleep" +
            " (deep ${hm(n.deepMin)}, light ${hm(n.lightMin)}, REM ${hm(n.remMin)}, awake ${hm(n.awakeMin)})")
        if (d == today()) controller.today.value?.sleepScore?.let { lines += "Sleep score: $it out of 100" }
        // The night can start the evening before, so take the minutes of both days.
        val minutes = ((activityDay(d.minusDays(1))?.minutes ?: emptyList()) + day.minutes).filter { it.ts in n.start until n.end }.sortedBy { it.ts }
        val segments = segments(minutes)
        lines += "Awakenings: ${segments.count { it.second == "awake" }}"
        if (segments.isNotEmpty()) {
            lines += "Timeline:"
            segments.filter { (it.third - it.first) >= 3 * 60 }.forEach { (from, stage, to) -> lines += "${at(from)}-${at(to)} $stage" }
        }
        return lines.joinToString("\n")
    }

    /** Consecutive minutes of the same stage as (start, stage, end). */
    private fun segments(minutes: List<MinuteUi>): List<Triple<Long, String, Long>> {
        fun stage(m: MinuteUi) = when (m.state) {
            MinuteState.SLEEP_DEEP -> "deep"; MinuteState.SLEEP_LIGHT -> "light"; MinuteState.SLEEP_REM -> "REM"
            MinuteState.NOT_WORN, MinuteState.CHARGING -> "watch off"; else -> "awake"
        }
        val out = ArrayList<Triple<Long, String, Long>>()
        for (m in minutes) {
            val s = stage(m)
            val last = out.lastOrNull()
            if (last != null && last.second == s && m.ts - last.third <= 120) out[out.size - 1] = Triple(last.first, s, m.ts + 60)
            else out += Triple(m.ts, s, m.ts + 60)
        }
        return out
    }

    private suspend fun activity(d: LocalDate): String {
        val h = healthDay(d) ?: return "Activity for ${label(d)} isn't available."
        val lines = arrayListOf("Activity on ${label(d)}: ${h.stepsTotal} steps" + listOfNotNull(h.distanceM?.let { "${r(it / 1000.0, 1)} km" },
            h.caloriesKcal?.let { "$it kcal" }, "${h.activeMinutes} active minutes", "watch worn ${hm(h.wornMinutes)}").joinToString(", ", ", "))
        if (h.paiToday != null || h.paiTotal != null) lines += "PAI: ${r(h.paiToday)} that day, ${r(h.paiTotal)} over the last 7 days"
        h.stepsByHour.filter { (it.total ?: 0.0) > 0 }.takeIf { it.isNotEmpty() }?.let { s -> lines += "Steps by hour: " + s.joinToString(", ") { "%02d:00 ${r(it.total)}".format(it.hour) } }
        if (h.workouts.isEmpty()) lines += "No workouts recorded by the watch."
        h.workouts.forEach { w ->
            lines += "Workout: ${w.type} at ${at(w.start)}, ${hm(w.durationS / 60)}" + listOfNotNull(w.avgHr?.let { "average heart rate $it" }, w.maxHr?.let { "max $it" },
                w.calories?.let { "$it kcal" }, w.distanceM?.let { "${r(it / 1000.0, 2)} km" }, w.steps?.let { "$it steps" }).joinToString(", ", ", ")
        }
        return lines.joinToString("\n")
    }

    private suspend fun spo2AndTemperature(d: LocalDate): String {
        val h = healthDay(d) ?: return "Blood oxygen and temperature for ${label(d)} aren't available."
        val lines = ArrayList<String>()
        if (h.spo2.isEmpty()) lines += "No SpO2 readings on ${label(d)}." else {
            lines += "SpO2 on ${label(d)}: average ${r(h.spo2Avg)}%, lowest ${r(h.spo2Min)}%, ${h.spo2.size} readings"
            h.spo2Bands.filter { it.minutes > 0 }.takeIf { it.isNotEmpty() }?.let { b -> lines += "Time in bands: " + b.joinToString(", ") { "${it.name} ${hm(it.minutes)}" } }
            val step = maxOf(1, h.spo2.size / 30)
            lines += "Readings: " + h.spo2.filterIndexed { i, _ -> i % step == 0 }.take(30).joinToString(", ") { "${at(it.t.toLong())} ${r(it.v)}%" }
        }
        if (h.temperature.isEmpty()) lines += "No skin temperature readings on ${label(d)}." else {
            lines += "Skin temperature: average ${r(h.tempAvg, 1)} °C, lowest ${r(h.tempMin, 1)}, highest ${r(h.tempMax, 1)}"
            lines += "By hour: " + h.temperature.groupBy { Instant.ofEpochSecond(it.t.toLong()).atZone(zone).hour }.toSortedMap()
                .entries.joinToString(", ") { (hour, v) -> "%02d:00 ${r(v.map { it.v }.average(), 1)}".format(hour) }
        }
        return lines.joinToString("\n")
    }

    private fun readiness(): String {
        val t = controller.today.value ?: return "Readiness isn't computed yet."
        val lines = ArrayList<String>()
        lines += "${t.readinessLabel}: ${t.readiness?.let { "$it out of 100" } ?: "not available"}"
        t.recommendation?.let { lines += "Suggestion: $it" }
        lines += listOfNotNull(t.sleepScore?.let { "sleep score $it" }, t.strain?.let { "strain ${r(it, 1)}" }, t.bodyBattery?.let { "body battery $it" },
            t.restingHr?.let { "resting heart rate $it bpm" }, t.activeMinutes?.let { "$it active minutes today" }).joinToString(", ").ifEmpty { "No other scores yet." }
        t.factors.forEach { lines += "Factor: ${it.name} (${it.impact.name.lowercase()}): ${it.detail}" }
        t.alerts.forEach { lines += "Alert: ${it.title}. ${it.detail}" }
        return lines.joinToString("\n")
    }

    private fun trends(days: Int): String {
        val t = controller.trends.value ?: return "Trends aren't computed yet."
        val lines = ArrayList<String>()
        listOfNotNull(t.vo2max?.let { "VO2max ${r(it, 1)}" }, t.fitnessAge?.let { "fitness age $it" }, t.sleepRegularity?.let { "sleep regularity $it out of 100" },
            t.load7d?.let { "7-day training load ${r(it)}" }, t.acwr?.let { "acute to chronic load ratio ${r(it, 2)}" })
            .takeIf { it.isNotEmpty() }?.let { lines += it.joinToString(", ") }
        t.series.forEach { s ->
            val points = s.points.filter { !it.date.isBefore(today().minusDays(days - 1L)) && it.value != null }
            if (points.isEmpty()) return@forEach
            lines += "${s.title}${if (s.unit.isNotBlank()) " (${s.unit})" else ""}${s.baseline?.let { ", usual ${r(it, 1)}" } ?: ""}: " +
                points.joinToString(", ") { "${it.date.format(dayName)} ${r(it.value, if (kotlin.math.abs(it.value!!) >= 100) 0 else 1)}" }
        }
        return lines.joinToString("\n").ifEmpty { "No trend data yet." }
    }

    private fun captures(limit: Int): String {
        val c = controller.captures.value.sortedByDescending { it.start }.take(limit)
        if (c.isEmpty()) return "No workout captures recorded with Heartline yet."
        return c.joinToString("\n") { w ->
            "${w.label}: ${Instant.ofEpochSecond(w.start).atZone(zone).format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH))}, ${hm(w.durationS / 60)}" +
                listOfNotNull(w.avgHr?.let { "average heart rate $it" }, w.sets?.let { "$it sets" }, w.load?.let { "load ${r(it)}" }).joinToString(", ", ", ")
        }
    }
}
