package dev.heartline.app

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Meta key prefix for a capture's coach's note: JSON {note, next[], model, at, basis}. */
internal const val CAPTURE_COACH = "capture_coach_"

/** A short coach's note on one session: [note] in 2 or 3 sentences, [next] up to 3 things to try next time. */
/** [stale]: the page's numbers changed after the note was written (a log edit, say); it is still shown until rewritten. */
data class CoachUi(val note: String, val next: List<String>, val model: String, val at: Long, val stale: Boolean = false)

private fun num(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)

/** One logged set as the coach reads it: "50 kg x 7 (rest 3:20, 1 in reserve, to failure; stopped short)". */
private fun setLine(r: LoggedSet): String {
    val what = when {
        r.cardio -> listOfNotNull(r.speedKmh?.let { "${num(it)} km/h" }, r.inclinePct?.let { "${num(it)}% incline" },
            r.durationS?.let { mmss(it) }).joinToString(", ")
        r.assistKg != null -> "assisted ${num(r.assistKg)} kg x ${r.reps?.let(::fmtReps) ?: "?"}"
        else -> "${r.loadKg?.let { "${num(it)} kg" } ?: "bodyweight"} x ${r.reps?.let(::fmtReps) ?: "?"}"
    }
    val extra = listOfNotNull(
        r.restBeforeS?.let { "rest ${mmss(it)}" }, r.rir?.let { "${num(it)} in reserve" }, if (r.failure) "to failure" else null,
        r.difficulty?.let { "difficulty ${num(it)}/10" }, r.notes.trim().takeIf { it.isNotEmpty() },
    )
    return (if (r.setType.startsWith("warm")) "warm-up " else "") + what + (if (extra.isEmpty()) "" else " (${extra.joinToString("; ")})")
}

/**
 * What the coach is given: the page's own numbers as plain lines, and nothing else, so it can't invent measurements.
 * [day] is an optional line on readiness and sleep going in.
 */
internal fun coachFacts(a: CaptureAnalysisUi, ins: SessionInsights, day: String? = null): String = buildString {
    appendLine("Session \"${ins.session.name}\" on ${ins.session.date}: ${a.durationS / 60} min recorded, average heart rate ${a.avgHr ?: "?"}, max ${a.maxHr ?: "?"}.")
    day?.let { appendLine(it) }
    appendLine("Exercises in the order done. Sets are load x reps; partial reps show as decimals (5.4 = 5 full and a partial).")
    for (e in ins.exercises) {
        val rows = ins.session.sets.filter { it.exercise == e.exercise }
        appendLine("- ${e.exercise}: " + rows.filter { it.completed }.joinToString("; ", transform = ::setLine))
        e.best?.let { appendLine("  best estimated 1RM ${num(it.e1rmKg)} kg, from ${num(it.loadKg)} kg x ${fmtReps(it.reps)}") }
        if (e.volumeKg > 0) appendLine("  working volume ${e.volumeKg.roundToInt()} kg over ${e.workingSets} sets")
        e.prev?.let { p ->
            appendLine("  last time, ${p.date}" + (if (!p.name.equals(e.exercise, true)) " (logged as ${p.name})" else "") +
                ": ${p.workingSets} working sets, volume ${p.volumeKg.roundToInt()} kg" +
                (p.best?.let { b -> ", best ${num(b.loadKg)} kg x ${fmtReps(b.reps)} (estimated 1RM ${num(b.e1rmKg)} kg)" } ?: ""))
        } ?: appendLine("  first time in the log")
        e.avgRise?.let { appendLine("  heart rate rose about ${it.roundToInt()} bpm a set, highest ${e.peakHr ?: "?"}") }
        if (e.skipped.isNotEmpty()) appendLine("  skipped: " + e.skipped.joinToString("; ", transform = ::setLine))
    }
    ins.session.sets.filter { it.cardio && it.completed }.forEach { appendLine("- ${it.exercise}: ${setLine(it)}") }
    a.cardio.filter { it.name != null || it.end - it.start >= 300 }.forEach { k ->
        appendLine("Cardio block ${k.name ?: k.kind}: steady ${k.steadyHr ?: "?"} bpm, peak ${k.peakHr ?: "?"}, fell ${k.drop60 ?: "?"} bpm in the minute after.")
    }
    ins.restReps.mapNotNull { r -> r.verdict?.let { "${r.exercise} at ${num(r.loadKg)} kg: $it" } }.takeIf { it.isNotEmpty() }?.let {
        appendLine("Rest against reps:"); it.forEach { line -> appendLine("- $line") }
    }
    if (ins.findings.isNotEmpty()) {
        appendLine("What the app already pointed out:")
        ins.findings.forEach { appendLine("- ${it.title} ${it.text.replace('\n', ' ')}") }
    }
}

internal const val COACH_SYSTEM = "You are a strength coach writing a short note in a workout tracking app about one gym session. " +
    "Reply with JSON {\"note\": \"...\", \"next\": [\"...\"]}. " +
    "note: 2 or 3 plain sentences on what went well and what held the session back. Use only numbers that appear in the data; " +
    "do not work out new figures, percentages or estimates. " +
    "next: 1 to 3 specific things to try next session, each starting with the exercise name and giving a load, a rep target or a rest, " +
    "for example \"Incline bench: stay at 50 kg and aim for 7, 7, 6 with 3:30 rest\". Add load only where sets reached the rep target " +
    "with reps in reserve; repeat the load where sets went to failure or fell short. Respect notes such as no spotter. " +
    "Heart rate here is a wrist estimate; don't make it the point unless it stands out. " +
    "No medical advice, no emoji, no praise filler, no headings, no markdown."

/** The note from the model's JSON reply, or null when it doesn't have the expected shape. */
internal fun parseCoach(reply: String): Pair<String, List<String>>? = runCatching {
    val j = JSONObject(reply.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
    val note = j.optString("note").trim()
    val next = j.optJSONArray("next")?.let { arr -> (0 until arr.length()).map { arr.optString(it).trim() } }.orEmpty()
        .filter { it.length in 5..240 }.take(3)
    if (note.length !in 20..900) null else note to next
}.getOrNull()

internal fun coachJson(c: CoachUi, basis: String): String =
    JSONObject().put("note", c.note).put("next", JSONArray(c.next)).put("model", c.model).put("at", c.at).put("basis", basis).toString()

internal fun coachFromJson(s: String?): Pair<CoachUi, String>? = s?.let {
    runCatching {
        val j = JSONObject(it)
        val arr = j.optJSONArray("next") ?: JSONArray()
        CoachUi(j.getString("note"), (0 until arr.length()).map { i -> arr.getString(i) }, j.optString("model"), j.optLong("at")) to j.optString("basis")
    }.getOrNull()
}
