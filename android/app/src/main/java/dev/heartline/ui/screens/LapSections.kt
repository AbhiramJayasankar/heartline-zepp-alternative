package dev.heartline.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.heartline.analytics.Gait
import dev.heartline.analytics.Lap
import dev.heartline.analytics.LapSummary
import dev.heartline.ui.components.LegendDot
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.SectionTitle
import dev.heartline.ui.components.clock
import dev.heartline.ui.theme.HL
import kotlin.math.abs
import kotlin.math.roundToInt

/** Where a session's laps were measured, said under them. */
enum class LapSource(val note: String) {
    PHONE("Laps and their distances from the phone's GPS."),
    WATCH("Laps and their distances from the watch's GPS."),
    WATCH_STEPS("Laps from where the watch's GPS passed the lap point; its track wandered, so distances come from your steps."),
}

private fun km(m: Double?) = m?.let { if (it >= 1000) "%.2f km".format(it / 1000) else "${it.roundToInt()} m" }

/** The sentences that sum up a session's laps: how many and how long, the fastest and slowest, how even, how it ended. */
internal fun lapSentences(s: LapSummary): List<String> = buildList {
    val laps = s.laps
    val dist = laps.mapNotNull { it.distanceM }.sorted()
    val loop = dist.getOrNull(dist.size / 2)
    add("${laps.size} laps" + (loop?.let { " of about ${km(it)}" } ?: "") +
        (s.tail?.let { t ->
            val part = loop?.let { l -> t.distanceM?.let { it / l } }
            when { part == null -> ", then ${clock(t.seconds)} more"
                part < 0.95 -> " and ${(part * 100).roundToInt().coerceAtLeast(1)}% of another"
                else -> ", then ${km(t.distanceM)} more" }
        } ?: "") + ".")
    if (laps.size >= 2) {
        val f = laps.first { it.index == s.fastest }; val w = laps.first { it.index == s.slowest }
        add("Fastest was lap ${f.index} in ${clock(f.seconds)}" + (f.paceSPerKm?.let { " (${pace(it)} /km)" } ?: "") +
            ", slowest lap ${w.index} in ${clock(w.seconds)}" + (w.stillS.takeIf { it >= 30 }?.let { ", with ${clock(it)} standing" } ?: "") + ".")
    }
    s.spreadPct?.let { sp ->
        add(when { sp < 3 -> "Very even: lap times within ${"%.1f".format(sp)}% of each other."
            sp < 8 -> "Fairly even: lap times varied by ${sp.roundToInt()}%."
            else -> "Uneven: lap times varied by ${sp.roundToInt()}%." })
    }
    s.fadePct?.takeIf { laps.size >= 3 }?.let { fd ->
        add(when { fd >= 3 -> "The last laps were ${fd.roundToInt()}% slower than the first."
            fd <= -3 -> "The last laps were ${abs(fd).roundToInt()}% faster than the first: a negative split."
            else -> "The last laps went at the pace of the first." })
    }
    val first = laps.firstOrNull(); val last = laps.lastOrNull()
    if (first != null && last != null && first !== last && (first.runS > 0 || last.runS > 0)) {
        val a = (first.runS / first.seconds * 100).roundToInt(); val b = (last.runS / last.seconds * 100).roundToInt()
        add("You ran $a% of the first lap and $b% of the last.")
    }
    val strides = laps.mapNotNull { l -> l.strideM?.let { l to it } }
    if (strides.size >= 2) {
        val (a, b) = strides.first().second to strides.last().second
        if (abs(b - a) >= 0.05) add("Your step was ${"%.2f".format(a)} m on lap ${strides.first().first.index} and ${"%.2f".format(b)} m on lap ${strides.last().first.index}.")
    }
    val beats = laps.mapNotNull { l -> l.metresPerBeat?.let { l to it } }
    if (beats.size >= 3) {
        val a = beats.take(beats.size / 2).map { it.second }.average(); val b = beats.drop(beats.size / 2).map { it.second }.average()
        val ch = (b / a - 1) * 100
        if (abs(ch) >= 5) add("Each heartbeat carried you ${abs(ch).roundToInt()}% ${if (ch > 0) "further" else "less far"} in the second half of the laps than the first" +
            (if (ch < 0) ": tiring, or running slower for the same effort." else "."))
    }
}

/**
 * The laps of a loop: what they add up to, the sentences, and one row per lap whose bar shows its running, walking and
 * standing on one time scale. A row opens to the lap's stretches of each with their distance and pace.
 */
