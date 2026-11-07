package dev.heartline.app

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** Meta key prefix for a capture's short name: JSON {title, basis, ai, tried}. */
internal const val CAPTURE_TITLE = "capture_title_"

/** Capture labels that say nothing about the session, so a name is worked out instead. */
private val GENERIC = setOf("", "gym", "workout", "weights", "session", "strength", "strength training", "training", "capture", "lifting", "exercise")
internal fun genericLabel(label: String): Boolean = label.trim().lowercase() in GENERIC

/** What a capture's name is made from: its exercises (with working sets), the watch's workouts and steady cardio, and when. */
internal data class TitleFacts(val exercises: List<Pair<String, Int>>, val watchWorkouts: List<String>, val cardio: List<String>,
                               val logName: String?, val start: Long, val durationS: Int, val sets: Int) {
    /** Changes whenever the facts do, so a log imported later renames the capture. */
    val basis: String get() = "${exercises.joinToString { "${it.first}:${it.second}" }}|${watchWorkouts.sorted()}|${cardio.sorted()}|$logName|$sets"

    fun prompt(zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        val t = Instant.ofEpochSecond(start).atZone(zone)
        appendLine("Session at ${"%02d:%02d".format(t.hour, t.minute)}, ${durationS / 60} minutes.")
        if (logName != null) appendLine("The workout log called it: $logName")
        if (exercises.isNotEmpty()) appendLine("Exercises (working sets): " + exercises.joinToString { "${it.first} (${it.second})" })
        else if (sets > 0) appendLine("$sets sets found from wrist motion; exercises not logged.")
        if (watchWorkouts.isNotEmpty()) appendLine("Workouts started on the watch: " + watchWorkouts.distinct().joinToString())
        if (cardio.isNotEmpty()) appendLine("Steady cardio: " + cardio.distinct().joinToString())
    }
}

internal fun titleFacts(a: CaptureAnalysisUi, log: List<LoggedSet>): TitleFacts {
    val session = sessionForCapture(log, a.start, a.durationS)
    val fromLog = session?.sets?.filter { it.completed }?.groupBy { it.exercise }?.map { (n, rows) -> n to rows.count { !it.setType.startsWith("warm") } }
    val fromSets = a.sets.mapNotNull { it.exercise }.groupingBy { it }.eachCount().toList()
    return TitleFacts(fromLog ?: fromSets, a.activities.map { it.type }, a.cardio.map { it.name ?: it.kind }, session?.name, a.start, a.durationS, a.sets.size)
}

/** Muscle groups by keyword, in the order a name lists them. */
private val GROUPS = listOf(
    // Core first, so "leg raises" and "hanging knee raises" don't count as legs.
    "Core" to Regex("crunch|plank|\\babs?\\b|core|leg raise|knee raise|hanging|sit-?up|russian twist", RegexOption.IGNORE_CASE),
    "Legs" to Regex("squat|leg|lunge|deadlift|calf|hamstring|glute|hip thrust", RegexOption.IGNORE_CASE),
    "Chest" to Regex("bench|chest|pec|push-?up|(?<!rear delt )fly|dip", RegexOption.IGNORE_CASE),
    "Back" to Regex("pull-?up|pull-?down|row|rear delt|lat\\b|back extension|\\bchin-?ups?\\b", RegexOption.IGNORE_CASE),
    "Shoulders" to Regex("shoulder|overhead press|lateral raise|military", RegexOption.IGNORE_CASE),
    // Forearm and wrist work is arm work on a biceps day; lifters don't name a day after it.
    "Biceps" to Regex("forearm|wrist|curl|bicep|preacher|hammer", RegexOption.IGNORE_CASE),
    "Triceps" to Regex("tricep|pushdown|skull|extension", RegexOption.IGNORE_CASE),
    "Cardio" to Regex("treadmill|run|walk|bike|cycl|row(ing)? machine|elliptical|stair", RegexOption.IGNORE_CASE),
)

/** A short name without AI: the two muscle groups with the most sets ("Back and biceps day"), or what the watch logged. */
internal fun localTitle(f: TitleFacts): String {
    val sets = LinkedHashMap<String, Int>().apply { GROUPS.forEach { put(it.first, 0) } }
    for ((name, n) in f.exercises) GROUPS.firstOrNull { it.second.containsMatchIn(name) }?.let { sets[it.first] = (sets[it.first] ?: 0) + maxOf(n, 1) }
    val lifting = sets.filter { it.key != "Cardio" && it.value > 0 }.entries.sortedByDescending { it.value }.take(2).map { it.key }
    return when {
        lifting.size == 2 -> "${lifting[0]} and ${lifting[1].lowercase()} day"
        lifting.size == 1 -> "${lifting[0].removeSuffix("s").takeIf { lifting[0] == "Legs" } ?: lifting[0]} day"
        (sets["Cardio"] ?: 0) > 0 || f.cardio.isNotEmpty() -> f.watchWorkouts.firstOrNull { it.contains("tread", true) || it.contains("walk", true) || it.contains("run", true) } ?: "Cardio"
        f.watchWorkouts.isNotEmpty() -> f.watchWorkouts.first()
        else -> if (f.sets > 0) "Weights, ${f.sets} sets" else "Workout"
    }
}

internal const val TITLE_SYSTEM = "You name gym sessions for a workout tracking app. Reply with JSON {\"name\": \"...\"}: 2 to 4 words in sentence case " +
    "saying what was trained, the way a lifter would, such as \"Back and biceps day\", \"Chest push day\", \"Pull day\", \"Leg day\", " +
    "\"Shoulders and arms\" or \"Treadmill incline walk\". Mention cardio only when it was a large part. No dates, times, emoji or punctuation."

/** The name from the model's JSON reply, or null when it isn't a short plain name. */
internal fun parseTitle(reply: String): String? = runCatching {
    JSONObject(reply.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()).optString("name")
}.getOrNull()?.trim()?.trim('.', '"')?.takeIf { it.length in 3..40 && it.split(' ').size <= 5 && it.none { c -> c == '\n' } }
