package dev.heartline.analytics

import java.time.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.*

@RunWith(Parameterized::class)
class DailyGoldenTest(private val name: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun names() = listOf("real", "synthetic", "no_profile", "in_progress", "empty").map { arrayOf(it) }
    }

    @Test fun matchesEveryPythonDailyField() {
        val source = fixture(if (name == "real") "daily_snapshot.json" else "daily_recipe.json").obj()
        val now = ZonedDateTime.parse(source["now"] as String)
        val columns = source["minute_columns"]?.array()?.map { it as String }
        var minutes = if (columns != null) source["minutes"].array().map { values ->
            val row = columns.zip(values.array()).toMap()
            MinuteSample(row["ts"].number().toLong(), row["kind"].number().toInt(), row["intensity"].number().toInt(),
                row["steps"].number().toInt(), row["hr"].nullableNumber()?.toInt(), row["sleep"].number().toInt(),
                row["deep"].number().toInt(), row["rem"].number().toInt(), row["state"] as String)
        } else syntheticMinutes(source["recipe"].obj(), now.zone)
        var health = source["health"].array().map { it.array().let { v -> HealthValue(v[0] as String, v[1].number().toLong(), v[2].number()) } }
        var checks = source["checks"].array().map { check -> check.obj().let {
            CheckSummary(it["start"].number(), it["rmssd_ms"].nullableNumber(), it["hr_bpm"].nullableNumber(),
                it["signal_quality"].number(), it["usable_s"].number(), it["rhythm_status"] as String?)
        } }
        var profile = source["profile"].obj().let { Profile(it["age"].nullableNumber()?.toInt(), it["sex"] as String?,
            it["weight_kg"].nullableNumber(), it["height_cm"].nullableNumber(), it["max_hr"].nullableNumber()?.toInt(),
            it["resting_hr"].nullableNumber()?.toInt()) }
        if (name == "no_profile" || name == "empty") profile = Profile()
        if (name == "in_progress") {
            val cut = now.toLocalDate().atStartOfDay(now.zone).plusHours(2).toEpochSecond()
            minutes = minutes.filter { it.ts < cut }; health = emptyList(); checks = emptyList()
        }
        if (name == "empty") { minutes = emptyList(); health = emptyList(); checks = emptyList() }
        // The goldens come from Python, which held back a night that might still be going on.
        val actual = computeDaily(minutes, health, checks, profile, source["days"].number().toInt(), now, holdOngoingNights = true)
        val expected = fixture("goldens/daily_$name.json")
        var numericCount = 0
        var maxDelta = 0.0
        val integerFields = setOf("sleep_minutes", "deep_min", "light_min", "rem_min", "awake_min", "sleep_score",
            "steps", "active_minutes", "worn_minutes", "readiness", "calories_active", "sleep_start", "sleep_end",
            "z1", "z2", "z3", "z4", "z5", "body_battery", "value", "fitness_age", "hr_max_used")
        assertTree(expected, actual.tree()) { path, v ->
            numericCount++
            if (path.substringAfterLast('.') in integerFields) 1.0 else max(1e-8, abs(v) * 0.01)
        }
        // Also measure exact parity separately; the requested tolerance must not hide systematic drift.
        fun delta(e: Any?, a: Any?) {
            when (e) {
                is Number -> maxDelta = max(maxDelta, abs(e.toDouble() - a.number()))
                is Map<*, *> -> for ((key, v) in e) delta(v, a.obj()[key])
                is List<*> -> for (i in e.indices) delta(e[i], a.array()[i])
            }
        }
        delta(expected, actual.tree())
        println("DAILY $name | $numericCount numeric fields | max absolute difference $maxDelta | all strings and nulls equal")
    }
}

internal fun syntheticMinutes(recipe: Map<String, Any?>, zone: ZoneId): List<MinuteSample> {
    val start = LocalDate.parse(recipe["start"] as String).atStartOfDay(zone)
    val end = LocalDate.parse(recipe["end"] as String).atStartOfDay(zone).plusHours(recipe["stop_hour"].number().toLong())
    val out = arrayListOf<MinuteSample>()
    var t = start
    while (t < end) {
        val day = java.time.temporal.ChronoUnit.DAYS.between(start.toLocalDate(), t.toLocalDate()).toInt()
        val asleep = t.hour >= 23 || t.hour < if (t.toLocalDate() == end.toLocalDate()) 5 else 7
        var state = if (asleep) when { t.minute < 15 -> "sleep_deep"; t.minute < 27 -> "sleep_rem"; else -> "sleep_light" } else "idle"
        var hr: Int? = if (asleep) 51 + day % 5 else 72 + day % 4
        var steps = if (asleep) 0 else 5
        if (t.hour == 16) { hr = if (day >= 25) 170 else 110; state = "active"; steps = 80 }
        if (t.hour == 14 && t.minute < 20) { state = "sleep_light"; hr = 57; steps = 0 }
        if (t.hour == 2 && t.minute in 20..24) { state = "idle"; steps = 0 }
        if (t.hour == 10 && t.minute < 20) { state = "not_worn"; hr = null; steps = 0 }
        out.add(MinuteSample(t.toEpochSecond(), 0, 0, steps, hr, 0, 0, 0, state))
        t = t.plusMinutes(1)
    }
    return out
}
