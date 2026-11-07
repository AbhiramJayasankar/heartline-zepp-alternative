package dev.heartline.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.heartline.app.CaptureAnalysisUi
import dev.heartline.app.ExerciseInsight
import dev.heartline.app.LoggedSet
import dev.heartline.app.SessionInsights
import dev.heartline.app.SetInsight
import dev.heartline.app.fmtReps
import dev.heartline.ui.components.ChartLine
import dev.heartline.ui.components.ChartSpan
import dev.heartline.ui.components.InteractiveChart
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.SectionTitle
import dev.heartline.app.SeriesPoint
import dev.heartline.ui.components.clock
import dev.heartline.ui.components.groupColor
import dev.heartline.ui.theme.HL
import dev.heartline.ui.theme.Palette
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val shortDay = DateTimeFormatter.ofPattern("d MMM")
private fun kg(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)
internal fun exerciseColor(group: Int, c: Palette): Color = if (group < 0) c.inkSoft else groupColor(group, c)

/** The few things worth knowing about this session, from the log, the capture and the last comparable session. */
@Composable
internal fun Findings(ins: SessionInsights) {
    if (ins.findings.isEmpty()) return
    val c = HL.colors
    Gap(24); SectionTitle("What stood out"); Gap(6)
    ins.findings.forEach { f ->
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp).height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(c.pulse))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.title, style = HL.type.bodyStrong, color = c.ink)
                Text(f.text, style = HL.type.body, color = c.ink)
            }
        }
    }
}

/** Best estimated one-rep max and working volume, each against the last session with the same exercise. */
@Composable
internal fun ExerciseVersus(e: ExerciseInsight, onOpenPrev: (() -> Unit)? = null) {
    val c = HL.colors
    val p = e.prev
    Gap(8)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        e.best?.let { b ->
            Column(Modifier.weight(1f)) {
                Text("Best estimated 1RM", style = HL.type.label, color = c.inkSoft)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${kg(b.e1rmKg)} kg", style = HL.type.stat, color = c.ink)
                    p?.best?.let { pb ->
                        val d = b.e1rmKg - pb.e1rmKg
                        Spacer(Modifier.width(6.dp))
                        Text("${if (d >= 0) "+" else "−"}${kg(kotlin.math.abs(d))}", style = HL.type.label, color = if (d >= 0) c.ok else c.warn,
                            modifier = Modifier.padding(bottom = 3.dp))
                    }
                }
                MutedText("${kg(b.loadKg)} kg × ${fmtReps(b.reps)}" + (p?.best?.let { pb -> "; ${p.date.format(shortDay)}: ${kg(pb.loadKg)} kg × ${fmtReps(pb.reps)}" } ?: ""))
            }
        }
        if (e.volumeKg > 0) Column(Modifier.weight(1f)) {
            Text("Working volume", style = HL.type.label, color = c.inkSoft)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("%,d kg".format(e.volumeKg.roundToInt()), style = HL.type.stat, color = c.ink)
                p?.takeIf { it.volumeKg > 0 }?.let { pv ->
                    val d = (e.volumeKg - pv.volumeKg).roundToInt()
                    Spacer(Modifier.width(6.dp))
                    Text("${if (d >= 0) "+" else "−"}%,d".format(kotlin.math.abs(d)), style = HL.type.label, color = if (d >= 0) c.ok else c.warn,
                        modifier = Modifier.padding(bottom = 3.dp))
                }
            }
            MutedText("${e.workingSets} set${if (e.workingSets == 1) "" else "s"}" + (p?.let { "; ${it.workingSets} on ${it.date.format(shortDay)}" } ?: ""))
        }
    }
    if (p == null) MutedText("First time in your log, so nothing to compare with yet.")
    else if (!p.name.equals(e.exercise, ignoreCase = true)) MutedText("Compared with “${p.name}” on ${p.date.format(shortDay)}.")
    if (p != null && onOpenPrev != null) {
        // Opens the earlier session at this exercise; Back comes straight back here.
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${p.date.format(shortDay)}", onClick = onOpenPrev).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("See this exercise on ${p.date.format(DateTimeFormatter.ofPattern("EEE d MMM"))}", style = HL.type.label, color = c.pulse, modifier = Modifier.weight(1f))
            Text("›", style = HL.type.bodyStrong, color = c.pulse)
        }
    }
}

