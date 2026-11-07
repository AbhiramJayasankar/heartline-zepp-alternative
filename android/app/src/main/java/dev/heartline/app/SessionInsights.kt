package dev.heartline.app

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/** The best working set by estimated one-rep max (Epley, load × (1 + reps/30)), from sets of 12 reps or fewer. */
data class BestSet(val e1rmKg: Double, val loadKg: Double, val reps: Double)

/** The same exercise in an earlier session, under whatever name that log used. */
data class ExerciseHistory(val date: LocalDate, val name: String, val workingSets: Int, val volumeKg: Double, val best: BestSet?,
                           /** The session it came from, to open its recording. */
                           val session: LoggedSession? = null)

/**
 * One exercise of a logged session. [volumeKg] is load × reps over completed working sets, timed or not. [rises] are
 * the per-set heart-rate rises from the capture; [untimed] are completed rows with no times (so not on the charts) and
 * [skipped] the skipped ones. [group] is the capture's colour group, or -1 when no set of it is in the capture.
 */
data class ExerciseInsight(
    val exercise: String, val group: Int, val workingSets: Int, val warmUps: Int, val volumeKg: Double, val best: BestSet?,
    val peakHr: Int?, val rises: List<Int>, val prev: ExerciseHistory?, val untimed: List<LoggedSet>, val skipped: List<LoggedSet>,
) {
    val avgRise: Double? get() = rises.takeIf { it.isNotEmpty() }?.average()
}

/** A capture set with what the log adds: its row, the rest before it, and the lowest heart rate during that rest. */
data class SetInsight(val set: SetUi, val row: LoggedSet?, val restBeforeS: Double?, val floorHr: Int?) {
    /** Peak heart rate as the rise measures it: start plus rise. */
    val peakHr: Int? get() = set.startHr?.let { a -> set.hrRise?.let { a + it } } ?: set.peakHr
}

/** Working sets of one exercise at one load, each with the rest before it. */
data class RestReps(val exercise: String, val group: Int, val loadKg: Double, val sets: List<SetInsight>) {
    /** One line on whether rest lined up with reps, or null when it says nothing. */
    val verdict: String? get() {
        val r = sets.mapNotNull { s -> s.restBeforeS?.let { rest -> s.set.loggedReps?.let { rest to it } } }
        if (r.size < 2 || r.map { it.second }.distinct().size < 2) return null
        val longest = r.maxBy { it.first }; val shortest = r.minBy { it.first }
        val most = r.maxOf { it.second }; val fewest = r.minOf { it.second }
        return when {
            longest.second == most && r.count { it.second == most } == 1 ->
                "The longest rest came before the most reps: ${fmtReps(most)} after ${mmss(longest.first)}, against " +
                    r.filter { it !== longest }.joinToString(" and ") { "${fmtReps(it.second)} after ${mmss(it.first)}" } + "."
            shortest.second == fewest && r.count { it.second == fewest } == 1 ->
                "The shortest rest came before the fewest reps: ${fmtReps(fewest)} after ${mmss(shortest.first)}."
            else -> "Rest length didn't line up with reps here, so fatigue or effort mattered more than the rest."
        }
    }
}

data class Finding(val title: String, val text: String)

/**
 * How the watch's own set finding did against the log. [found] of [total] logged timed sets found; [found] of
 * [finds] of its finds were real; rep counts within 2 (and 1) of the log on [within2] ([within1]) of [counted].
 * [handFound]/[handTotal] leave out forearm and wrist work, which barely moves the forearm; null when there is none.
 */
data class DetectionScore(
    val found: Int, val total: Int, val finds: Int, val within1: Int, val within2: Int, val counted: Int,
    val handFound: Int?, val handTotal: Int?,
)

/** What a logged session adds to its capture: per-exercise totals against last time, set details, and findings. */
data class SessionInsights(
    val session: LoggedSession, val previous: LoggedSession?, val exercises: List<ExerciseInsight>, val sets: List<SetInsight>,
    val restReps: List<RestReps>, val findings: List<Finding>, val score: DetectionScore?, val volumeKg: Double,
)

