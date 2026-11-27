package dev.heartline.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.heartline.app.CaptureAnalysisUi
import dev.heartline.ui.components.ChartLine
import dev.heartline.ui.components.ChartSpan
import dev.heartline.ui.components.InteractiveChart
import dev.heartline.ui.components.LegendDot
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.Panel
import dev.heartline.ui.components.SectionTitle
import dev.heartline.ui.components.clock
import dev.heartline.ui.components.fmtHm
import dev.heartline.ui.components.fmtInt
import dev.heartline.ui.theme.HL
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import kotlin.math.roundToInt

// ---- Capture sections: exercises, the log against the watch, recovery, cardio, calories, pulse ----

@Composable
internal fun Exercises(a: CaptureAnalysisUi, ins: dev.heartline.app.SessionInsights?, energy: dev.heartline.app.CaptureEnergyUi?, anchor: (String) -> Modifier = { Modifier },
                      openPrev: (dev.heartline.app.ExerciseInsight) -> (() -> Unit)? = { null }) {
    val c = HL.colors
    val logged = a.logTotal != null
    Gap(24); SectionTitle("Exercises and sets"); Gap(4)
    MutedText(if (logged) "Sets, names and times from your gym log. Each block is one rep the watch counted: its width is how long the rep took, the solid part is the slower half, and its height is how far the wrist turned."
        else "Back-to-back sets that move the wrist the same way are grouped as one exercise. Each block is one rep: its width is how long the rep took, the solid part is the slower half, and its height is how far the wrist turned.")
    val groups = ArrayList<MutableList<dev.heartline.app.SetUi>>()
    a.sets.forEach { s -> if (groups.lastOrNull()?.last()?.group == s.group) groups.last().add(s) else groups.add(mutableListOf(s)) }
    val maxDur = a.sets.maxOfOrNull { s -> s.repDetail.sumOf { it.durationS } }?.coerceAtLeast(1.0) ?: 1.0
    var setNo = 0
    val lastRun = groups.indices.associateBy { groups[it].first().exercise }
    val shown = HashSet<String>()
    groups.forEachIndexed { gi, sets ->
        val info = ins?.exercises?.firstOrNull { it.exercise == sets.first().exercise }
        val color = dev.heartline.ui.components.groupColor(sets.first().group, c)
        val arcMax = sets.flatMap { s -> s.repDetail.map { minOf(it.arcDeg, 160.0) } }.maxOrNull() ?: 1.0
        Gap(18)
        Row(sets.first().exercise?.let(anchor) ?: Modifier, verticalAlignment = Alignment.CenterVertically) {
            dev.heartline.ui.components.Dot(color, 10.dp); Spacer(Modifier.width(8.dp))
            Text(sets.first().exercise ?: "Exercise ${exerciseLetter(gi)}", style = HL.type.section.copy(fontSize = 20.sp), color = c.ink)
        }
        MutedText(listOfNotNull(if (sets.size == 1) "1 set" else "${sets.size} sets",
            if (logged) null else "${sets.sumOf { it.reps }} reps",
            sets.mapNotNull { s -> s.startHr?.let { h -> s.hrRise?.let { h + it } } ?: s.peakHr }.maxOrNull()?.let { "peak $it bpm" },
            sets.mapNotNull { it.tempoS }.takeIf { it.isNotEmpty() }?.let { "about %.1f s per rep".format(it.average()) },
            sets.mapNotNull { s -> s.repDetail.map { it.arcDeg }.sorted().let { r -> r.getOrNull(r.size / 2) } }.takeIf { it.isNotEmpty() }?.let { "wrist turns about ${it.average().roundToInt()}°" },
            sets.first().activity).joinToString(", "))
        // Its calories over the whole session (every run of it), rests included.
        energy?.exercises?.firstOrNull { it.exercise == (sets.first().exercise ?: "Exercise ${sets.first().group}") }?.takeIf { lastRun[sets.first().exercise] == gi }?.let { en ->
            Text("About ${en.kcal.roundToInt()} kcal, ${en.inSets.roundToInt()} of it in the sets", style = HL.type.small, color = c.ink)
        }
        if (info != null && shown.add(info.exercise)) ExerciseVersus(info, openPrev(info))
        sets.forEach { s ->
            setNo++
            val rest = a.rests.firstOrNull { it.start >= s.end - 1 && it.start <= s.end + 5 }
            Gap(10)
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.width(if (logged) 108.dp else 70.dp)) {
                    Text(s.setLabel ?: "Set $setNo", style = HL.type.label, color = c.inkSoft)
                    Text(loadReps(s) ?: "${s.reps} reps", style = HL.type.bodyStrong, color = c.ink)
                }
                if (s.repDetail.isNotEmpty()) dev.heartline.ui.components.RepStrip(s.repDetail, maxDur, arcMax, color, Modifier.weight(1f))
                else Spacer(Modifier.weight(1f))
            }
            ins?.sets?.firstOrNull { it.set === s }?.let(::logDetail)?.let { Gap(2); Text(it, style = HL.type.small, color = c.ink) }
            MutedText(listOfNotNull(
                watchCount(s),
                "${clock(s.start)}, lasted ${clock(s.end - s.start)}",
                if (s.startHr != null && s.hrRise != null) "HR ${s.startHr} to ${s.startHr + s.hrRise} (${if (s.hrRise >= 0) "+" else ""}${s.hrRise})" else s.peakHr?.let { "peak $it bpm" },
                s.slowdownS?.takeIf { it >= 0.1 }?.let { "slowed %.2f s per rep".format(it) },
                if (s.partialLast) "last rep was short" else null,
                rest?.let { "then ${clock(it.end - it.start)} rest" + (it.drop60?.let { d -> if (d >= 0) ", HR down $d in 1 min" else ", HR up ${-d} in 1 min" } ?: "") },
            ).joinToString(", "))
        }
        if (info != null && lastRun[info.exercise] == gi) OffChartRows(info.untimed + info.skipped)
    }
    // Exercises in the log with no set in the recording: untimed or skipped.
    ins?.exercises?.filter { e -> a.sets.none { it.exercise == e.exercise } }?.forEach { e ->
        Gap(18)
        Row(verticalAlignment = Alignment.CenterVertically) {
            dev.heartline.ui.components.Dot(c.inkSoft, 10.dp); Spacer(Modifier.width(8.dp))
            Text(e.exercise, style = HL.type.section.copy(fontSize = 20.sp), color = c.ink)
        }
        if (e.volumeKg > 0) ExerciseVersus(e)
        OffChartRows(e.untimed + e.skipped)
    }
    if (ins != null) {
        Gap(12)
        MutedText("Estimated one-rep max uses Epley, load × (1 + reps/30), on working sets of 12 reps or fewer. Each exercise is compared with the last session in your log that had it, with names matched loosely across logs. Volume is load × reps over completed working sets, including ones with no times. HR is from a set's first reading to its peak up to 15 s after it ends.")
    }
}