/** What the log says about a set beyond load and reps: rest before it, failure, reps in reserve, difficulty, notes. */
internal fun logDetail(s: SetInsight): String? {
    val r = s.row
    // Drop notes that only repeat a column ("Failure", "2 RIR", "7/10") or the logging chat's own bookkeeping.
    val note = r?.notes?.trim()
        ?.replace(Regex("[;,.]?\\s*(reported as [\\d.]+ reps; )?retained as provided\\.?", RegexOption.IGNORE_CASE), "")
        ?.let { if (r.failure) it.replace(Regex("[;,]?\\s*(reached )?failure\\.?$", RegexOption.IGNORE_CASE), "") else it }?.trim()
        ?.takeIf { n -> n.isNotEmpty() && !Regex("^(failure|warm-?up|\\d+(\\.\\d+)?\\s*rir|\\d+(\\.\\d+)?/10)$", RegexOption.IGNORE_CASE).matches(n) }
    return listOfNotNull(
        s.restBeforeS?.let { "after ${clock(it)} rest" },
        if (r?.failure == true) "to failure" else r?.rir?.let { "${fmtReps(it)} in reserve" },
        r?.difficulty?.let { "difficulty ${kg(it)}/10" },
        note,
    ).joinToString(", ").takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercase() }
}

/** Logged rows of an exercise that aren't on the charts: no times in the log, or skipped. */
@Composable
internal fun OffChartRows(rows: List<LoggedSet>) {
    val c = HL.colors
    rows.forEach { r ->
        Gap(10)
        Text(if (r.setType.startsWith("warm")) "Warm-up" else "Set ${r.setNumber}", style = HL.type.label, color = c.inkSoft)
        Text(when {
            !r.completed -> "Skipped"
            // Assisted: the weight moved, as on the other sets, with the assistance after it.
            r.assistKg != null && r.loadKg != null -> "${kg(r.loadKg)} kg × ${r.reps?.let(::fmtReps) ?: "?"} (assist ${kg(r.assistKg)})"
            else -> setText(r).removeSuffix(" to failure")
        }, style = HL.type.bodyStrong, color = if (r.completed) c.ink else c.inkSoft)
        if (r.completed && r.failure) Text("To failure", style = HL.type.small, color = c.ink)
        MutedText(if (r.completed) "No start or end time in your log, so it isn't on the charts, but it counts in volume."
            else r.notes.trim().ifEmpty { "No reps logged." })
    }
}

/** Reps against the rest before each set, for exercises done at one weight more than once. */
@Composable
internal fun RestRepsSection(ins: SessionInsights) {
    if (ins.restReps.isEmpty()) return
    val c = HL.colors
    Gap(24); SectionTitle("Rest and reps at the same weight"); Gap(4)
    MutedText("Working sets at one weight, with the rest you took before each.")
    ins.restReps.forEach { g ->
        val color = exerciseColor(g.group, c)
        val max = g.sets.maxOf { it.set.loggedReps!! }.coerceAtLeast(1.0)
        Gap(16)
        Text("${g.exercise}, ${kg(g.loadKg)} kg", style = HL.type.bodyStrong, color = c.ink)
        Gap(8)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            g.sets.forEach { s ->
                val reps = s.set.loggedReps!!
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(fmtReps(reps), style = HL.type.label, color = c.ink)
                    Gap(2)
                    Box(Modifier.width(36.dp).height((96 * reps / max).coerceAtLeast(3.0).dp).clip(RoundedCornerShape(3.dp)).background(color))
                    Gap(4)
                    Text(s.set.setLabel?.lowercase() ?: "", style = HL.type.small, color = c.inkSoft, textAlign = TextAlign.Center)
                    Text(s.restBeforeS?.let { "${clock(it)} rest" } ?: "", style = HL.type.small, color = c.inkSoft, textAlign = TextAlign.Center)
                }
            }
        }
        g.verdict?.let { Gap(6); MutedText(it) }
    }
}