internal fun fmtReps(v: Double): String = when {
    v % 1.0 == 0.0 -> v.toInt().toString()
    abs(v % 1.0 - 0.5) < 1e-6 -> "${v.toInt()}½"
    else -> "%.1f".format(v)
}
/** Seconds as m:ss. */
internal fun mmss(s: Double): String = s.roundToInt().let { "%d:%02d".format(it / 60, it % 60) }
private fun kg(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)
private fun thousands(v: Double): String = "%,d".format(v.roundToInt())
private val shortDay = DateTimeFormatter.ofPattern("d MMM")

private val FILLER = setOf("machine", "the", "a", "an", "on", "with", "of", "using")
/** Words that make a different exercise of the same movement: both names must agree on each. */
private val QUALIFIERS = setOf("reverse", "incline", "decline", "hammer", "single", "wide", "close", "front", "overhead")

private fun tokens(name: String): List<String> = name.lowercase().replace("-", "").split(Regex("[^a-z0-9]+"))
    .filter { it.isNotEmpty() && it !in FILLER }.map { if (it.length > 3 && it.endsWith("s") && !it.endsWith("ss")) it.dropLast(1) else it }

/**
 * Whether two log names are the same exercise: logging chats name things a little differently each time
 * ("Preacher curl machine", "Preacher curl (machine)"). Same words after dropping filler, or a Jaccard overlap of 0.6
 * or more with every qualifier such as "reverse" agreeing.
 */
fun sameExercise(a: String, b: String): Boolean {
    val ta = tokens(a); val tb = tokens(b)
    if (ta.isEmpty() || tb.isEmpty()) return false
    if (ta.joinToString("") == tb.joinToString("")) return true
    val sa = ta.toSet(); val sb = tb.toSet()
    if (QUALIFIERS.any { (it in sa) != (it in sb) }) return false
    return (sa intersect sb).size.toDouble() / (sa union sb).size >= 0.6
}

private fun LoggedSet.warm(): Boolean = setType.startsWith("warm")
private fun LoggedSet.working(): Boolean = completed && !cardio && !warm()

internal fun bestOf(rows: List<LoggedSet>): BestSet? = rows.filter { it.working() && (it.loadKg ?: 0.0) > 0 && (it.reps ?: 0.0) in 0.5..12.0 }
    .map { BestSet(it.loadKg!! * (1 + it.reps!! / 30), it.loadKg, it.reps) }.maxByOrNull { it.e1rmKg }
    ?.let { it.copy(e1rmKg = (it.e1rmKg * 10).roundToInt() / 10.0) }

internal fun volumeOf(rows: List<LoggedSet>): Double = rows.filter { it.working() }.sumOf { (it.loadKg ?: 0.0) * (it.reps ?: 0.0) }

/** The logged session with the most timed sets inside the capture, if any. */
fun sessionForCapture(log: List<LoggedSet>, start: Long, durationS: Int): LoggedSession? =
    sessionsOf(log).map { s -> s to s.sets.count { it.start != null && it.start >= start - 60 && it.start <= start + durationS } }
        .filter { it.second > 0 }.maxByOrNull { it.second }?.first

private fun LoggedSession.before(other: LoggedSession): Boolean {
    if (this == other) return false
    val a = start; val b = other.start
    return if (a != null && b != null) a < b else date < other.date
}

/**
 * The capture that recorded a logged session: the one overlapping its timed sets the longest.
 */
fun captureForSession(s: LoggedSession, captures: List<CaptureItemUi>): CaptureItemUi? {
    val a = s.start ?: return null; val b = s.end ?: return null
    return captures.map { c -> c to (minOf(b, c.start + c.durationS) - maxOf(a, c.start)) }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
}

/**
 * Builds the insights for capture [a] from the gym [log]. [previousRecovery] is the recovery curve of the capture of
 * the previous comparable session, when the caller has it; [skinTemp] the skin temperature range over the session.
 */