private fun num(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')

/** "45 kg × 8" from the log, or null without one. */
internal fun loadReps(s: dev.heartline.app.SetUi): String? = s.loggedReps?.let { r ->
    (s.loadKg?.takeIf { it > 0 }?.let { "${num(it)} kg × " } ?: "") + num(r) + if (s.loadKg == null || s.loadKg == 0.0) " reps" else ""
}

/** What the watch made of a logged set. */
internal fun watchCount(s: dev.heartline.app.SetUi): String? = when {
    s.loggedReps == null -> null
    s.source == "log" && s.reps == 0 -> "the watch didn't see a rep rhythm here, so it couldn't count this set"
    s.source == "log" -> "missed by the watch on its own; it counted ${s.reps} reps in your logged time"
    kotlin.math.abs(s.reps - s.loggedReps) < 0.5 -> "watch counted ${s.reps}, same as your log"
    else -> "watch counted ${s.reps}"
}

/** How the gym log and the watch's own set finding line up for this capture. */
@Composable
internal fun LogAndWatch(a: CaptureAnalysisUi, score: dev.heartline.app.DetectionScore?) {
    val total = a.logTotal ?: return
    val found = a.logMatched ?: 0
    val counted = a.sets.filter { it.loggedReps != null && it.reps > 0 }
    val close = counted.count { kotlin.math.abs(it.reps - it.loggedReps!!) <= 2 }
    Gap(24); SectionTitle("Your log and the watch"); Gap(4)
    score?.let { sc ->
        Gap(8)
        StatGrid(listOfNotNull(
            Triple("Logged sets found", "${sc.found}/${sc.total}", null), Triple("Finds that were sets", "${sc.found}/${sc.finds}", null),
            if (sc.counted > 0) Triple("Reps within 2", "${sc.within2}/${sc.counted}", null) else null,
            if (sc.counted > 0) Triple("Reps within 1", "${sc.within1}/${sc.counted}", null) else null,
            if (sc.handTotal != null) Triple("Found, no forearm work", "${sc.handFound}/${sc.handTotal}", null) else null,
        ))
        Gap(10)
    }
    MutedText("Your gym log has $total timed sets in this recording, and they set the sets, names and rest times on this page. " +
        "The watch found $found of them from wrist motion alone" + (if (found < total) "; for the other ${total - found}, it had only your logged times." else ".") +
        (if (counted.isNotEmpty()) " Where it counted reps, it was within 2 of your log on $close of ${counted.size} sets." else ""))
    if (a.unlogged.isNotEmpty()) {
        Gap(6)
        MutedText("It also found ${a.unlogged.size} rep-like stretch${if (a.unlogged.size == 1) "" else "es"} that ${if (a.unlogged.size == 1) "isn't" else "aren't"} in your log: " +
            a.unlogged.joinToString { "${clock(it.start)} (${it.reps} reps, ${clock(it.end - it.start)})" } +
            ". If you didn't do a set then, it was other movement, and it is left out of rests and recovery.")
    }
}

internal fun exerciseLetter(i: Int): String = if (i < 26) ('A' + i).toString() else "${i + 1}"

/** A name short enough for the chart's exercise lane: brackets and "machine"/"cable" dropped, first two words. */
internal fun shortExercise(name: String): String = name.replace(Regex("\\s*\\(.*?\\)"), "")
    .replace(Regex("(?i)\\b(machine|cable|barbell|dumbbell|seated)\\b"), "").trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    .take(2).joinToString(" ").replaceFirstChar { it.uppercase() }.ifBlank { name }

@Composable
internal fun Recovery(a: CaptureAnalysisUi, medianRest: Double?, compare: List<dev.heartline.app.RecoveryPointUi> = emptyList(), compareDate: LocalDate? = null) {
    val m = a.recovery.minBy { it.delta }
    val at60 = a.recovery.minByOrNull { kotlin.math.abs(it.t - 60) }
    Gap(24); SectionTitle("Heart rate between sets"); Gap(4)
    MutedText("Average change from each set's peak while you rested, across ${a.recovery.first().n} rests.")
    Gap(10)
    val label = compareDate?.format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))
    PaperPanel {
        dev.heartline.ui.components.RecoveryChart(a.recovery, medianRest, compare = if (label != null) compare else emptyList(), compareLabel = label)
        if (label != null && compare.size >= 2) {
            Gap(6)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) { LegendDot(HL.colors.pulse, "This session"); LegendDot(HL.colors.inkSoft, label) }
        }
    }
    Gap(10)
    MutedText(buildString {
        at60?.let { append("Heart rate fell about ${(-it.delta).roundToInt()} bpm in the first minute") }
        val then60 = compare.minByOrNull { kotlin.math.abs(it.t - 60) }?.takeIf { label != null && kotlin.math.abs(it.t - 60) <= 10 }
        if (at60 != null) append(then60?.let { ", against ${(-it.delta).roundToInt()} bpm on $label. " } ?: ". ")
        append("It stopped falling around ${m.t} s")
        medianRest?.let { append(if (it > m.t + 30) ", well before your median rest of ${clock(it)} ended" else ", close to your median rest of ${clock(it)}") }
        append(". Heart-rate recovery is not muscle recovery, so longer rests before hard sets can still pay off.")
    })
}

