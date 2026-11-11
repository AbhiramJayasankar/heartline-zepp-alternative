package dev.heartline.app

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime

/** Meta key holding every imported or typed-in set as JSON. */
const val GYM_LOG_KEY = "gym_log"

/**
 * One set from a gym log, whatever format it came in. Times are epoch seconds when the log had them. [loadKg] is the
 * weight moved: for an assisted pull-up, body weight minus the assistance. Treadmill and other cardio rows carry
 * [speedKmh], [inclinePct], [distanceKm], [durationS], and the machine display's [steps] and [caloriesKcal].
 */
data class LoggedSet(
    val date: String, val session: String, val exercise: String, val kind: String,
    val setNumber: Int, val setType: String, val status: String,
    val start: Long? = null, val end: Long? = null, val restBeforeS: Double? = null,
    val loadKg: Double? = null, val assistKg: Double? = null, val reps: Double? = null,
    val failure: Boolean = false, val rir: Double? = null, val difficulty: Double? = null,
    val speedKmh: Double? = null, val inclinePct: Double? = null, val distanceKm: Double? = null, val durationS: Double? = null,
    val hrMin: Int? = null, val hrMax: Int? = null, val notes: String = "",
    /** Treadmill only: "none", "some" or "held". Held rows are left out of VO2max. */
    val handrails: String? = null,
    val steps: Int? = null, val caloriesKcal: Double? = null,
) {
    /** Same set in another export of the same session: date, exercise, set number and warm-up or not. */
    val key: String get() = "$date|${exercise.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()}|$setNumber|${if (setType.startsWith("warm")) "w" else "s"}"
    val cardio: Boolean get() = kind == "cardio" || speedKmh != null || exercise.contains("treadmill", ignoreCase = true)
    val completed: Boolean get() = status != "skipped"

    fun json(): JSONObject = JSONObject().apply {
        put("date", date); put("session", session); put("exercise", exercise); put("kind", kind); put("set", setNumber)
        put("set_type", setType); put("status", status); put("failure", failure); put("notes", notes)
        fun opt(k: String, v: Any?) { if (v != null) put(k, v) }
        opt("start", start); opt("end", end); opt("rest_s", restBeforeS); opt("load_kg", loadKg); opt("assist_kg", assistKg); opt("reps", reps)
        opt("rir", rir); opt("difficulty", difficulty); opt("speed_kmh", speedKmh); opt("incline_pct", inclinePct); opt("distance_km", distanceKm)
        opt("duration_s", durationS); opt("hr_min", hrMin); opt("hr_max", hrMax); opt("handrails", handrails); opt("steps", steps); opt("calories_kcal", caloriesKcal)
    }

    companion object {
        fun fromJson(j: JSONObject): LoggedSet {
            fun d(k: String) = if (j.has(k) && !j.isNull(k)) j.optDouble(k).takeIf { it.isFinite() } else null
            fun l(k: String) = if (j.has(k) && !j.isNull(k)) j.optLong(k) else null
            fun i(k: String) = if (j.has(k) && !j.isNull(k)) j.optInt(k) else null
            return LoggedSet(j.optString("date"), j.optString("session"), j.optString("exercise"), j.optString("kind"), j.optInt("set"),
                j.optString("set_type"), j.optString("status", "completed"), l("start"), l("end"), d("rest_s"), d("load_kg"), d("assist_kg"),
                d("reps"), j.optBoolean("failure"), d("rir"), d("difficulty"), d("speed_kmh"), d("incline_pct"), d("distance_km"), d("duration_s"),
                i("hr_min"), i("hr_max"), j.optString("notes"), j.optString("handrails").takeIf { it.isNotEmpty() },
                i("steps"), d("calories_kcal"))
        }
    }
}

/**
 * Reads a gym log: the per-set CSV (row_id, date, session, exercise, ... incline_pct, speed_kmh ...), the newer per-set
 * CSV (session_id, exercise, set_number, start_datetime_ist, ... weight_type, reps_reported ...), or the JSON export
 * (sessions, exercises, sets). [bodyWeightKg] turns assistance into weight moved.
 */
fun parseGymLog(text: String, bodyWeightKg: Double?): List<LoggedSet> {
    val trimmed = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
    return if (trimmed.startsWith("{")) parseJsonLog(JSONObject(trimmed), bodyWeightKg) else parseCsvLog(trimmed, bodyWeightKg)
}

