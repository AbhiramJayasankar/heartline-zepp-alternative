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

    /** The get_info topics Heartline's data answers, with what each gives. */
    val topics: List<Pair<String, String>> = TOPICS

    /**
     * One lookup tool for everything the brain can read (fewer tools keep the model's choice simple): [topics] plus
     * [extraTopics] that the assistant answers itself (weather, the phone).
     */
    fun tool(extraTopics: List<Pair<String, String>> = emptyList()): Tool {
        val all = topics + extraTopics
        return Tool("get_info", "Look something up. Topics: " + all.joinToString("; ") { (t, d) -> "$t: $d" } +
            ". Call it several times for several topics.",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("topic", JSONObject().put("type", "string").put("enum", JSONArray(all.map { it.first })))
                .put("date", JSONObject().put("type", "string").put("description", "For a day's topics: YYYY-MM-DD, today or yesterday; today if left out"))
                .put("days", JSONObject().put("type", "integer").put("description", "For trends: 7 to 28; 14 if left out"))
                .put("limit", JSONObject().put("type", "integer").put("description", "For workouts: 1 to 10; 5 if left out")))
                .put("required", JSONArray(listOf("topic"))))
    }

    val tools = listOf(tool())

    /** Answers get_info for one of [topics]; null for any other tool or topic. Bad arguments come back as an error line. */
    suspend fun run(name: String, args: JSONObject): String? {
        if (name != "get_info") return null
        val topic = args.optString("topic").trim().lowercase(Locale.ROOT)
        if (topics.none { it.first == topic }) return null
        val day = when (val d = args.optString("date").trim().lowercase(Locale.ROOT)) {
            "", "today", "null" -> today()
            "yesterday" -> today().minusDays(1)
            else -> runCatching { LocalDate.parse(d) }.getOrNull() ?: return "Error: date must be YYYY-MM-DD, today or yesterday"
        }
        if (day.isAfter(today())) return "Error: $day is in the future"
        return when (topic) {
            "heart_rate" -> heartRate(day)
            "stress" -> stress(day)
            "sleep" -> sleep(day)
            "activity" -> activity(day)
            "spo2_temperature" -> spo2AndTemperature(day)
            "readiness" -> readiness()
            "trends" -> trends(args.optInt("days", 14).coerceIn(7, 28))
            "hrv" -> hrv(day)
            "notes" -> controller.notes.value.trim().let { if (it.isEmpty()) "The quick notes are empty." else "Quick notes:\n$it" }
            "ai_insights" -> insights()
            else -> captures(args.optInt("limit", 5).coerceIn(1, 10))
        }
    }

    companion object {
        val TOPICS = listOf(
            "readiness" to "today's readiness score, what drove it, strain, sleep score, body battery and the suggestion",
            "sleep" to "the night that ended on the morning of date: bed and wake time, stages, awakenings, timeline",
            "heart_rate" to "a day's resting, lowest, average and highest heart rate, zones and hour by hour",
            "hrv" to "heart rate variability: the night before date (each overnight sample, median, vs usual), daytime samples, heart checks, last 7 nights",
            "stress" to "a day's stress (0 to 100): average, highest, bands, hour by hour",
            "activity" to "a day's steps, distance, calories, active minutes, PAI and the watch's workouts",
            "spo2_temperature" to "a day's blood oxygen readings and skin temperature",
            "trends" to "daily values over recent days with the usual level, VO2max and training load",
            "workouts" to "the newest workout captures recorded with Heartline (sets, reps, heart rate)",
            "ai_insights" to "the latest AI insights report about their health",
            "notes" to "the quick notes they keep in the Heartline app",
        )
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
        val lines = arrayListOf("Heart rate on ${label(d)} (bpm): sleeping (lowest 30 min asleep) ${h.restingHr ?: "?"}, awake resting ${h.awakeRestingHr ?: "?"}, lowest ${h.hrMin ?: "?"}, average ${h.hrAvg ?: "?"}, highest ${h.hrMax ?: "?"}" +
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
            if (h.spo2SetAside.isNotEmpty()) lines += "${h.spo2SetAside.size} more overnight readings (${r(h.spo2SetAside.minOf { it.v })}–${r(h.spo2SetAside.maxOf { it.v })}%) " +
                "were set aside and are not in the figures here: they stepped down when the wearer settled into a sleeping position and back up " +
                "at the next movement, the signature of the watch being pressed on, not of low blood oxygen. Don't treat them as oxygen dips. " +
                "Wrist SpO2 is a rough guide and can't diagnose breathing problems."
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
            t.restingHr?.let { "sleeping heart rate $it bpm" }, t.awakeRestingHr?.let { "awake resting heart rate $it bpm" }, t.activeMinutes?.let { "$it active minutes today" }).joinToString(", ").ifEmpty { "No other scores yet." }
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

    private fun median(v: List<Double>): Double? = v.sorted().takeIf { it.isNotEmpty() }?.let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }

    private fun hrv(d: LocalDate): String {
        val o = controller.overnight.value
        val lines = ArrayList<String>()
        fun sample(x: dev.heartline.app.OvernightSampleUi) = "${at(x.ts)} " + (x.rmssd?.let { "${r(it)} ms" + (x.hr?.let { h -> ", ${r(h)} bpm" } ?: "") } ?: "set aside (${x.reason ?: "unclear signal"})")
        val night = o.samples.filter { it.night == d }.sortedBy { it.ts }
        val nights = o.samples.filter { it.rmssd != null && it.night != null }.groupBy { it.night!! }.mapValues { (_, v) -> median(v.map { it.rmssd!! })!! }
        lines += "Overnight HRV checks: ${if (o.enabled) "on (every ${o.nightEvery} min, ${o.nightLen} min long), ${o.status}" else "off"}"
        if (night.isEmpty()) lines += "No overnight samples for the night before ${label(d)}."
        else {
            val clean = night.mapNotNull { it.rmssd }
            val earlier = nights.filterKeys { it < d && it >= d.minusDays(14) }.values.toList()
            val usual = earlier.takeIf { it.isNotEmpty() }?.let { v -> kotlin.math.exp(v.sumOf { kotlin.math.ln(it) } / v.size) }
            lines += "Night before ${label(d)}: ${clean.size} clean of ${night.size} samples" + (median(clean)?.let { m ->
                ", median ${r(m)} ms (range ${r(clean.min())} to ${r(clean.max())})" + (usual?.let { u -> "; usual ${r(u)} ms over ${earlier.size} earlier night(s), so ${r((m / u - 1) * 100)}%" } ?: "")
            } ?: "")
            lines += "Samples: " + night.takeLast(30).joinToString("; ") { sample(it) }
        }
        val day = o.daySamples.filter { Instant.ofEpochSecond(it.ts).atZone(zone).toLocalDate() == d }.sortedBy { it.ts }
        lines += "Daytime HRV checks: ${if (o.allDay) "on (every ${o.dayEvery} min, ${o.dayLen} min long), ${o.dayStatus}" else "off"}"
        if (day.isNotEmpty()) {
            val clean = day.mapNotNull { it.rmssd }
            lines += "Daytime on ${label(d)}: ${clean.size} clean of ${day.size}" + (median(clean)?.let { ", median ${r(it)} ms" } ?: "") + ". " + day.takeLast(30).joinToString("; ") { sample(it) }
        }
        val checks = controller.checks.value.filter { Instant.ofEpochSecond(it.start).atZone(zone).toLocalDate() == d }.sortedBy { it.start }
        if (checks.isNotEmpty()) lines += "Heart checks on ${label(d)}: " + checks.joinToString("; ") { c ->
            "${at(c.start)} ${c.rmssd?.let { "${r(it)} ms" } ?: "HRV not measured"}, ${c.hr?.let { "${r(it)} bpm" } ?: "?"}, rhythm ${c.rhythm.name.lowercase(Locale.ROOT).replace('_', ' ')}"
        }
        nights.filterKeys { it <= d }.toSortedMap().entries.toList().takeLast(7).takeIf { it.isNotEmpty() }?.let { last ->
            lines += "Last nights (median RMSSD): " + last.joinToString(", ") { (n, v) -> "${n.format(DateTimeFormatter.ofPattern("EEE d", Locale.ENGLISH))} ${r(v)} ms" }
        }
        return lines.joinToString("\n")
    }

    private fun insights(): String {
        val u = controller.insights.value
        val rep = u.report ?: return if (u.running) "Heartline is writing the AI insights now; none are saved yet." else "No AI insights have been written yet."
        val lines = arrayListOf("AI insights from ${Instant.ofEpochSecond(rep.at).atZone(zone).format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH))}" +
            (if (u.running) " (a newer report is being written)" else "") + ": ${rep.headline}")
        rep.insights.forEach { i -> lines += "${i.area} (${i.tone}): ${i.title}. ${i.detail}" + (i.action?.takeIf { it.isNotBlank() }?.let { " Suggestion: $it" } ?: "") }
        return lines.joinToString("\n")
    }
}