/**
 * What a cardio block was set to and how far it went. Each figure comes from the most accurate source that has it: the
 * gym log (what the treadmill was set to), then the raw sensors, then the watch's workout. [src] names each figure's source.
 */
internal data class CardioFacts(val speedKmh: Double?, val inclinePct: Double?, val durationS: Double, val steps: Int?, val km: Double?, val kcal: Double?,
    val src: Map<String, String>, val displayKcal: Double? = null, val watchKcal: Double? = null)

internal fun cardioFacts(a: CaptureAnalysisUi, k: dev.heartline.app.CardioUi, log: List<dev.heartline.app.LoggedSet>, energy: dev.heartline.app.CaptureEnergyUi?): CardioFacts {
    val row = dev.heartline.app.cardioLogRow(a, k, log)
    val w = a.activities.filter { it.start < k.end && it.end > k.start }.maxByOrNull { minOf(it.end, k.end) - maxOf(it.start, k.start) }
    val src = LinkedHashMap<String, String>()
    fun <T> pick(name: String, vararg options: Pair<String, T?>): T? = options.firstOrNull { it.second != null }?.also { src[name] = it.first }?.second
    val sensorS = k.end - k.start
    val durationS = pick("time", "gym log" to row?.durationS?.takeIf { it > 0 }, "sensors" to sensorS)!!
    // The sensors' cadence over the block they saw walking; the watch's count only without it.
    val steps = pick("steps", "gym log" to row?.steps, "sensors" to k.cadenceSpm?.let { (it * sensorS / 60.0).roundToInt() }, "watch" to w?.steps?.takeIf { it > 0 })
    val speed = pick("speed", "gym log" to row?.speedKmh, "watch" to k.speedKmh)
    val incline = pick("incline", "gym log" to row?.inclinePct)
    val km = pick("distance", "gym log" to (row?.distanceKm ?: row?.speedKmh?.let { v -> v * durationS / 3600.0 }), "watch" to k.distanceM?.takeIf { it > 0 }?.div(1000.0))
    // Calories: the treadmill equation from the logged speed and incline (the display's own figure is shown beside it,
    // but treadmill displays guess at your weight), else the heart-rate estimate; the watch's own figure only without those.
    val own = w?.takeIf { it.start >= k.start - 120 && it.end <= k.end + 120 }?.calories?.toDouble()
    val byEquation = energy?.treadmill?.any { it.start < k.end && it.end > k.start } == true
    val kcal = pick("calories", (if (byEquation) "treadmill equation" else "sensors") to energy?.between(k.start, k.end), "watch" to own)
    return CardioFacts(speed, incline, durationS, steps, km, kcal, src, row?.caloriesKcal, own)
}