fun sessionInsights(
    a: CaptureAnalysisUi, log: List<LoggedSet>, previousRecovery: List<RecoveryPointUi> = emptyList(),
    skinTemp: Pair<Double, Double>? = null,
): SessionInsights? {
    if (a.logTotal == null) return null
    val session = sessionForCapture(log, a.start, a.durationS) ?: return null
    val earlier = sessionsOf(log).filter { it.before(session) }.sortedWith(compareByDescending<LoggedSession> { it.date }.thenByDescending { it.start ?: 0L })
    val names = session.sets.filter { !it.cardio }.map { it.exercise }.distinct()
    // The session to compare with: the latest earlier one sharing at least two exercises (one, for a one-exercise day).
    val need = minOf(2, names.size)
    val previous = earlier.firstOrNull { p -> names.count { n -> p.sets.any { it.working() && sameExercise(it.exercise, n) } } >= need.coerceAtLeast(1) }
    fun history(name: String): ExerciseHistory? = (listOfNotNull(previous) + earlier).firstNotNullOfOrNull { p ->
        val rows = p.sets.filter { it.working() && sameExercise(it.exercise, name) }
        rows.takeIf { it.isNotEmpty() }?.let { ExerciseHistory(p.date, it.first().exercise, it.size, volumeOf(it), bestOf(it), p) }
    }

    // Capture sets with their log rows, the rest before each and the lowest heart rate in that rest.
    val sorted = a.sets.sortedBy { it.start }
    val sets = sorted.mapIndexed { i, s ->
        val at = a.start + s.start.roundToInt()
        val row = s.exercise?.let { ex -> session.sets.filter { it.exercise == ex && it.start != null && abs(it.start - at) <= 20 }.minByOrNull { abs(it.start!! - at) } }
        val prevEnd = sorted.getOrNull(i - 1)?.end
        val lo = prevEnd ?: (s.start - 180).coerceAtLeast(0.0)
        val floor = a.hr.filter { it.t >= lo && it.t < s.start }.minOfOrNull { it.v }?.roundToInt()
        SetInsight(s, row, row?.restBeforeS ?: prevEnd?.let { s.start - it }, floor)
    }

    val exercises = (sorted.mapNotNull { it.exercise } + names).distinct().filter { n -> session.sets.any { it.exercise == n && !it.cardio } }.map { n ->
        val rows = session.sets.filter { it.exercise == n }
        val mine = sets.filter { it.set.exercise == n }
        ExerciseInsight(
            n, mine.firstOrNull()?.set?.group ?: -1, rows.count { it.working() }, rows.count { it.completed && it.warm() }, volumeOf(rows), bestOf(rows),
            mine.mapNotNull { it.peakHr }.maxOrNull(), mine.mapNotNull { it.set.hrRise }, history(n),
            rows.filter { it.completed && it.start == null }, rows.filter { !it.completed },
        )
    }

    val restReps = sets.filter { !it.set.warmUp && it.set.loadKg != null && it.set.loggedReps != null && it.restBeforeS != null }
        .groupBy { it.set.exercise to it.set.loadKg }.filter { it.value.size >= 2 && it.value.map { s -> s.set.loggedReps }.distinct().size >= 2 }
        .map { (k, v) -> RestReps(k.first ?: "", v.first().set.group, k.second!!, v) }
        // Groups where rest lined up with reps first, then by how much reps varied.
        .sortedWith(compareBy<RestReps> { it.verdict?.startsWith("Rest length") != false }.thenByDescending { g -> g.sets.maxOf { it.set.loggedReps!! } - g.sets.minOf { it.set.loggedReps!! } })
        .take(5)

    val score = a.logMatched?.let { found ->
        val counted = a.sets.filter { it.loggedReps != null && it.reps > 0 }
        val hand = a.sets.filter { it.loggedReps != null && it.exercise?.contains(Regex("forearm|wrist", RegexOption.IGNORE_CASE)) == true }
        val rest = a.sets.filter { it.loggedReps != null } - hand.toSet()
        DetectionScore(found, a.logTotal, found + a.unlogged.size,
            counted.count { abs(it.reps - it.loggedReps!!) <= 1 }, counted.count { abs(it.reps - it.loggedReps!!) <= 2 }, counted.size,
            if (hand.isEmpty()) null else rest.count { it.source != "log" }, if (hand.isEmpty()) null else rest.size)
    }

    return SessionInsights(session, previous, exercises, sets, restReps,
        findings(exercises, sets, a.recovery, previousRecovery, previous, session, skinTemp), score, volumeOf(session.sets))
}