/** Heart-rate rise per set for each exercise: average as a bar, single sets as dots. */
@Composable
internal fun HeartCost(ins: SessionInsights) {
    val rows = ins.exercises.filter { it.rises.isNotEmpty() }
    if (rows.size < 2) return
    val c = HL.colors
    Gap(24); SectionTitle("What each exercise cost your heart"); Gap(4)
    MutedText("Rise from the start of each set to its peak, up to 15 s after it ends. The bar is the average, dots are single sets.")
    Gap(10)
    val max = rows.maxOf { it.rises.max() }.coerceAtLeast(10).toFloat()
    val ink = c.ink.copy(alpha = 0.55f); val grid = c.line
    rows.forEach { e ->
        val color = exerciseColor(e.group, c)
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(e.exercise, style = HL.type.small, color = c.ink, maxLines = 2, modifier = Modifier.width(112.dp))
            Spacer(Modifier.width(8.dp))
            Canvas(Modifier.weight(1f).height(22.dp)) {
                val w = size.width
                for (v in 0..max.toInt() step 10) drawLine(grid, Offset(v / max * w, 0f), Offset(v / max * w, size.height), 1f)
                val avg = e.avgRise!!.toFloat().coerceAtLeast(0f)
                drawRoundRect(color, Offset(0f, size.height * 0.2f), Size((avg / max * w).coerceAtLeast(3f), size.height * 0.6f), CornerRadius(4f, 4f))
                e.rises.forEach { r -> drawCircle(ink, 3.dp.toPx(), Offset(r.coerceAtLeast(0) / max * w, size.height / 2)) }
            }
            Text("+${e.avgRise!!.roundToInt()}" + (e.peakHr?.let { ", $it" } ?: ""), style = HL.type.small, color = c.inkSoft, textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
        }
    }
    Gap(4)
    MutedText("Numbers on the right: average rise, then the highest heart rate in a set of it. Grid lines every 10 bpm.")
    val sorted = rows.sortedByDescending { it.avgRise }
    val low = rows.flatMap { it.rises }.let { all -> all.count { it <= 2 } to all.size }
    Gap(6)
    MutedText("${sorted.first().exercise} cost your heart the most, about ${sorted.first().avgRise!!.roundToInt()} bpm a set, and ${sorted.last().exercise.lowercase()} the least, about ${sorted.last().avgRise!!.roundToInt()} bpm." +
        (if (low.first > 0) " ${low.first} of ${low.second} sets added 2 bpm or less." else "") +
        " Small muscles on a machine ask little of the heart; the level between sets reflects the session as a whole.")
}

/** The lowest heart rate in the rest before each set, against time, with the sets shaded. */
@Composable
internal fun FloorSection(a: CaptureAnalysisUi, ins: SessionInsights) {
    val pts = ins.sets.filter { it.floorHr != null }
    if (pts.size < 4) return
    val c = HL.colors
    Gap(24); SectionTitle("The floor before each set"); Gap(4)
    MutedText("The lowest heart rate in the rest before each set. A rising floor means the session's work is adding up faster than you recover between sets.")
    Gap(10)
    PaperPanel {
        InteractiveChart(
            listOf(ChartLine(pts.map { SeriesPoint(it.set.start, it.floorHr!!.toDouble()) }, c.ink, "Lowest before the set", "bpm", width = 2.dp)),
            0.0, a.durationS.toDouble().coerceAtLeast(60.0), height = 170.dp,
            spans = ins.sets.map { s -> ChartSpan(s.set.start, s.set.end, exerciseColor(s.set.group, c).copy(alpha = 0.3f),
                listOfNotNull(s.set.exercise, s.set.setLabel?.lowercase()).joinToString(", ")) },
            xLabel = { clock(it) }, readoutX = { "${clock(it)} in" }, minSpan = 60.0,
            idleHint = "Touch to see the floor before a set.")
    }
    val f = pts.map { it.floorHr!! }
    val later = f.drop(f.size / 3)
    Gap(8)
    MutedText("From ${f.first()} bpm before the first set to between ${later.min()} and ${later.max()} for the last two-thirds of the session.")
}