@Composable
internal fun CardioBlock(
    a: CaptureAnalysisUi, k: dev.heartline.app.CardioUi, log: List<dev.heartline.app.LoggedSet>,
    prev: Pair<CaptureAnalysisUi, dev.heartline.app.CardioUi>?, anchor: Modifier, onOpenPrev: (() -> Unit)?,
    energyOf: (CaptureAnalysisUi) -> dev.heartline.app.CaptureEnergyUi?,
) {
    val f = remember(a, k, log, energyOf) { cardioFacts(a, k, log, energyOf(a)) }
    Gap(24); SectionTitle(k.name ?: k.kind.replaceFirstChar { it.uppercase() }, modifier = anchor); Gap(4)
    MutedText("${clock(k.start)} to ${clock(k.end)} into the capture, ${clock(k.end - k.start)} of steady ${k.kind}" + (k.name?.let { ", named by the watch" } ?: "") +
        listOfNotNull(f.speedKmh?.let { "%.1f km/h".format(it) }, f.inclinePct?.let { "${num1(it)}% incline" }).takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = ", at ").orEmpty())
    Gap(10)
    PaperPanel { dev.heartline.ui.components.HrWindowChart(a.hr, (k.start - 120).coerceAtLeast(0.0), (k.end + 240).coerceAtMost(a.durationS.toDouble()), k.start, k.end) }
    Gap(14)
    StatGrid(listOf(
        Triple("Steady", fmtInt(k.steadyHr), "bpm"), Triple("Peak", fmtInt(k.peakHr), "bpm"), Triple("Average", fmtInt(k.avgHr), "bpm"),
        Triple("Rise time", fmtInt(k.riseTauS), "s"), Triple("Drift", k.driftPerMin?.let { "%+.1f".format(it) } ?: "–", "bpm/min"), Triple("Cadence", fmtInt(k.cadenceSpm), "spm"),
        Triple("Drop 1 min", fmtInt(k.drop60), "bpm"), Triple("Drop 2 min", fmtInt(k.drop120), "bpm"), Triple("Recovery", fmtInt(k.recoveryTauS), "s"),
        Triple("Time", clock(f.durationS), null), Triple("Steps", f.steps?.let { "%,d".format(it) } ?: "–", null),
        Triple("Distance", f.km?.let { "%.2f".format(it) } ?: "–", "km"), Triple("Speed", f.speedKmh?.let { "%.1f".format(it) } ?: "–", "km/h"),
    ) + listOfNotNull(f.inclinePct?.let { Triple("Incline", num1(it), "%") }, f.kcal?.let { Triple("Calories", it.roundToInt().toString(), "kcal") }))
    Gap(10)
    val notes = listOfNotNull(
        k.riseTauS?.let { "Rise time is how quickly heart rate settled towards its plateau (a time constant). Fitter hearts settle in under a minute; $it s is where regular cardio shows up first." },
        // The 12 bpm check means something only after real effort with a rest after it, not when a set started straight away.
        k.drop60?.let { d ->
            val nextSet = a.sets.firstOrNull { it.start >= k.end - 5 }?.start?.minus(k.end)
            val effort = k.peakHr != null && a.zones.firstOrNull { it.zone == 2 }?.let { k.peakHr >= it.lowBpm } != false
            val moved = if (d >= 0) "fell $d bpm" else "rose ${-d} bpm"
            when {
                nextSet != null && nextSet < 90 -> "A set started ${nextSet.roundToInt()} s after this, so the minute after it isn't a recovery measure."
                !effort -> "Heart rate $moved in the first minute after; the effort was too light for that to say much about recovery."
                d > 12 -> "Heart rate fell $d bpm in the first minute after stopping, comfortably above the 12 bpm warning level."
                else -> "Heart rate $moved in the first minute after stopping; under 12 bpm after a hard effort is worth keeping an eye on."
            }
        },
        // Which source each figure came from, grouped: "From the gym log: time, speed, incline. From the sensors: ..."
        f.src.entries.groupBy({ it.value }, { it.key }).entries.joinToString(" ") { (from, what) ->
            "From ${when (from) { "watch" -> "the watch's workout"; "treadmill equation" -> "the logged speed, incline and your weight (ACSM walking equation)"; else -> "the $from" }}: ${what.joinToString(", ")}" + (if (from == "sensors" && "steps" in what) " (cadence times the time walking)" else "") + "."
        }.takeIf { it.isNotEmpty() }?.let { "$it Heart rate and cadence are always the sensors'." },
    )
    notes.forEach { MutedText(it); Gap(4) }
    if (f.kcal != null && (f.displayKcal != null || f.watchKcal != null)) {
        MutedText("Calories: " + listOfNotNull("${f.kcal.roundToInt()} kcal here", f.displayKcal?.let { "${it.roundToInt()} on the treadmill's display" },
            f.watchKcal?.let { "${it.roundToInt()} from the watch" }).joinToString(", ") +
            (if (f.src["calories"] == "treadmill equation") ". The equation works from the belt's speed and slope and your own weight; a treadmill display assumes a weight unless you enter one, and watches estimate from heart rate." else "."))
        Gap(4)
    }
    prev?.let { (pa, pk) -> CardioCompare(a, k, f, pa, pk, remember(pa, pk, log, energyOf) { cardioFacts(pa, pk, log, energyOf(pa)) }, onOpenPrev) }
}

