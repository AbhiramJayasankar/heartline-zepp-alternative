package dev.heartline.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.heartline.analytics.GaitBout
import dev.heartline.analytics.GaitSummary
import dev.heartline.ui.components.LegendDot
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.SectionTitle
import dev.heartline.ui.components.clock
import dev.heartline.ui.theme.HL
import kotlin.math.abs

/** The few plain sentences that sum up a run-walk session, in the order a runner would ask. */
internal fun gaitSentences(g: GaitSummary): List<String> = buildList {
    val runs = g.runs; val walks = g.walks
    when {
        runs.isEmpty() -> add("All walking: ${clock(g.walkS.toDouble())}" + (g.walkCadence?.let { " at about $it steps a minute" } ?: "") +
            (g.walkPaceSPerKm?.let { ", ${pace(it)} /km" } ?: "") + ".")
        g.intervals -> {
            // Medians: a long walk home or one long run shouldn't set the typical interval.
            val between = g.bouts.zipWithNext().filter { (a, b) -> a.running && !b.running && b != g.bouts.last() }.map { it.second.seconds }.sorted()
            val each = runs.map { it.seconds }.sorted()
            add("You ran ${runs.size} times, ${clock(g.runS.toDouble())} in all and about ${clock(each[each.size / 2].toDouble())} each" +
                (between.takeIf { it.isNotEmpty() }?.let { " with walks of about ${clock(it[it.size / 2].toDouble())} between" } ?: "") + ".")
        }
        else -> add("Running ${clock(g.runS.toDouble())}" + (if (g.walkS >= 60) " and walking ${clock(g.walkS.toDouble())}" else "") + ".")
    }
    if (runs.isNotEmpty()) {
        val p = listOfNotNull(g.runPaceSPerKm?.let { "running ${pace(it)} /km" }, g.walkPaceSPerKm?.takeIf { walks.isNotEmpty() }?.let { "walking ${pace(it)} /km" })
        if (p.isNotEmpty()) add("Pace " + p.joinToString(", ") + ".")
        g.runCadence?.let { rc -> add("Cadence $rc steps a minute running" + (g.walkCadence?.takeIf { walks.isNotEmpty() }?.let { ", $it walking" } ?: "") + ".") }
        val hottest = runs.filter { it.peakHr != null }.maxByOrNull { it.peakHr!! }
        if (hottest != null && runs.size >= 2) add("Heart rate peaked at ${hottest.peakHr} bpm on run ${runs.indexOf(hottest) + 1}.")
    }
    g.recovery60?.let { r ->
        add("It fell about $r bpm in the first minute of walking after a run" + (if (g.recoveries > 1) " (${g.recoveries} walks)" else "") + ".")
    }
    g.driftBpm?.let { d ->
        val dc = g.driftCadence
        when {
            d >= 5 && (dc == null || dc < 4) -> add("Running heart rate was $d bpm higher in the last third of your running than the first, at the same cadence: cardiac drift, from heat, fluid loss or tiring.")
            d >= 5 -> add("Running heart rate was $d bpm higher in the last third than the first, but cadence was up $dc too, so part of that is running faster.")
            d <= -5 -> add("Running heart rate was ${abs(d)} bpm lower in the last third than the first" + (if (dc != null && dc <= -4) ", with cadence down $dc." else "."))
            else -> add("Running heart rate held steady from the first third to the last (within $d bpm).")
        }
    }
}

/**
 * Running and walking for a session: the numbers, the sentences, and one row per run with the walk after it. The bars
 * share one time scale, so long and short intervals read at a glance. [distanceM] is the watch's distance when only it
 * knows (a capture), shown when the bouts carry none of their own.
 */