@Composable
internal fun LapsSection(s: LapSummary, source: LapSource) {
    val c = HL.colors
    val laps = s.laps
    val dist = laps.mapNotNull { it.distanceM }.sorted()
    val fastest = laps.firstOrNull { it.index == s.fastest }
    Gap(24); SectionTitle("Laps"); Gap(10)
    StatGrid(listOf(
        Triple("Laps", laps.size.toString() + if (s.tail != null) "+" else "", null),
        Triple("Lap length", dist.getOrNull(dist.size / 2)?.roundToInt()?.toString() ?: "–", "m"),
        Triple("Average lap", clock(laps.map { it.seconds }.average()), null),
        Triple("Fastest lap", fastest?.let { clock(it.seconds) } ?: "–", fastest?.let { "lap ${it.index}" }),
        Triple("Best 30 s", pace(laps.mapNotNull { it.bestPaceSPerKm }.minOrNull()), "/km"),
        Triple("Lap spread", s.spreadPct?.let { "%.1f".format(it) } ?: "–", "%"),
    ))
    Gap(14)
    Text(lapSentences(s).joinToString(" "), style = HL.type.body, color = c.ink)
    Gap(12)
    Row { LegendDot(c.warn, "Run"); Spacer(Modifier.width(14.dp)); LegendDot(c.ok, "Walk"); Spacer(Modifier.width(14.dp)); LegendDot(c.line, "Standing") }
    Gap(4)
    val longest = (laps + listOfNotNull(s.lead, s.tail)).maxOf { it.seconds }.coerceAtLeast(1.0)
    s.lead?.let { LapRow(it, "Start", longest, fastest = false) }
    laps.forEach { LapRow(it, "${it.index}", longest, fastest = it.index == s.fastest && laps.size >= 2) }
    s.tail?.let { LapRow(it, "End", longest, fastest = false) }
    Gap(8)
    MutedText(source.note + " Tap a lap for its runs and walks.")
}

@Composable
private fun LapRow(lap: Lap, name: String, longest: Double, fastest: Boolean) {
    val c = HL.colors
    var open by rememberSaveable(lap.start) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable(onClickLabel = if (open) "Hide lap $name" else "Show lap $name", role = Role.Button) { open = !open }
        .animateContentSize().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = if (fastest) HL.type.bodyStrong else HL.type.label, color = if (fastest) c.ink else c.inkSoft,
                modifier = Modifier.width(44.dp))
            Canvas(Modifier.weight(1f).height(12.dp)) {
                val unit = size.width / longest.toFloat()
                val gap = 1.dp.toPx()
                for (g in lap.segments) {
                    val x = ((g.start - lap.start) * unit).toFloat()
                    val w = (g.seconds * unit).toFloat() - gap
                    if (w <= 0f) continue
                    val col = when (g.gait) { Gait.RUN -> c.warn; Gait.WALK -> c.ok.copy(alpha = 0.55f); else -> c.line }
                    drawRoundRect(col, Offset(x, 0f), Size(w, size.height), CornerRadius(3.dp.toPx()))
                }
            }
            Text(clock(lap.seconds), style = HL.type.label, color = c.ink, textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
        }
        val bits = listOfNotNull(
            km(lap.distanceM),
            lap.paceSPerKm?.let { "${pace(it)} /km" },
            lap.runPaceSPerKm?.takeIf { lap.runS >= 20 && lap.walkS >= 20 }?.let { "running ${pace(it)}" },
            lap.walkPaceSPerKm?.takeIf { lap.runS >= 20 && lap.walkS >= 20 }?.let { "walking ${pace(it)}" },
            lap.avgHr?.let { a -> "$a bpm" + (lap.maxHr?.takeIf { it - a >= 5 }?.let { ", peak $it" } ?: "") },
            lap.cadenceSpm?.let { "$it spm" },
            lap.strideM?.let { "%.2f m step".format(it) },
        )
        if (bits.isNotEmpty()) MutedText(bits.joinToString(", "), Modifier.padding(start = 44.dp, top = 2.dp))
        if (open) {
            Gap(6)
            lap.segments.filter { it.seconds >= 3 }.forEach { g ->
                val what = when (g.gait) { Gait.RUN -> "Run"; Gait.WALK -> "Walk"; else -> "Stood" }
                val line = listOfNotNull("$what ${clock(g.seconds)}", g.distanceM?.takeIf { g.gait != Gait.STILL }?.let { km(it) },
                    g.paceSPerKm?.takeIf { g.gait != Gait.STILL }?.let { "${pace(it)} /km" }, g.cadenceSpm?.takeIf { g.gait != Gait.STILL }?.let { "$it spm" },
                    g.avgHr?.let { "$it bpm" }).joinToString(", ")
                Text(line, style = HL.type.small, color = c.ink, modifier = Modifier.padding(start = 44.dp, top = 3.dp))
            }
            lap.bestPaceSPerKm?.let { MutedText("Fastest 30 seconds: ${pace(it)} /km", Modifier.padding(start = 44.dp, top = 4.dp)) }
        }
    }
    HorizontalDivider(color = c.line)
}

/** The laps for the map's lap picker: the start, each lap and the end, with a line of each one's figures. */
internal fun LapSummary.mapLaps(): List<dev.heartline.ui.components.MapLap> =
    (listOfNotNull(lead) + laps + listOfNotNull(tail)).map { l ->
        val name = when { l === lead -> "Start"; l === tail -> "End"; else -> "Lap ${l.index}" }
        dev.heartline.ui.components.MapLap(name, l.start, l.end, listOfNotNull("$name: ${clock(l.seconds)}", km(l.distanceM),
            l.paceSPerKm?.let { "${pace(it)} /km" }, l.runS.takeIf { it >= 20 }?.let { "ran ${clock(it)}" },
            l.walkS.takeIf { it >= 20 }?.let { "walked ${clock(it)}" }, l.avgHr?.let { "$it bpm" }).joinToString(", "))
    }