private fun findings(
    ex: List<ExerciseInsight>, sets: List<SetInsight>, recovery: List<RecoveryPointUi>, prevRecovery: List<RecoveryPointUi>,
    previous: LoggedSession?, session: LoggedSession, skinTemp: Pair<Double, Double>?,
): List<Finding> {
    val out = ArrayList<Finding>()
    fun on(d: LocalDate) = "on ${d.format(shortDay)}"
    val counts = listOf("", "One", "Two", "Three", "Four", "Five", "Six")

    val bests = ex.filter { e -> e.best != null && e.prev?.best != null && e.best.e1rmKg > e.prev.best.e1rmKg + 0.05 }
    if (bests.isNotEmpty()) out += Finding(if (bests.size == 1) "A new best." else "${counts.getOrElse(bests.size) { "${bests.size}" }} new bests.",
        bests.joinToString(if (bests.size > 1) "\n" else "") { e ->
            val b = e.best!!; val p = e.prev!!.best!!
            "${e.exercise} ${kg(b.loadKg)} kg × ${fmtReps(b.reps)} (estimated one-rep max ${kg(b.e1rmKg)} kg, up ${((b.e1rmKg / p.e1rmKg - 1) * 100).roundToInt()}% " +
                "from ${kg(p.loadKg)} kg × ${fmtReps(p.reps)} ${on(e.prev.date)})."
        })

    ex.filter { e -> e.prev != null && e.prev.volumeKg > 0 && e.volumeKg < e.prev.volumeKg * 0.85 }.sortedBy { it.volumeKg / it.prev!!.volumeKg }.take(2).forEach { e ->
        val p = e.prev!!
        out += Finding("Less from ${e.exercise.lowercase()}.",
            "${thousands(e.volumeKg)} kg of volume over ${e.workingSets} sets, against ${thousands(p.volumeKg)} kg over ${p.workingSets} ${on(p.date)}" +
                (e.best?.let { b -> ", best set ${kg(b.loadKg)} kg × ${fmtReps(b.reps)}" } ?: "") + ".")
    }

    val costed = ex.filter { it.rises.size >= 2 }.sortedByDescending { it.avgRise }
    if (costed.size >= 2) {
        val top = costed.first(); val others = costed.drop(1).map { it.avgRise!! }
        if (top.avgRise!! >= 6 && top.avgRise!! >= others.max() * 1.4) out += Finding("${top.exercise} drove your heart rate.",
            "Each set raised it by about ${top.avgRise!!.roundToInt()} bpm" + (top.peakHr?.let { ", up to $it bpm" } ?: "") +
                ". The other exercises added ${others.min().roundToInt()} to ${others.max().roundToInt()} bpm a set.")
    }

    val now60 = recovery.minByOrNull { abs(it.t - 60) }?.takeIf { abs(it.t - 60) <= 10 }
    val then60 = prevRecovery.minByOrNull { abs(it.t - 60) }?.takeIf { abs(it.t - 60) <= 10 }
    if (now60 != null && then60 != null && previous != null) {
        val d = -now60.delta; val p = -then60.delta
        out += Finding(when { d > p + 2 -> "Recovery was deeper than ${on(previous.date)}."; d < p - 2 -> "Recovery was shallower than ${on(previous.date)}."; else -> "Recovery was about the same as ${on(previous.date)}." },
            "A minute after a set's peak your heart rate had dropped ${d.roundToInt()} bpm on average, against ${p.roundToInt()} bpm ${on(previous.date)}.")
    }

    val failed = session.sets.filter { it.completed && it.failure && !it.cardio }
    if (failed.isNotEmpty()) out += Finding(if (failed.size == 1) "One set went to failure." else "${counts.getOrElse(failed.size) { "${failed.size}" }} sets went to failure.",
        failed.groupBy { it.exercise }.entries.joinToString("\n") { (n, rows) ->
            "$n " + rows.joinToString(", ") { r -> "set ${r.setNumber} (${r.loadKg?.let { "${kg(it)} kg × " } ?: ""}${r.reps?.let(::fmtReps) ?: "?"}" +
                (r.restBeforeS?.let { ", after ${mmss(it)} rest" } ?: "") + ")" } + "."
        })

    val skipped = session.sets.filter { !it.completed }
    if (skipped.isNotEmpty()) out += Finding(if (skipped.size == 1) "One set was skipped." else "${skipped.size} sets were skipped.",
        skipped.joinToString("; ") { s -> "${s.exercise}${if (s.setNumber > 0) " set ${s.setNumber}" else ""}" + (s.notes.takeIf { it.isNotBlank() }?.let { ": ${it.trim().trimEnd('.')}" } ?: "") } + ".")

    if (skinTemp != null) out += Finding("Skin temperature held steady.", "Between ${"%.1f".format(skinTemp.first)} and ${"%.1f".format(skinTemp.second)} °C over the session.")
    return out
}
