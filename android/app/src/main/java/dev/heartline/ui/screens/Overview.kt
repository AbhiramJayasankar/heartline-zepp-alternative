package dev.heartline.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heartline.app.HeartlineController
import dev.heartline.app.Impact
import dev.heartline.app.LinkState
import dev.heartline.app.MinuteState
import dev.heartline.app.TrendsUi
import dev.heartline.ui.Navigator
import dev.heartline.ui.Route
import dev.heartline.ui.Tab
import dev.heartline.ui.components.AreaLabel
import dev.heartline.ui.components.BatteryGlyph
import dev.heartline.ui.components.BigValue
import dev.heartline.ui.components.ButtonStyle
import dev.heartline.ui.components.Card
import dev.heartline.ui.components.ConnectionChip
import dev.heartline.ui.components.DayTimeline
import dev.heartline.ui.components.Delta
import dev.heartline.ui.components.Heading
import dev.heartline.ui.components.Meter
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.PillButton
import dev.heartline.ui.components.StagesBar
import dev.heartline.ui.components.avgOf
import dev.heartline.ui.components.fmtInt
import dev.heartline.ui.components.hm
import dev.heartline.ui.components.latest
import dev.heartline.ui.components.rememberDayStart
import dev.heartline.ui.components.shortDate
import dev.heartline.ui.components.stateColor
import dev.heartline.ui.theme.Area
import dev.heartline.ui.theme.HL
import dev.heartline.ui.theme.hue
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** The step goal set on the watch (Watch settings), else Zepp's default. */
@Composable
fun stepGoal(controller: HeartlineController): Int {
    val settings by controller.watchSettings.collectAsStateWithLifecycle()
    return (settings.firstOrNull { it.key == "step_goal" }?.value as? Number)?.toInt()?.takeIf { it > 0 } ?: 8000
}

/** A trend series' value for [key]: today's if there is one, else the latest day with its date. */
fun TrendsUi?.latestOf(key: String): Pair<Double, LocalDate>? = this?.series?.firstOrNull { it.key == key }?.points?.latest()?.let { p -> p.value!! to p.date }
fun TrendsUi?.seriesOf(key: String) = this?.series?.firstOrNull { it.key == key }

fun readinessWord(r: Int): String = when { r >= 80 -> "Primed"; r >= 65 -> "Good to go"; r >= 45 -> "Steady"; else -> "Go easy" }

/**
 * Today: readiness up top with its three drivers, the AI's headline, a tile for each part of health (each opens its
 * page), the day minute by minute, and what shaped the score.
 */