private fun time(s: String?): Long? = s?.takeIf { it.isNotBlank() }?.let { runCatching { OffsetDateTime.parse(it.trim()).toEpochSecond() }.getOrNull() }
private fun num(s: String?): Double? = s?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
private fun kindOf(exercise: String, given: String?): String = when {
    !given.isNullOrBlank() -> given.trim().lowercase()
    exercise.contains(Regex("treadmill|bike|cycle|row(ing)? machine|elliptical|run|walk", RegexOption.IGNORE_CASE)) -> "cardio"
    else -> "strength"
}
private fun setTypeOf(s: String?): String = when (s?.trim()?.lowercase()) {
    null, "" -> "working"; "warmup", "warm-up", "warm up" -> "warm-up"; else -> s.trim().lowercase()
}

private fun parseCsvLog(text: String, weight: Double?): List<LoggedSet> {
    val rows = csvRows(text)
    if (rows.isEmpty()) return emptyList()
    val head = rows.first().map { it.trim().lowercase() }
    fun col(r: List<String>, name: String): String? = head.indexOf(name).takeIf { it >= 0 }?.let { r.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }
    return rows.drop(1).filter { r -> r.any { it.isNotBlank() } }.mapNotNull { r ->
        val exercise = col(r, "exercise") ?: return@mapNotNull null
        if ("session_id" in head) {
            // Newer format: one session per file, ISO times with offset, assistance as weight_type.
            val sid = col(r, "session_id").orEmpty()
            val start = time(col(r, "start_datetime_ist")); val end = time(col(r, "end_datetime_ist"))
            val date = Regex("^\\d{4}-\\d{2}-\\d{2}").find(sid)?.value ?: start?.let { java.time.Instant.ofEpochSecond(it).atOffset(java.time.ZoneOffset.ofHoursMinutes(5, 30)).toLocalDate().toString() } ?: ""
            val w = num(col(r, "weight_kg"))
            val type = col(r, "weight_type")?.lowercase()
            val assisted = type == "assistance"
            // Load moved: assistance comes off body weight, per-side plates count twice, bodyweight moves add a plate or belt.
            val load = when (type) {
                "assistance" -> weight?.let { b -> w?.let { b - it } }
                "per_side" -> w?.let { it * 2 }
                "bodyweight" -> weight?.let { b -> b + (w ?: 0.0) }
                else -> w
            }
            LoggedSet(date, col(r, "session_name") ?: sid.removePrefix(date).trim('_').replace('_', ' '), exercise,
                kindOf(exercise, col(r, "exercise_type")?.let { if (it == "bodyweight") "strength" else it }), num(col(r, "set_number"))?.toInt() ?: 0,
                setTypeOf(col(r, "set_type")), col(r, "status") ?: "completed", start, end, num(col(r, "rest_before_seconds")),
                load, if (assisted) w else null,
                num(col(r, "reps_reported")) ?: num(col(r, "reps")), col(r, "reached_failure").equals("true", ignoreCase = true), num(col(r, "reps_in_reserve")),
                num(col(r, "difficulty_out_of_10")), num(col(r, "speed_kmh")), num(col(r, "incline_pct")), num(col(r, "distance_km")),
                num(col(r, "duration_seconds")) ?: if (start != null && end != null) (end - start).toDouble() else null,
                notes = col(r, "notes").orEmpty(), handrails = col(r, "handrails")?.lowercase(),
                steps = num(col(r, "steps"))?.toInt(), caloriesKcal = num(col(r, "calories_kcal")))
        } else {
            // Older format: many sessions per file, load_basis tells assistance apart, cardio columns on treadmill rows.
            val load = num(col(r, "load_kg"))
            val assisted = col(r, "load_basis") == "assist_weight"
            LoggedSet(col(r, "date").orEmpty(), col(r, "session").orEmpty(), exercise, kindOf(exercise, col(r, "exercise_type")),
                num(col(r, "set_number"))?.toInt() ?: 0, setTypeOf(col(r, "set_type")), "completed",
                time(col(r, "start_time")), time(col(r, "end_time")), num(col(r, "rest_before_sec")),
                if (assisted) num(col(r, "effective_load_kg")) ?: weight?.let { b -> load?.let { b - it } } else num(col(r, "effective_load_kg")) ?: load,
                if (assisted) load else null, num(col(r, "reps")), false, null, num(col(r, "difficulty_0_10")),
                num(col(r, "speed_kmh")), num(col(r, "incline_pct")), num(col(r, "distance_km")), num(col(r, "duration_sec")),
                num(col(r, "hr_bpm_min"))?.toInt(), num(col(r, "hr_bpm_max"))?.toInt(), col(r, "notes").orEmpty(),
                steps = num(col(r, "steps"))?.toInt(), caloriesKcal = num(col(r, "calories_kcal")))
        }
    }
}

