package dev.heartline.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import dev.heartline.analytics.RideSplit
import dev.heartline.analytics.RideSummary
import dev.heartline.app.SeriesPoint
import dev.heartline.ui.components.ChartLine
import dev.heartline.ui.components.ChartSpan
import dev.heartline.ui.components.InteractiveChart
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.Panel
import dev.heartline.ui.components.SectionTitle
import dev.heartline.ui.components.clock
import dev.heartline.ui.theme.HL
import kotlin.math.abs
import kotlin.math.roundToInt

private fun kmh(v: Double?) = v?.let { "%.1f".format(it) } ?: "–"

/** The few plain sentences that sum up a ride. */
internal fun rideSentences(r: RideSummary): List<String> = buildList {
    add("You rode %.1f km in %s of riding".format(r.distanceM / 1000, clock(r.movingS.toDouble())) +
        (r.avgKmh?.let { ", %.1f km/h on average while moving".format(it) } ?: "") + ".")
    when {
        r.stops == 0 -> add("No stops along the way.")
        r.stops == 1 -> add("You stopped once, for ${clock(r.longestStopS.toDouble())}.")
        else -> add("You stopped ${r.stops} times, ${clock(r.stoppedS.toDouble())} in all; the longest was ${clock(r.longestStopS.toDouble())}.")
    }
    r.top5MinKmh?.let { t -> add("Your fastest 5 minutes were %.1f km/h".format(t) + (r.top30Kmh?.let { ", your fastest 30 seconds %.1f".format(it) } ?: "") + ".") }
    r.climbM?.takeIf { it >= 10 }?.let { up -> add("You climbed about ${up.roundToInt()} m" + (r.descentM?.takeIf { it >= 10 }?.let { " and came down ${it.roundToInt()} m" } ?: "") + ".") }
    r.fadePct?.let { f ->
        when {
            f >= 8 -> add("The last third of the kilometres were ${f.roundToInt()}% slower than the first: you tired, or the road turned uphill or into the wind.")
            f <= -8 -> add("The last third of the kilometres were ${abs(f).roundToInt()}% faster than the first.")
            else -> add("You held your speed from the first kilometres to the last.")
        }
    }
    r.ridingHr?.let { h -> add("Heart rate averaged $h bpm while riding" + (r.peakHr?.let { ", peaking at $it" } ?: "") +
        (r.metresPerBeat?.let { "; each heartbeat carried you %.1f m".format(it) } ?: "") + ".") }
}

/**
 * A ride: the numbers, the sentences, speed through it with the stops shaded (when [speed] has it; a watch workout
 * shows its own speed chart), and one row per kilometre. [title] names the section.
 */
@Composable
internal fun RideSection(r: RideSummary, speed: List<SeriesPoint> = emptyList(), title: String = "Riding") {
    val c = HL.colors
    Gap(24); SectionTitle(title); Gap(10)
    StatGrid(listOf(
        Triple("Distance", "%.2f".format(r.distanceM / 1000), "km"), Triple("Moving", clock(r.movingS.toDouble()), null),
        Triple("Average", kmh(r.avgKmh), "km/h"),
        Triple("Best 5 min", kmh(r.top5MinKmh), "km/h"), Triple("Best 30 s", kmh(r.top30Kmh), "km/h"),
        Triple("Stops", if (r.stops == 0) "0" else "${r.stops}", null),
        Triple("Climb", r.climbM?.roundToInt()?.toString() ?: "–", "m"), Triple("Descent", r.descentM?.roundToInt()?.toString() ?: "–", "m"),
        Triple("Longest stop", if (r.stops == 0) "–" else clock(r.longestStopS.toDouble()), null),
        Triple("Riding HR", r.ridingHr?.toString() ?: "–", "bpm"), Triple("Peak HR", r.peakHr?.toString() ?: "–", "bpm"),
        Triple("Per beat", r.metresPerBeat?.let { "%.1f".format(it) } ?: "–", "m"),
    ))
    Gap(14)
    Text(rideSentences(r).joinToString(" "), style = HL.type.body, color = c.ink)
    if (speed.size >= 12) {
        Gap(14)
        Panel(padding = PaddingValues(12.dp)) {
            Text("Speed while riding, stops shaded", style = HL.type.label, color = c.inkSoft); Gap(6)
            val from = r.bouts.first().start.toDouble(); val to = r.bouts.last().end.toDouble()
            InteractiveChart(listOf(ChartLine(speed, c.oxygen, "Speed", "km/h", fill = true, gap = 60.0, format = { "%.1f".format(it) })),
                from, to.coerceAtLeast(from + 60), height = 160.dp, yMin = 0.0,
                spans = r.bouts.filter { !it.riding }.map { ChartSpan(it.start.toDouble(), it.end.toDouble(), c.inkSoft.copy(alpha = 0.14f), "Stopped") },
                xLabel = { clock(it) }, readoutX = { "${clock(it)} in" }, minSpan = 60.0, idleHint = "Touch for your speed at that moment. Pinch to zoom.")
        }
    }
    if (r.splits.size < 2) return
    Gap(16)
    Text("Each kilometre", style = HL.type.bodyStrong, color = c.ink)
    MutedText("Riding time for the kilometre (stops left out), its speed and your heart rate. The bar is the speed against your fastest kilometre.")
    Gap(4)
    val top = r.splits.maxOf { it.kmh }.coerceAtLeast(1.0)
    r.splits.forEach { SplitRow(it, top) }
}

@Composable
private fun SplitRow(s: RideSplit, top: Double) {
    val c = HL.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${s.km}", style = HL.type.label, color = c.inkSoft, modifier = Modifier.width(28.dp))
            Canvas(Modifier.weight(1f).height(10.dp)) {
                drawRoundRect(c.oxygen.copy(alpha = 0.75f), Offset.Zero, Size((size.width * (s.kmh / top)).toFloat().coerceAtLeast(2f), size.height), CornerRadius(3.dp.toPx()))
            }
            Text("%.1f km/h".format(s.kmh), style = HL.type.label, color = c.ink, textAlign = TextAlign.End, modifier = Modifier.width(84.dp))
        }
        MutedText(listOfNotNull(clock(s.movingS.toDouble()), s.avgHr?.let { "$it bpm" }, "at ${clock(s.end.toDouble())} in").joinToString(", "),
            Modifier.padding(start = 28.dp, top = 2.dp))
    }
    HorizontalDivider(color = c.line)
}

/** A ride's riding stretches for the route map's colours. */
internal fun RideSummary.routeBouts(): List<dev.heartline.ui.components.RouteBout> =
    bouts.filter { it.riding }.map { dev.heartline.ui.components.RouteBout(it.start, it.end, running = false, riding = true) }
