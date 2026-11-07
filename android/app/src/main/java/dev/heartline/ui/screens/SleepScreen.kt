package dev.heartline.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heartline.app.HeartlineController
import dev.heartline.app.MinuteState
import dev.heartline.app.SleepNightUi
import dev.heartline.ui.Navigator
import dev.heartline.ui.Route
import dev.heartline.ui.components.AreaLabel
import dev.heartline.ui.components.BigValue
import dev.heartline.ui.components.Card
import dev.heartline.ui.components.DateStepper
import dev.heartline.ui.components.DayBars
import dev.heartline.ui.components.Delta
import dev.heartline.ui.components.FactGrid
import dev.heartline.ui.components.FactItem
import dev.heartline.ui.components.Heading
import dev.heartline.ui.components.Hypnogram
import dev.heartline.ui.components.Meter
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.Note
import dev.heartline.ui.components.RangeBar
import dev.heartline.ui.components.Ring
import dev.heartline.ui.components.StagesBar
import dev.heartline.ui.components.hm
import dev.heartline.ui.theme.Area
import dev.heartline.ui.theme.HL
import dev.heartline.ui.theme.hue
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val nightFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

/**
 * One night in depth, picked by the morning it ended: time asleep and the sleep score, the stages through the night
 * against usual ranges, heart rate and blood oxygen while asleep, then two weeks of nights and how regular they are.
 */
@Composable
fun SleepScreen(controller: HeartlineController, nav: Navigator) {
    val c = HL.colors
    val today = remember { LocalDate.now() }
    val todayUi by controller.today.collectAsStateWithLifecycle()
    // Open on the latest night that has sleep (last night's appears with the first sync after you wake).
    val latestNight = todayUi?.lastNight?.end?.let { java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
    var picked by rememberSaveable { mutableStateOf<Long?>(null) }
    val epochDay = picked ?: (latestNight ?: today).toEpochDay()
    val date = LocalDate.ofEpochDay(epochDay)
    val day by remember(epochDay) { controller.healthDay(date) }.collectAsState(initial = null)
    val eve by remember(epochDay) { controller.healthDay(date.minusDays(1)) }.collectAsState(initial = null)
    val trends by controller.trends.collectAsStateWithLifecycle()
    val sleepSeries = trends.seriesOf("sleep")
    val scoreSeries = trends.seriesOf("sleep_score")
    val night = day?.sleep

    ScrollPage {
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Gutter).padding(top = 14.dp)) {
            Text("Sleep", style = HL.type.title, color = c.ink)
            MutedText("The night that ended on the morning you pick.")
            Gap(12)
            DateStepper(if (date == today) "Last night" else date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)),
                "Night of ${date.minusDays(1).format(DateTimeFormatter.ofPattern("EEE d", Locale.ENGLISH))} → ${date.format(DateTimeFormatter.ofPattern("EEE d", Locale.ENGLISH))}",
                onPrev = { picked = epochDay - 1 }, onNext = { picked = epochDay + 1 }, nextEnabled = date < today)
        }
        Column(Modifier.padding(horizontal = Gutter)) {
            Gap(14)
            if (day == null) { MutedText("Loading…"); return@Column }
            if (night == null) {
                Card {
                    Text("No sleep recorded for this night", style = HL.type.bodyStrong, color = c.ink)
                    MutedText(if (date == today) "Sleep shows up after the morning sync. Wear the watch to bed and pull down on Today to sync." else "The watch didn't record sleep this night, or it hasn't been synced.")
                }
            } else NightDetail(night, day!!.minutes + (eve?.minutes ?: emptyList()), score = scoreSeries?.points?.firstOrNull { it.date == date }?.value?.roundToInt(),
                usualMin = sleepSeries?.points?.dropLast(1)?.mapNotNull { it.value }?.takeIf { it.isNotEmpty() }?.average()?.let { (it * 60).roundToInt() },
                spo2Points = (day!!.spo2 + (eve?.spo2 ?: emptyList())).filter { it.t >= night.start && it.t <= night.end }.sortedBy { it.t })

            // ---- Two weeks ----
            sleepSeries?.let { s ->
                Heading("Last ${s.points.size} nights", action = "Trend", onAction = { nav.push(Route.Trend("sleep")) })
                Card(padding = 14.dp) {
                    val avg = s.points.mapNotNull { it.value }.takeIf { it.isNotEmpty() }?.average()
                    val nights = trends?.nights.orEmpty().takeLast(s.points.size)
                    val usual = dev.heartline.ui.components.usualWindow(nights)
                    Row(Modifier.fillMaxWidth()) {
                        dev.heartline.ui.components.Fact("Average", avg?.let { hm((it * 60).roundToInt()) } ?: "–", Modifier.weight(1f))
                        dev.heartline.ui.components.Fact("Usual bedtime", usual?.let { dev.heartline.ui.components.clockOf(it.first) } ?: "–", Modifier.weight(1f))
                        dev.heartline.ui.components.Fact("Usual wake", usual?.let { dev.heartline.ui.components.clockOf(it.second) } ?: "–", Modifier.weight(1f))
                    }
                    Gap(4)
                    MutedText("${s.points.count { (it.value ?: 0.0) >= 7 }} of ${s.points.count { it.value != null }} nights reached 7 hours, the least most adults need.")
                    Gap(10)
                    if (nights.any { it.start != null }) dev.heartline.ui.components.SleepWindows(nights, c.hue(Area.SLEEP))
                    else DayBars(s.points, c.hue(Area.SLEEP), reference = 7.0, referenceLabel = "7 h, the least most adults need", unit = "h", format = { "%.1f".format(it) }, highlightLast = false)
                }
            }
            scoreSeries?.takeIf { it.points.any { p -> p.value != null } }?.let { s ->
                Gap(10)
                Card(padding = 14.dp) {
                    AreaLabel("Sleep score", c.hue(Area.SLEEP), dot = false)
                    Gap(4)
                    DayBars(s.points, c.hue(Area.SLEEP), line = true, height = 110.dp, unit = "", format = { "%.0f".format(it) })
                }
            }
            trends?.sleepRegularity?.let { reg ->
                Heading("Consistency")
                Card(padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BigValue("$reg", "/100", size = 30)
                        Spacer(Modifier.width(12.dp))
                        Text(when { reg >= 80 -> "Very regular"; reg >= 60 -> "Fairly regular"; else -> "Irregular" }, style = HL.type.bodyStrong, color = c.ink)
                    }
                    Gap(8)
                    Meter(reg / 100f, c.hue(Area.SLEEP), description = "Sleep regularity $reg out of 100")
                    Gap(8)
                    MutedText("How alike your sleep and wake times are from day to day. Going to bed and waking at the same times, weekends included, is one of the strongest levers on sleep quality.")
                }
            }
            Gap(16)
            Note("Stages are estimated from wrist movement and heart rate, so treat them as a guide rather than a lab result. Duration and timing are the most reliable numbers here.")
            Gap(28)
        }
    }
}