private fun parseJsonLog(root: JSONObject, weight: Double?): List<LoggedSet> {
    val out = ArrayList<LoggedSet>()
    val sessions = root.optJSONArray("sessions") ?: JSONArray()
    for (i in 0 until sessions.length()) {
        val s = sessions.getJSONObject(i)
        val date = s.optString("date"); val name = s.optString("name", s.optString("session"))
        val exercises = s.optJSONArray("exercises") ?: JSONArray()
        for (e in 0 until exercises.length()) {
            val ex = exercises.getJSONObject(e)
            val exercise = ex.optString("exercise")
            val sets = ex.optJSONArray("sets") ?: JSONArray()
            for (k in 0 until sets.length()) {
                val x = sets.getJSONObject(k)
                fun d(n: String) = if (x.has(n) && !x.isNull(n)) x.optDouble(n).takeIf { it.isFinite() } else null
                fun str(n: String) = if (x.has(n) && !x.isNull(n)) x.optString(n) else null
                val load = d("load_kg")
                val assisted = str("load_basis") == "assist_weight"
                out.add(LoggedSet(date, name, exercise, kindOf(exercise, str("exercise_type")), x.optInt("set_number"), setTypeOf(str("set_type")),
                    "completed", time(str("start_time")), time(str("end_time")), d("rest_before_sec"),
                    if (assisted) d("effective_load_kg") ?: weight?.let { b -> load?.let { b - it } } else d("effective_load_kg") ?: load,
                    if (assisted) load else null, d("reps"), false, null, d("difficulty_0_10"), d("speed_kmh"), d("incline_pct"), d("distance_km"),
                    d("duration_sec"), d("hr_bpm_min")?.toInt(), d("hr_bpm_max")?.toInt(), str("notes").orEmpty(),
                    steps = d("steps")?.toInt(), caloriesKcal = d("calories_kcal")))
            }
        }
    }
    return out
}

/** RFC 4180 rows: quoted fields may hold commas, quotes ("") and line breaks. */
internal fun csvRows(text: String): List<List<String>> {
    val rows = ArrayList<List<String>>()
    var row = ArrayList<String>()
    val field = StringBuilder()
    var quoted = false
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (quoted) {
            if (ch == '"' && i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ }
            else if (ch == '"') quoted = false
            else field.append(ch)
        } else when (ch) {
            '"' -> quoted = true
            ',' -> { row.add(field.toString()); field.setLength(0) }
            '\r' -> {}
            '\n' -> { row.add(field.toString()); field.setLength(0); rows.add(row); row = ArrayList() }
            else -> field.append(ch)
        }
        i++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
    return rows
}

/**
 * Adds [incoming] to [existing]: a set already there (same [LoggedSet.key]) is replaced only when the new copy has times
 * and the old one doesn't, or carries more filled-in fields, so re-importing an older export never loses detail.
 */
fun mergeGymLog(existing: List<LoggedSet>, incoming: List<LoggedSet>): Pair<List<LoggedSet>, Int> {
    val byKey = LinkedHashMap<String, LoggedSet>()
    existing.forEach { byKey[it.key] = it }
    var added = 0
    fun filled(s: LoggedSet) = s.json().length()
    for (s in incoming) {
        val old = byKey[s.key]
        if (old == null) { byKey[s.key] = s; added++ }
        else if ((s.start != null && old.start == null) || (s.start != null) == (old.start != null) && filled(s) > filled(old)) byKey[s.key] = s
    }
    return byKey.values.sortedWith(compareBy({ it.date }, { it.start ?: Long.MAX_VALUE })) to added
}

fun gymLogJson(sets: List<LoggedSet>): String = JSONArray(sets.map { it.json() }).toString()
fun gymLogFromJson(text: String?): List<LoggedSet> = runCatching {
    val a = JSONArray(text ?: "[]"); (0 until a.length()).map { LoggedSet.fromJson(a.getJSONObject(it)) }
}.getOrDefault(emptyList())

/** A logged session: its sets, in order, with the date it belongs to. */
data class LoggedSession(val date: LocalDate, val name: String, val sets: List<LoggedSet>) {
    val start: Long? get() = sets.mapNotNull { it.start }.minOrNull()
    val end: Long? get() = sets.mapNotNull { it.end ?: it.start }.maxOrNull()
    val volumeKg: Double get() = sets.filter { it.completed && !it.cardio }.sumOf { (it.loadKg ?: 0.0) * (it.reps ?: 0.0) }
    val workingSets: Int get() = sets.count { it.completed && !it.cardio && it.setType == "working" }
}

fun sessionsOf(sets: List<LoggedSet>): List<LoggedSession> = sets.groupBy { it.date to it.session }.map { (k, v) ->
    LoggedSession(runCatching { LocalDate.parse(k.first) }.getOrDefault(LocalDate.MIN), k.second, v.sortedWith(compareBy({ it.start ?: Long.MAX_VALUE }, { it.exercise }, { it.setNumber })))
}.sortedByDescending { it.date }