/** This block against the same block last time: the settings, then what the heart did at them. */
@Composable
internal fun CardioCompare(
    a: CaptureAnalysisUi, k: dev.heartline.app.CardioUi, f: CardioFacts,
    pa: CaptureAnalysisUi, pk: dev.heartline.app.CardioUi, pf: CardioFacts, onOpen: (() -> Unit)?,
) {
    val c = HL.colors
    val zone = java.time.ZoneId.systemDefault()
    val prevDay = dev.heartline.app.sessionDate(pa.start, zone)
    val name = (k.name ?: k.kind).lowercase()
    Gap(16)
    Text("Against ${dev.heartline.ui.components.shortDate(prevDay)}", style = HL.type.bodyStrong, color = c.ink)
    Gap(6)
    Panel(padding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
        CompareRow("", "This session", dev.heartline.ui.components.shortDate(prevDay))
        val kmh: (Double?) -> String? = { it?.let { v -> "%.1f km/h".format(v) } }
        val pct: (Double?) -> String? = { it?.let { v -> "${num1(v)}%" } }
        val bpm: (Int?) -> String? = { it?.let { v -> "$v bpm" } }
        val sec: (Int?) -> String? = { it?.let { v -> "$v s" } }
        listOf(
            Triple("Speed", kmh(f.speedKmh), kmh(pf.speedKmh)),
            Triple("Incline", pct(f.inclinePct), pct(pf.inclinePct)),
            Triple("Time", clock(f.durationS), clock(pf.durationS)),
            Triple("Steps", f.steps?.let { "%,d".format(it) }, pf.steps?.let { "%,d".format(it) }),
            Triple("Distance", f.km?.let { "%.2f km".format(it) }, pf.km?.let { "%.2f km".format(it) }),
            Triple("Calories", f.kcal?.let { "${it.roundToInt()} kcal" }, pf.kcal?.let { "${it.roundToInt()} kcal" }),
            Triple("Cadence", k.cadenceSpm?.let { "$it spm" }, pk.cadenceSpm?.let { "$it spm" }),
            Triple("Steady", bpm(k.steadyHr), bpm(pk.steadyHr)),
            Triple("Peak", bpm(k.peakHr), bpm(pk.peakHr)),
            Triple("Rise time", sec(k.riseTauS), sec(pk.riseTauS)),
            Triple("Drop 1 min", bpm(k.drop60), bpm(pk.drop60)),
            Triple("Drop 2 min", bpm(k.drop120), bpm(pk.drop120)),
            Triple("Started", "${clock(k.start)} in", "${clock(pk.start)} in"),
        ).filter { it.second != null || it.third != null }.forEach { (l, n, t) -> CompareRow(l, n ?: "–", t ?: "–") }
    }
    Gap(8)
    MutedText(cardioVerdict(k, f, pk, pf, name))
    onOpen?.let { open -> Gap(4); NavRow("Open ${dev.heartline.ui.components.shortDate(prevDay)}'s $name", "${pa.label}, ${dateTimeOf(pa.start)}", open) }
}