@Composable
private fun NightDetail(n: SleepNightUi, minutes: List<dev.heartline.app.MinuteUi>, score: Int?, usualMin: Int?, spo2Points: List<dev.heartline.app.SeriesPoint>) {
    val c = HL.colors
    val spo2 = spo2Points.map { it.v }
    val asleep = n.deepMin + n.lightMin + n.remMin
    val inBed = ((n.end - n.start) / 60).toInt().coerceAtLeast(asleep)
    // ---- Headline ----
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                AreaLabel("Asleep", c.hue(Area.SLEEP), dot = false)
                BigValue(hm(asleep), size = 40)
                Text("${timeOf(n.start)} → ${timeOf(n.end)}", style = HL.type.bodyStrong, color = c.ink)
                Text("${hm(inBed)} in bed · ${if (inBed > 0) 100 * asleep / inBed else 0}% asleep", style = HL.type.small, color = c.inkSoft)
                if (n.ongoing) { Gap(4); Text("So far. This night updates with the next sync if you're still asleep or doze off again.", style = HL.type.small, color = c.inkSoft) }
                usualMin?.let { u ->
                    val diff = asleep - u
                    Gap(6)
                    Delta(if (abs(diff) < 10) "about your usual" else "${if (diff > 0) "▲" else "▼"} ${hm(abs(diff))} vs usual", if (abs(diff) < 10) null else diff > 0)
                }
            }
            score?.let { s ->
                Ring(s / 100f, c.hue(Area.SLEEP), size = 104.dp, stroke = 10.dp, description = "Sleep score $s out of 100") {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$s", style = HL.type.stat.copy(fontSize = 32.sp), color = c.ink)
                        Text("score", style = HL.type.small.copy(fontSize = 11.sp), color = c.inkSoft)
                    }
                }
            }
        }
    }
    Gap(10)
    // ---- Stages ----
    Card(padding = 14.dp) {
        AreaLabel("Stages through the night", c.hue(Area.SLEEP), dot = false)
        Gap(8)
        Hypnogram(minutes, n.start, n.end, spo2 = spo2Points)
        Gap(12)
        StagesBar(n)
        Gap(12)
        val total = (asleep + n.awakeMin).coerceAtLeast(1)
        listOf(
            Stage("Deep", n.deepMin, c.sleepDeep, 0.13f, 0.23f, "Body repair and immune work happen mostly here."),
            Stage("Light", n.lightMin, c.sleepLight, 0.45f, 0.60f, "Most of the night; the bridge between stages."),
            Stage("REM", n.remMin, c.sleepRem, 0.20f, 0.25f, "Dreaming; memory and mood."),
            Stage("Awake", n.awakeMin, c.warn, 0f, 0.10f, "Short wakings are normal; most aren't remembered."),
        ).forEach { s ->
            val share = s.min / total.toFloat()
            Column(Modifier.padding(vertical = 6.dp).semantics(mergeDescendants = true) {}) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name, style = HL.type.bodyStrong, color = c.ink, modifier = Modifier.weight(1f))
                    Text(hm(s.min), style = HL.type.bodyStrong, color = c.ink)
                    Text("  ${(share * 100).roundToInt()}%", style = HL.type.small, color = c.inkSoft, modifier = Modifier.width(48.dp))
                }
                Gap(4)
                RangeBar(share, s.low, s.high, s.color)
                val word = dev.heartline.ui.components.rangeWord(share, s.low, s.high)
                Text("${word.replaceFirstChar { it.uppercase() }} (${(s.low * 100).roundToInt()}–${(s.high * 100).roundToInt()}% for adults, the tinted stretch). ${s.about}",
                    style = HL.type.small.copy(fontSize = 12.sp), color = c.inkSoft, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
    // ---- Body while asleep ----
    val hrs = minutes.filter { it.ts in n.start until n.end && it.hr != null && it.state.name.startsWith("SLEEP") }.mapNotNull { it.hr }
    val wakings = minutes.filter { it.ts in n.start until n.end }.sortedBy { it.ts }
        .zipWithNext().count { (a, b) -> a.state.name.startsWith("SLEEP") && (b.state == MinuteState.ACTIVE || b.state == MinuteState.IDLE) }
    if (hrs.isNotEmpty() || spo2.isNotEmpty()) {
        Heading("Your body while asleep")
        Card(padding = 14.dp) {
            FactGrid(listOfNotNull(
                hrs.takeIf { it.isNotEmpty() }?.let { FactItem("Lowest HR", "${it.min()}", "bpm") },
                hrs.takeIf { it.isNotEmpty() }?.let { FactItem("Average HR", "${it.average().roundToInt()}", "bpm") },
                FactItem("Wakings", "$wakings", null),
                spo2.takeIf { it.isNotEmpty() }?.let { FactItem("SpO2 avg", "%.0f".format(it.average()), "%") },
                spo2.takeIf { it.isNotEmpty() }?.let { FactItem("SpO2 lowest", "%.0f".format(it.min()), "%", color = if (it.min() < 90) HL.colors.pulse else null) },
                spo2.takeIf { it.isNotEmpty() }?.let { FactItem("Readings", "${it.size}", null) },
            ))
            Gap(10)
            MutedText("A low, steady heart rate overnight is a sign of good recovery; the lowest point usually comes in the second half of the night.")
        }
        Gap(10)
        NightBodyChart(n, minutes, spo2Points)
    }
}