@Composable
internal fun GaitSection(summary: GaitSummary, distanceM: Int? = null) {
    val c = HL.colors
    // The bouts' own distance comes from the watch's per-second pace, which runs a few percent short of its GPS total
    // (3.73 against 3.89 km on 14 Nov). With the total known, the bouts are scaled to it so the page agrees with itself.
    val own = (summary.runDistanceM ?: 0.0) + (summary.walkDistanceM ?: 0.0)
    val k = if (distanceM != null && own >= 100) (distanceM / own).takeIf { it in 0.7..1.3 } ?: 1.0 else 1.0
    val g = if (k == 1.0) summary else summary.copy(bouts = summary.bouts.map { it.copy(distanceM = it.distanceM?.times(k)) },
        runDistanceM = summary.runDistanceM?.times(k), walkDistanceM = summary.walkDistanceM?.times(k))
    Gap(24); SectionTitle("Running and walking"); Gap(10)
    StatGrid(listOf(
        Triple("Running", clock(g.runS.toDouble()), null), Triple("Walking", if (g.walkS > 0) clock(g.walkS.toDouble()) else "–", null),
        Triple("Runs", if (g.runs.isEmpty()) "–" else g.runs.size.toString(), null),
        Triple("Run pace", pace(g.runPaceSPerKm), "/km"), Triple("Walk pace", pace(g.walkPaceSPerKm), "/km"),
        Triple("Distance", (g.runDistanceM?.plus(g.walkDistanceM ?: 0.0)?.takeIf { it >= 100 } ?: distanceM?.toDouble())?.let { "%.2f".format(it / 1000) } ?: "–", "km"),
        Triple("Run cadence", g.runCadence?.toString() ?: "–", "spm"), Triple("Run HR", g.runHr?.toString() ?: "–", "bpm"),
        Triple("Walk HR", g.walkHr?.takeIf { g.walkS > 0 }?.toString() ?: "–", "bpm"),
        Triple("1-min drop", g.recovery60?.toString() ?: "–", "bpm"),
        Triple("Drift", g.driftBpm?.let { if (it > 0) "+$it" else "$it" } ?: "–", "bpm"),
    ))
    Gap(14)
    Text(gaitSentences(g).joinToString(" "), style = HL.type.body, color = c.ink)
    val runs = g.runs
    if (runs.size < 2) return
    Gap(12)
    Row { LegendDot(c.warn, "Run"); Spacer(Modifier.width(14.dp)); LegendDot(c.ok, "Walk after it") }
    Gap(4)
    val after = runs.associateWith { r -> g.bouts.getOrNull(g.bouts.indexOf(r) + 1)?.takeIf { !it.running && it.start - r.end <= 10 } }
    // The bars share a scale set by the intervals; a long walk home after the last run is drawn to the edge, not scaled to.
    val walks = after.values.filterNotNull().map { it.seconds }.sorted()
    val cap = walks.getOrNull(walks.size / 2)?.let { maxOf(it * 2, runs.maxOf { r -> r.seconds }) } ?: Int.MAX_VALUE
    val longest = runs.maxOf { it.seconds + minOf(after[it]?.seconds ?: 0, cap) }.coerceAtLeast(1)
    runs.forEachIndexed { i, r -> IntervalRow(i + 1, r, after[r], longest) }
}

@Composable
private fun IntervalRow(n: Int, run: GaitBout, walk: GaitBout?, longest: Int) {
    val c = HL.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$n", style = HL.type.label, color = c.inkSoft, modifier = Modifier.width(28.dp))
            Canvas(Modifier.weight(1f).height(12.dp)) {
                val unit = size.width / longest
                val runW = run.seconds * unit
                drawRoundRect(c.warn, Offset.Zero, Size(runW, size.height), CornerRadius(3.dp.toPx()))
                walk?.let { w -> drawRoundRect(c.ok.copy(alpha = 0.55f), Offset(runW + 2.dp.toPx(), 0f),
                    Size((w.seconds * unit - 2.dp.toPx()).coerceIn(1f, (size.width - runW - 2.dp.toPx()).coerceAtLeast(1f)), size.height), CornerRadius(3.dp.toPx())) }
            }
            Text(clock(run.seconds.toDouble()) + (walk?.let { " + " + clock(it.seconds.toDouble()) } ?: ""), style = HL.type.label, color = c.ink,
                textAlign = TextAlign.End, modifier = Modifier.width(92.dp))
        }
        val bits = listOfNotNull(
            run.avgHr?.let { a -> "$a bpm" + (run.peakHr?.takeIf { it - a >= 3 }?.let { ", peak $it" } ?: "") },
            run.paceSPerKm?.let { "${pace(it)} /km" },
            run.cadenceSpm?.let { "$it spm" },
            walk?.let { w -> if (w.startHr != null && w.endHr != null && w.startHr!! > w.endHr!!) "walk fell to ${w.endHr}" else null },
        )
        if (bits.isNotEmpty()) MutedText(bits.joinToString(", "), Modifier.padding(start = 28.dp, top = 2.dp))
    }
    HorizontalDivider(color = c.line)
}

/** A bout's time span for the route map's colours. */
internal fun GaitSummary.routeBouts(): List<dev.heartline.ui.components.RouteBout> =
    bouts.map { dev.heartline.ui.components.RouteBout(it.start, it.end, it.running) }