/** One or two sentences: same settings or not, then the steady heart rate, how fast it got there, and the drop after. */
internal fun cardioVerdict(k: dev.heartline.app.CardioUi, f: CardioFacts, pk: dev.heartline.app.CardioUi, pf: CardioFacts, name: String): String {
    val same = f.speedKmh != null && pf.speedKmh != null && kotlin.math.abs(f.speedKmh - pf.speedKmh) < 0.15 &&
        (f.inclinePct == null || pf.inclinePct == null || kotlin.math.abs(f.inclinePct - pf.inclinePct) < 0.6)
    val harder = f.speedKmh != null && pf.speedKmh != null && !same &&
        f.speedKmh * (1 + (f.inclinePct ?: 0.0) / 10) > pf.speedKmh * (1 + (pf.inclinePct ?: 0.0) / 10)
    val out = ArrayList<String>()
    val steady = if (k.steadyHr != null && pk.steadyHr != null) k.steadyHr - pk.steadyHr else null
    // 3.8 against 3.7 km/h is the same work within what a treadmill dial means, but say so.
    val settings = if (same && f.speedKmh != pf.speedKmh) "Almost the same settings as last time (%.1f against %.1f km/h)".format(f.speedKmh, pf.speedKmh) else "Same settings as last time"
    when {
        steady == null -> out += "No steady heart rate for one of the two, so they can't be compared directly."
        same -> out += when {
            steady <= -3 -> "$settings, and your heart settled ${-steady} bpm lower: the same work cost less."
            steady >= 3 -> "$settings, but your heart settled $steady bpm higher. Sleep, heat, caffeine or the lifting before it can all do that."
            else -> "$settings, and much the same steady heart rate (${"%+d".format(steady)} bpm): no change yet."
        }
        f.speedKmh == null || pf.speedKmh == null -> out += "Steady heart rate ${"%+d".format(steady)} bpm against last time; log speed and incline to tell whether the work was the same."
        harder && steady <= 2 -> out += "A harder setting than last time at a steady heart rate ${if (steady <= 0) "no higher" else "only $steady bpm higher"}: that's progress."
        harder -> out += "A harder setting than last time, and heart rate settled $steady bpm higher, as you'd expect."
        steady >= 0 -> out += "An easier setting than last time, yet heart rate settled ${if (steady == 0) "at the same level" else "$steady bpm higher"}."
        else -> out += "An easier setting than last time, and heart rate settled ${-steady} bpm lower, as you'd expect."
    }
    if (k.riseTauS != null && pk.riseTauS != null && kotlin.math.abs(k.riseTauS - pk.riseTauS) >= 10)
        out += "It reached its plateau ${if (k.riseTauS < pk.riseTauS) "faster" else "slower"} (${k.riseTauS} s against ${pk.riseTauS} s)."
    if (kotlin.math.abs(k.start - pk.start) >= 15 * 60)
        out += "This $name came ${if (k.start > pk.start) "later" else "earlier"} in the session (${clock(k.start)} in, against ${clock(pk.start)}), so the lifting before it differs."
    return out.joinToString(" ")
}

internal fun num1(v: Double): String = if (kotlin.math.abs(v - v.roundToInt()) < 0.05) v.roundToInt().toString() else "%.1f".format(v)

/**
 * Where the session's calories went: a running total across the session, each exercise (its sets and the rests after
 * them), cardio and the rest, and how the estimate was put together from heart rate, the treadmill and the watch.
 */