private class Stage(val name: String, val min: Int, val color: Color, val low: Float, val high: Float, val about: String)

/** Heart rate and blood oxygen through the night over the sleep stages, touchable and expandable. */
@Composable
private fun NightBodyChart(n: SleepNightUi, minutes: List<dev.heartline.app.MinuteUi>, spo2: List<dev.heartline.app.SeriesPoint>) {
    val c = HL.colors
    val inNight = remember(minutes, n) { minutes.filter { it.ts in n.start until n.end }.sortedBy { it.ts } }
    val hr = remember(inNight) { inNight.mapNotNull { m -> m.hr?.let { dev.heartline.app.SeriesPoint(m.ts.toDouble(), it.toDouble()) } } }
    val spans = remember(inNight, c) {
        val out = ArrayList<dev.heartline.ui.components.ChartSpan>()
        var runStart = 0L; var state: MinuteState? = null; var last = 0L
        fun close() { val s = state ?: return; dev.heartline.ui.components.stateColor(s, c)?.let { col -> out += dev.heartline.ui.components.ChartSpan(runStart.toDouble(), last + 60.0, col.copy(alpha = 0.16f), dev.heartline.ui.components.stateName(s)) } }
        inNight.forEach { m -> if (m.state != state || m.ts - last > 120) { close(); runStart = m.ts; state = m.state }; last = m.ts }
        close(); out
    }
    if (hr.isEmpty() && spo2.isEmpty()) return
    Card(padding = 14.dp) {
        AreaLabel("Heart rate and blood oxygen overnight", c.pulse, dot = false)
        Gap(6)
        androidx.compose.runtime.CompositionLocalProvider(dev.heartline.ui.components.LocalChartTitle provides "Heart rate and blood oxygen overnight") {
            dev.heartline.ui.components.InteractiveChart(
                listOfNotNull(
                    dev.heartline.ui.components.ChartLine(hr, c.pulse, "Heart rate", "bpm", width = 1.6.dp, gap = 600.0),
                    spo2.takeIf { it.isNotEmpty() }?.let { dev.heartline.ui.components.ChartLine(it, c.oxygen, "SpO2", "%", width = 1.6.dp, gap = 1200.0) },
                ),
                n.start.toDouble(), n.end.toDouble(), height = 190.dp, spans = spans, minSpan = 900.0,
                idleHint = "Red: heart rate. Blue: blood oxygen. Shading: the sleep stage. Touch for a minute's values.",
            )
        }
        if (spo2.isEmpty()) { Gap(6); MutedText("No blood oxygen this night. Turn on \"Blood oxygen during sleep\" in Watch settings, Tracking.") }
    }
}