@Composable
fun OverviewScreen(controller: HeartlineController, nav: Navigator, onTab: (Tab) -> Unit) {
    val c = HL.colors
    val conn by controller.connection.collectAsStateWithLifecycle()
    val battery by controller.battery.collectAsStateWithLifecycle()
    val today by controller.today.collectAsStateWithLifecycle()
    val trends by controller.trends.collectAsStateWithLifecycle()
    val hr by controller.liveHr.collectAsStateWithLifecycle()
    val steps by controller.steps.collectAsStateWithLifecycle()
    val sync by controller.sync.collectAsStateWithLifecycle()
    val insights by controller.insights.collectAsStateWithLifecycle()
    val date = remember { LocalDate.now() }
    val day by remember(date) { controller.healthDay(date) }.collectAsState(initial = null)
    // Overnight readings (SpO2, temperature) often land on the day before; fall back to it.
    val yday by remember(date) { controller.healthDay(date.minusDays(1)) }.collectAsState(initial = null)
    val goal = stepGoal(controller)
    val t = today

    ScrollPage {
        // ---- Readiness hero ----
        Column(Modifier.fillMaxWidth().background(c.wash).statusBarsPadding().padding(horizontal = Gutter).padding(top = 14.dp, bottom = 22.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ConnectionChip(conn, Modifier.weight(1f))
                battery?.let { BatteryGlyph(it.level, it.charging) }
            }
            Gap(16)
            Text(date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)), style = HL.type.label, color = c.inkSoft)
            val ready = t?.readiness
            val readyColor = when { ready == null -> c.inkSoft; ready >= 70 -> c.ok; ready < 45 -> c.pulse; else -> c.ink }
            Row(Modifier.semantics(mergeDescendants = true) { contentDescription = if (ready == null) "Readiness not yet available" else "${t.readinessLabel} $ready out of 100, ${readinessWord(ready)}" },
                verticalAlignment = Alignment.Bottom) {
                Text(ready?.toString() ?: "–", style = HL.type.hero, color = readyColor)
                Column(Modifier.padding(start = 12.dp, bottom = 14.dp)) {
                    Text(if (ready == null) "Readiness" else readinessWord(ready), style = HL.type.section, color = c.ink)
                    Text((t?.readinessLabel ?: "Readiness") + " · of 100", style = HL.type.small, color = c.inkSoft)
                }
            }
            Text(t?.recommendation ?: "Wear the watch tonight and take a heart check in the morning; readiness learns your baseline from both.",
                style = HL.type.body.copy(fontSize = 17.sp, lineHeight = 25.sp), color = c.ink)
            Gap(18)
            // The three drivers, each with a meter.
            val sleepScore = t?.sleepScore?.toDouble()?.let { it to date } ?: trends.latestOf("sleep_score")
            val strain = t?.strain?.let { it to date } ?: trends.latestOf("strain")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Driver("Sleep", sleepScore?.first?.roundToInt()?.toString() ?: "–", "/100", (sleepScore?.first ?: 0.0) / 100, c.hue(Area.SLEEP), sleepScore?.second?.takeIf { it != date }, Modifier.weight(1f))
                Driver("Strain", strain?.first?.let { "%.1f".format(it) } ?: "–", "/21", (strain?.first ?: 0.0) / 21, c.pulse, strain?.second?.takeIf { it != date }, Modifier.weight(1f))
                Driver("Battery", t?.bodyBattery?.toString() ?: "–", "/100", (t?.bodyBattery ?: 0) / 100.0, c.ok, null, Modifier.weight(1f), if (t?.bodyBattery == null) "after a night's sleep" else null)
            }
        }

        Column(Modifier.padding(horizontal = Gutter)) {
            Gap(18)
            // ---- AI headline ----
            val report = insights.report
            Card(onClick = { nav.push(Route.Insights) }, onClickLabel = "Open AI insights", tint = c.surface) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AreaLabel("AI insights", c.pulse, Modifier.weight(1f))
                    if (report != null) Text(agoOf(report.at), style = HL.type.small.copy(fontSize = 12.sp), color = c.inkSoft)
                }
                Gap(6)
                Text(
                    when {
                        insights.running -> "Reading your data…"
                        report != null -> report.headline.ifEmpty { report.insights.first().title }
                        else -> "Get a read on your last two weeks: patterns across sleep, heart, stress and activity, in plain words."
                    },
                    style = HL.type.bodyStrong.copy(fontSize = 17.sp, lineHeight = 24.sp), color = c.ink,
                )
                if (report != null) {
                    Gap(4)
                    MutedText("${report.insights.size} insights · ${report.insights.count { it.tone == "watch" }} to watch")
                }
            }

            // ---- One tile per area ----
            Heading("Your health", action = "All trends", onAction = { nav.push(Route.Trends) })
            val night = t?.lastNight
            val restingNow = t?.restingHr?.toDouble()?.let { it to date } ?: trends.latestOf("rhr")
            val rhrUsual = trends.seriesOf("rhr")?.baseline
            val stepsNow = steps?.steps ?: t?.stepsToday ?: day?.stepsTotal
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("Sleep", c.hue(Area.SLEEP), Modifier.weight(1f).fillMaxHeight(), { onTab(Tab.SLEEP) }) {
                    BigValue(night?.let { hm(it.deepMin + it.lightMin + it.remMin) } ?: "–", size = 28)
                    if (night != null) {
                        val ended = java.time.Instant.ofEpochSecond(night.end).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        Text("${timeOf(night.start)} – ${timeOf(night.end)}" + if (ended != date) ", ${shortDate(ended)}" else "", style = HL.type.small, color = c.inkSoft)
                        Gap(8); StagesBar(night)
                    } else MutedText("No night synced yet")
                }
                Tile("Heart", c.pulse, Modifier.weight(1f).fillMaxHeight(), { onTab(Tab.HEART) }) {
                    BigValue(restingNow?.first?.roundToInt()?.toString() ?: "–", "bpm", size = 28)
                    Text("Resting" + (restingNow?.second?.takeIf { it != date }?.let { ", ${shortDate(it)}" } ?: ""), style = HL.type.small, color = c.inkSoft)
                    Gap(6)
                    if (restingNow != null && rhrUsual != null) {
                        val diff = (restingNow.first - rhrUsual).roundToInt()
                        Delta(if (diff == 0) "at your usual" else "${if (diff > 0) "▲" else "▼"} ${kotlin.math.abs(diff)} vs usual", if (diff == 0) null else diff < 0)
                    } else if (hr.bpm != null) MutedText("Now ${hr.bpm} bpm")
                }
            }
            Gap(10)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("Steps", c.hue(Area.MOVE), Modifier.weight(1f).fillMaxHeight(), { onTab(Tab.ACTIVITY) }) {
                    BigValue(fmtInt(stepsNow), size = 28)
                    Text("of ${fmtInt(goal)} goal", style = HL.type.small, color = c.inkSoft)
                    Gap(8)
                    Meter((stepsNow ?: 0) / goal.toFloat(), c.hue(Area.MOVE), description = "${((stepsNow ?: 0) * 100 / goal)} percent of the step goal")
                    Gap(6)
                    MutedText("${day?.distanceM?.let { "%.1f km".format(it / 1000.0) } ?: "– km"} · ${day?.caloriesKcal?.let { "$it kcal" } ?: "– kcal"}")
                }
                val stress = day?.stressAvg?.toDouble()?.let { it to date } ?: trends.latestOf("stress")
                Tile("Stress", c.hue(Area.STRESS), Modifier.weight(1f).fillMaxHeight(), { nav.push(dayRoute(DayMetric.STRESS, date.toEpochDay())) }) {
                    BigValue(stress?.first?.roundToInt()?.toString() ?: "–", "avg", size = 28)
                    Text(stress?.first?.let { stressWord(it) } ?: "No readings yet", style = HL.type.small, color = c.inkSoft)
                    Gap(8)
                    Meter(((stress?.first ?: 0.0) / 100).toFloat(), stress?.first?.let { stressColor(stressWord(it), c) } ?: c.line)
                    day?.stressBands?.firstOrNull { it.name == "Relaxed" }?.minutes?.takeIf { it > 0 }?.let { Gap(6); MutedText("Relaxed ${hm(it)}") }
                }
            }
            Gap(10)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val spo2 = day?.spo2Avg?.let { it to date } ?: yday?.spo2Avg?.let { it to date.minusDays(1) } ?: trends.latestOf("spo2")
                Tile("Blood oxygen", c.hue(Area.BODY), Modifier.weight(1f).fillMaxHeight(), { nav.push(dayRoute(DayMetric.BODY, date.toEpochDay())) }) {
                    BigValue(spo2?.first?.let { "%.0f".format(it) } ?: "–", "%", size = 28)
                    Text(if (spo2 == null) "No readings yet" else "Average" + (spo2.second.takeIf { it != date }?.let { ", ${shortDate(it)}" } ?: ""), style = HL.type.small, color = c.inkSoft)
                    (day?.spo2Min ?: yday?.spo2Min)?.let { Gap(6); MutedText("Lowest ${"%.0f".format(it)}%") }
                }
                val temp = day?.tempAvg?.let { it to date } ?: yday?.tempAvg?.let { it to date.minusDays(1) } ?: trends.latestOf("temp")
                Tile("Temperature", c.warn, Modifier.weight(1f).fillMaxHeight(), { nav.push(dayRoute(DayMetric.BODY, date.toEpochDay())) }) {
                    BigValue(temp?.first?.let { "%.1f".format(it) } ?: "–", "°C", size = 28)
                    Text(if (temp == null) "No readings yet" else "Skin, average" + (temp.second.takeIf { it != date }?.let { ", ${shortDate(it)}" } ?: ""), style = HL.type.small, color = c.inkSoft)
                    if (day?.tempMin != null && day?.tempMax != null) { Gap(6); MutedText("${"%.1f".format(day!!.tempMin)}–${"%.1f".format(day!!.tempMax)} °C") }
                }
            }

            // ---- The day minute by minute ----
            val d = day
            if (d != null && d.minutes.isNotEmpty()) {
                Heading("Through the day", action = "Open", onAction = { onTab(Tab.ACTIVITY) })
                Card(padding = 12.dp) {
                    dev.heartline.ui.components.MinuteChart(d.minutes, rememberDayStart(date.toEpochDay()), height = 170.dp, title = "Today, minute by minute")
                    Gap(8)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("Asleep" to MinuteState.SLEEP_LIGHT, "Active" to MinuteState.ACTIVE, "Still" to MinuteState.IDLE, "Charging" to MinuteState.CHARGING).forEach { (l, s) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(stateColor(s, c) ?: Color.Transparent))
                                Spacer(Modifier.width(5.dp)); Text(l, style = HL.type.small.copy(fontSize = 12.sp), color = c.inkSoft)
                            }
                        }
                    }
                }
            }

            // ---- What shaped readiness ----
            if (t != null && t.factors.isNotEmpty()) {
                Heading("What shaped today")
                Card(padding = 14.dp) {
                    t.factors.forEachIndexed { i, f ->
                        if (i > 0) Gap(10)
                        Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
                            val (mark, color) = when (f.impact) { Impact.POSITIVE -> "+" to c.ok; Impact.NEGATIVE -> "–" to c.pulse; Impact.NEUTRAL -> "·" to c.inkSoft }
                            Box(Modifier.size(24.dp).clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                                Text(mark, style = HL.type.bodyStrong, color = color)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.name, style = HL.type.bodyStrong, color = c.ink)
                                Text(f.detail, style = HL.type.small, color = c.inkSoft)
                            }
                        }
                    }
                }
            }
            t?.alerts?.takeIf { it.isNotEmpty() }?.let { alerts ->
                Heading("Worth a look")
                alerts.forEach { a ->
                    dev.heartline.ui.components.Note(a.detail, Modifier.padding(bottom = 8.dp), color = if (a.warn) c.pulse else c.inkSoft, title = a.title)
                }
            }

            // ---- Week at a glance ----
            // Whole days only: today's steps are still adding up.
            val weekSteps = trends.seriesOf("steps")?.points?.filter { it.date != date }?.avgOf(7)
            val weekSleep = trends.seriesOf("sleep")?.points?.avgOf(7)
            if (weekSteps != null || weekSleep != null) {
                Heading("Last 7 days", action = "Trends", onAction = { nav.push(Route.Trends) })
                Card(padding = 14.dp) {
                    Row(Modifier.fillMaxWidth()) {
                        dev.heartline.ui.components.Fact("Sleep, avg", weekSleep?.let { hm((it * 60).roundToInt()) } ?: "–", Modifier.weight(1f))
                        dev.heartline.ui.components.Fact("Steps, avg", fmtInt(weekSteps?.roundToInt()), Modifier.weight(1f))
                        dev.heartline.ui.components.Fact("Resting, avg", fmtInt(trends.seriesOf("rhr")?.points?.avgOf(7)?.roundToInt()), Modifier.weight(1f), unit = "bpm")
                    }
                }
            }

            Gap(20)
            Row(verticalAlignment = Alignment.CenterVertically) {
                MutedText(if (sync.running) sync.message ?: "Syncing…" else sync.lastActivity?.let { "Synced ${agoOf(it)}" } ?: "Not synced yet", Modifier.weight(1f))
                PillButton(if (sync.running) "Syncing…" else "Sync now", { controller.syncNow() }, style = ButtonStyle.QUIET, enabled = !sync.running && conn.state == LinkState.CONNECTED)
            }
            Gap(28)
        }
    }
}

fun stressWord(v: Double): String = when { v < 40 -> "Relaxed"; v < 60 -> "Normal"; v < 80 -> "Medium"; else -> "High" }

/** One of readiness' three drivers: value, a meter, and the date when it isn't today's. */
@Composable
private fun Driver(label: String, value: String, unit: String, fraction: Double, color: Color, from: LocalDate?, modifier: Modifier, note: String? = null) {
    val c = HL.colors
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = HL.type.label, color = c.inkSoft)
        BigValue(value, unit, size = 26)
        Gap(6)
        Meter(fraction.toFloat(), color, height = 6.dp)
        if (from != null || note != null) Text(note ?: shortDate(from!!), style = HL.type.small.copy(fontSize = 11.sp), color = c.inkSoft, modifier = Modifier.padding(top = 3.dp))
    }
}

/** A tappable tile for one area of health. */
@Composable
private fun Tile(title: String, color: Color, modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    Card(modifier, onClick = onClick, onClickLabel = "Open $title", padding = 14.dp) {
        AreaLabel(title, color)
        Gap(6)
        content()
    }
}