@Composable
internal fun CaloriesSection(a: CaptureAnalysisUi, e: dev.heartline.app.CaptureEnergyUi, onFoot: Boolean,
                            gait: dev.heartline.analytics.GaitSummary?, ride: dev.heartline.analytics.RideSummary?) {
    val c = HL.colors
    // On foot (riding included) the gym's lifting and cardio blocks mean nothing, even with no runs or walks left.
    val feet = gait.takeIf { onFoot }
        // On foot: what the running and the walking burned, not the gym's lifting and cardio blocks.
    val lifting = if (onFoot) 0.0 else e.exercises.sumOf { it.kcal }
    val cardio = if (onFoot) 0.0 else a.cardio.filter { it.name != null || it.end - it.start >= 300 }.sumOf { e.between(it.start, it.end) }
    val ran = feet?.runs?.sumOf { e.between(it.start.toDouble(), it.end.toDouble()) } ?: 0.0
    val walked = feet?.walks?.sumOf { e.between(it.start.toDouble(), it.end.toDouble()) } ?: 0.0
    val rode = ride?.takeIf { onFoot }?.bouts?.filter { it.riding }?.sumOf { e.between(it.start.toDouble(), it.end.toDouble()) } ?: 0.0
    Gap(24); SectionTitle("Calories"); Gap(4)
    MutedText("About ${e.total.roundToInt()} kcal over ${fmtHm(a.durationS / 60)}, everything your body burned, resting share included. Touch the chart above for the running total at any moment.")
    Gap(12)
    StatGrid(listOfNotNull(
        Triple("Total", e.total.roundToInt().toString(), "kcal"),
        Triple("Lifting", lifting.roundToInt().toString(), "kcal").takeIf { lifting > 0 },
        Triple("Cardio", cardio.roundToInt().toString(), "kcal").takeIf { cardio > 0 },
        Triple("Running", ran.roundToInt().toString(), "kcal").takeIf { ran > 0 },
        Triple("Walking", walked.roundToInt().toString(), "kcal").takeIf { walked > 0 },
        Triple("Riding", rode.roundToInt().toString(), "kcal").takeIf { rode > 0 },
        Triple(if (onFoot) "Standing" else "Other", (e.total - lifting - cardio - ran - walked - rode).coerceAtLeast(0.0).roundToInt().toString(), "kcal"),
        Triple("Per hour", (e.total * 3600 / a.durationS.coerceAtLeast(60)).roundToInt().toString(), "kcal"),
    ))
    Gap(14)
    Panel(padding = PaddingValues(12.dp)) {
        Text(if (onFoot) "Running total" else "Running total, sets shaded", style = HL.type.label, color = c.inkSoft)
        Gap(6)
        InteractiveChart(listOf(ChartLine(e.cumulative + dev.heartline.app.SeriesPoint(a.durationS.toDouble(), e.total), c.warn, "So far", "kcal", width = 2.dp)),
            0.0, a.durationS.toDouble().coerceAtLeast(60.0), height = 130.dp,
            spans = if (onFoot) emptyList() else a.sets.map { s -> ChartSpan(s.start, s.end, dev.heartline.ui.components.groupColor(s.group, c).copy(alpha = 0.22f), s.exercise) } +
                a.cardio.filter { it.name != null }.map { k -> ChartSpan(k.start, k.end, c.warn.copy(alpha = 0.12f), k.name) },
            xLabel = { clock(it) }, readoutX = { "${clock(it)} in" }, minSpan = 60.0, idleHint = "Touch for the total so far.")
    }
    if (e.exercises.isNotEmpty() && !onFoot) {
        Gap(16)
        Text("By exercise", style = HL.type.bodyStrong, color = c.ink)
        MutedText("Each bar is an exercise's sets and the rests after them; the solid part is the sets alone.")
        Gap(8)
        val top = e.exercises.maxOf { it.kcal }.coerceAtLeast(1.0)
        e.exercises.sortedByDescending { it.kcal }.forEach { x ->
            val color = dev.heartline.ui.components.groupColor(x.group, c)
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(x.exercise, style = HL.type.body, color = c.ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(0.42f).padding(end = 8.dp))
                androidx.compose.foundation.layout.Box(Modifier.weight(0.42f).height(12.dp)) {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth((x.kcal / top).toFloat()).height(12.dp).clip(RoundedCornerShape(3.dp)).background(color.copy(alpha = 0.3f)))
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth((x.inSets / top).toFloat()).height(12.dp).clip(RoundedCornerShape(3.dp)).background(color))
                }
                Text("${x.kcal.roundToInt()}", style = HL.type.bodyStrong, color = c.ink, textAlign = TextAlign.End, modifier = Modifier.weight(0.16f))
            }
        }
    }
    Gap(12)
    val how = buildList {
        add("Heart rate alone gives ${e.heartRateOnly.roundToInt()} kcal: the share of your heart-rate reserve taken as the share of your oxygen reserve, " +
            "with VO2max ${e.vo2max.roundToInt()} (${if (e.vo2FromFitness) "from your walks and runs" else "estimated from resting and maximum heart rate"}) and your weight.")
        e.treadmill.forEach { t ->
            add("The treadmill at %.1f km/h and %s%% incline: %d kcal from the ACSM walking equation".format(t.speedKmh, num1(t.inclinePct), t.kcal.roundToInt()) +
                (t.display?.let { ", against ${it.roundToInt()} on its display" } ?: "") + ". The belt's work is measured directly, so this replaces heart rate there.")
        }
        e.watchSpans.forEach { w ->
            add(if (w.blended) "The watch's ${w.type.lowercase()} workout says ${w.watch.roundToInt()} kcal, heart rate here ${w.heartRate.roundToInt()}; this page meets them halfway at ${w.used.roundToInt()}."
                else "The watch's ${w.type.lowercase()} workout says ${w.watch.roundToInt()} kcal; the treadmill equation is used there instead.")
        }
    }
    how.forEach { MutedText(it); Gap(4) }
}