/** The day a session belongs to: one running past midnight counts on the evening it started. */
fun sessionDate(ts: Long, zone: java.time.ZoneId): LocalDate = java.time.Instant.ofEpochSecond(ts - 6 * 3600).atZone(zone).toLocalDate()

/** A synced watch workout as matching needs it: when, what type, and its heart rate track (epoch second, bpm). */
data class WatchWorkoutRef(val start: Long, val end: Long, val type: String, val track: List<Pair<Long, Int>>)

/**
 * The logged sets that belong to a watch workout: timed sets inside it (15 minutes either side), else, for a log
 * without times, the sets of the session on the same evening (treadmill rows for a treadmill workout, the other sets otherwise).
 */
fun setsForWorkout(log: List<LoggedSet>, start: Long, end: Long, type: String, zone: java.time.ZoneId): List<LoggedSet> {
    val treadmill = type == "Treadmill"
    val timed = log.filter { s -> s.start != null && s.start < end + 900 && (s.end ?: s.start) > start - 900 }
    if (timed.isNotEmpty()) return if (treadmill) timed.filter { it.cardio } else timed
    val day = sessionDate(start, zone).toString()
    return log.filter { it.start == null && it.date == day && it.cardio == treadmill }
}

/**
 * Treadmill stretches with speed and incline, each matched to watch heart rate. A timed one uses its own times; an
 * untimed one uses the stretch of its logged length with the highest heart rate inside the same evening's treadmill
 * workout. The steady heart rate is the median over the stretch's last 5 minutes.
 */
fun treadmillEfforts(log: List<LoggedSet>, watch: List<WatchWorkoutRef>, zone: java.time.ZoneId): List<dev.heartline.analytics.TreadmillEffort> {
    val allHr = watch.flatMap { it.track }
    return log.filter { it.cardio && it.completed && it.speedKmh != null && it.inclinePct != null && it.handrails != "held" }.map { s ->
        val length = s.durationS ?: s.start?.let { a -> s.end?.let { (it - a).toDouble() } } ?: 600.0
        val window: Pair<Long, Long>? = if (s.start != null) s.start to (s.end ?: (s.start + length.toLong())) else
            watch.filter { it.type == "Treadmill" && sessionDate(it.start, zone).toString() == s.date }.maxByOrNull { it.end - it.start }?.let { w ->
                // Search only where the track has readings: the watch can keep a workout open after the walk ends.
                val first = w.track.minOfOrNull { it.first } ?: w.start; val last = w.track.maxOfOrNull { it.first } ?: w.end
                var best = first to minOf(last, first + length.toLong()); var bestHr = -1.0
                var t = first
                while (t + length <= last + 30) {
                    val inside = w.track.filter { it.first >= t && it.first < t + length }.map { it.second }
                    if (inside.size >= 10) inside.average().let { if (it > bestHr) { bestHr = it; best = t to t + length.toLong() } }
                    t += 30
                }
                best
            }
        val hr = window?.let { (a, b) ->
            allHr.filter { it.first >= maxOf(a, b - 300) && it.first <= b }.map { it.second }.sorted().takeIf { it.size >= 5 }?.let { it[it.size / 2] }
        }
        val start = window?.first ?: runCatching { LocalDate.parse(s.date).atTime(20, 0).atZone(zone).toEpochSecond() }.getOrDefault(0L)
        dev.heartline.analytics.TreadmillEffort(start, length, s.speedKmh!!, s.inclinePct!!, hr)
    }
}

/** Timed logged sessions as (start, end) spans, a minute either side, so their minutes count as workout time. */
fun loggedSpans(log: List<LoggedSet>): List<Pair<Long, Long>> =
    sessionsOf(log).mapNotNull { s -> s.start?.let { a -> s.end?.let { b -> (a - 60) to (b + 60) } } }

/** Stored watch workouts with their heart rate tracks as absolute times. */
fun watchRefs(rows: List<dev.heartline.data.model.Workout>): List<WatchWorkoutRef> = rows.mapNotNull { w ->
    runCatching {
        val j = JSONObject(w.data)
        val track = JSONArray(w.track ?: "[]").let { a ->
            (0 until a.length()).mapNotNull { i -> a.getJSONObject(i).let { p -> p.optInt("hr").takeIf { it in 30..230 }?.let { (w.start + p.optLong("t")) to it } } }
        }
        WatchWorkoutRef(w.start, j.optLong("end", w.start + j.optLong("duration_s")), j.optString("type"), track)
    }.getOrNull()
}