@Composable
internal fun PulseSection(p: dev.heartline.app.PulseUi) {
    val c = HL.colors
    val valid = p.windows.filter { it.status != "unreliable" }
    // Under a minute of clean signal is too little for a verdict, whatever the beats looked like.
    val enough = p.usableS >= 60
    val (title, color) = when {
        !enough || p.status == "insufficient_data" -> "Too little still time" to c.inkSoft
        p.status == "regular" -> "Regular" to c.ok; p.status == "irregular" -> "Some irregular beats" to c.warn
        else -> "Irregular pattern" to c.pulse
    }
    Gap(24); SectionTitle("Pulse regularity"); Gap(4)
    MutedText("Individual beats from the raw optical signal, whenever your wrist was still. A screening look, not an ECG.")
    Gap(4)
    MutedText("Why only still moments: when the arm moves, pressure under the strap swamps the pulse (it falls from about a fifth of the signal to about a twentieth). Cancelling it with the motion sensors was tested on a real session and could not recover trustworthy beats, so moving stretches are left out rather than guessed. Heart rate itself still works while moving.")
    Gap(10)
    Text(title, style = HL.type.score.copy(fontSize = 34.sp, lineHeight = 36.sp), color = color)
    val noisy = p.windows.size - valid.size
    MutedText("From ${clock(p.usableS.toDouble())} of still wrist in ${valid.size} stretch${if (valid.size == 1) "" else "es"}" +
        (if (noisy > 0) "; $noisy more ${if (noisy == 1) "was" else "were"} too noisy to use." else ".") +
        (if (!enough && valid.isNotEmpty()) " What there was looked ${if (p.status == "regular") "regular" else "uneven"}, but a verdict needs a minute or more; sit still for a few minutes during a capture to get one." else ""))
    if (p.excerpt.size > 1) {
        Gap(12)
        PaperPanel {
            Text("Filtered pulse at ${clock(p.excerptStart)}, each beat marked", style = HL.type.label, color = c.inkSoft)
            Gap(6)
            dev.heartline.ui.components.PulseWave(p.excerpt, p.excerptFs, p.excerptBeats)
        }
    }
    if (p.pairs.size >= 20) {
        Gap(12)
        PaperPanel {
            Text("Each beat gap against the next (ms)", style = HL.type.label, color = c.inkSoft)
            Gap(6)
            dev.heartline.ui.components.PoincarePlot(p.pairs)
            Gap(4)
            MutedText("A regular rhythm hugs the diagonal. An irregular one, such as atrial fibrillation, scatters into a wide fan.")
        }
    }
    Gap(14)
    StatGrid(listOf(
        Triple("Irregularity", p.nrmssd?.let { "%.3f".format(it) } ?: "–", null), Triple("Entropy", p.entropy?.let { "%.2f".format(it) } ?: "–", null),
        Triple("Early beats", p.ectopic.toString(), null),
    ))
    Gap(6)
    MutedText("Irregularity is beat-to-beat variation relative to the average gap (nRMSSD). Atrial fibrillation usually shows above 0.10 together with high entropy.")
    if (p.windows.isNotEmpty()) {
        Gap(12)
        p.windows.sortedBy { it.start }.forEach { w ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${clock(w.start)}–${clock(w.end)}", style = HL.type.body, color = c.ink, modifier = Modifier.weight(1.3f))
                Text("${w.beats} beats", style = HL.type.small, color = c.inkSoft, modifier = Modifier.weight(1f))
                Text("${w.hrPpg} / ${fmtInt(w.hrWatch)}", style = HL.type.small, color = c.inkSoft, modifier = Modifier.weight(1f))
                Text(if (w.status == "unreliable") "noisy" else "%.3f".format(w.nrmssd), style = HL.type.label,
                    color = when (w.status) { "regular" -> c.ok; "unreliable" -> c.inkSoft; "possible_af" -> c.pulse; else -> c.warn }, textAlign = TextAlign.End, modifier = Modifier.weight(0.8f))
            }
            HorizontalDivider(color = c.line)
        }
        Gap(4)
        MutedText("Columns: still stretch, beats found, heart rate from beats / from the watch, irregularity.")
    }
    p.notes.forEach { Gap(4); MutedText(it) }
}
