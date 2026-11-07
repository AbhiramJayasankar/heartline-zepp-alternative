package dev.heartline.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heartline.app.HeartlineController
import dev.heartline.app.MinuteState
import dev.heartline.app.SeriesPoint
import dev.heartline.ui.Navigator
import dev.heartline.ui.Route
import dev.heartline.ui.components.AreaLabel
import dev.heartline.ui.components.BigValue
import dev.heartline.ui.components.Card
import dev.heartline.ui.components.ChartBars
import dev.heartline.ui.components.DateStepper
import dev.heartline.ui.components.DayBars
import dev.heartline.ui.components.DayTimeline
import dev.heartline.ui.components.Fact
import dev.heartline.ui.components.Heading
import dev.heartline.ui.components.InteractiveChart
import dev.heartline.ui.components.Meter
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.Note
import dev.heartline.ui.components.Ring
import dev.heartline.ui.components.epochClock
import dev.heartline.ui.components.fmtInt
import dev.heartline.ui.components.hm
import dev.heartline.ui.components.rememberDayStart
import dev.heartline.ui.components.stateColor
import dev.heartline.ui.theme.Area
import dev.heartline.ui.theme.HL
import dev.heartline.ui.theme.hue
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Activity for a picked day: steps against the goal, distance, calories, active time and PAI; steps hour by hour and
 * the day minute by minute; two weeks of steps and active time; workout captures and the watch's workouts.
 */
@Composable
fun ActivityScreen(controller: HeartlineController, nav: Navigator) {
    val c = HL.colors
    val today = remember { LocalDate.now() }
    var epochDay by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    val date = LocalDate.ofEpochDay(epochDay)
    val day by remember(epochDay) { controller.healthDay(date) }.collectAsState(initial = null)
    val live by controller.steps.collectAsStateWithLifecycle()
    val trends by controller.trends.collectAsStateWithLifecycle()
    val goal = stepGoal(controller)
    val dayStart = rememberDayStart(epochDay)
    val move = c.hue(Area.MOVE)

    ScrollPage {
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Gutter).padding(top = 14.dp)) {
            Text("Activity", style = HL.type.title, color = c.ink)
            Gap(12)
            DateStepper(if (date == today) "Today" else if (date == today.minusDays(1)) "Yesterday" else date.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)),
                date.format(DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH)), onPrev = { epochDay-- }, onNext = { epochDay++ }, nextEnabled = date < today)
        }
        Column(Modifier.padding(horizontal = Gutter)) {
            Gap(14)
            val d = day
            val steps = if (date == today) maxOf(live?.steps ?: 0, d?.stepsTotal ?: 0) else d?.stepsTotal ?: 0
            // ---- Steps against the goal ----
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ring(steps / goal.toFloat(), move, size = 132.dp, stroke = 12.dp, description = "$steps steps, ${steps * 100 / goal} percent of the $goal step goal") {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(fmtInt(steps), style = HL.type.stat.copy(fontSize = 30.sp), color = c.ink)
                            Text("of ${fmtInt(goal)}", style = HL.type.small.copy(fontSize = 12.sp), color = c.inkSoft)
                        }
                    }
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Fact("Distance", (if (date == today) live?.distanceM else null)?.let { "%.2f".format(it / 1000.0) } ?: d?.distanceM?.let { "%.2f".format(it / 1000.0) } ?: "–", unit = "km")
                        Fact("Calories", fmtInt(d?.caloriesKcal?.takeIf { it > 0 } ?: (if (date == today) live?.calories?.takeIf { it > 0 } else null)), unit = "kcal")
                        Fact("Active", hm(d?.activeMinutes))
                    }
                }
                Gap(12)
                val left = goal - steps
                MutedText(if (left <= 0) "Goal reached${if (steps > goal) ", ${fmtInt(steps - goal)} over" else ""}." else "${fmtInt(left)} steps to go, about ${(left / 100.0).roundToInt()} minutes of brisk walking.")
            }
            d?.paiTotal?.let { pai ->
                Gap(10)
                Card(padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            AreaLabel("PAI, last 7 days", c.pulse, dot = false)
                            BigValue("%.0f".format(pai), "/ 100", size = 28)
                        }
                        d.paiToday?.let { Fact("Today", "+%.0f".format(it), Modifier.padding(start = 12.dp)) }
                    }
                    Gap(8)
                    Meter((pai / 100).toFloat(), c.pulse, description = "PAI ${pai.roundToInt()} of 100")
                    Gap(6)
                    MutedText("Personal Activity Intelligence scores how hard your heart worked over a week. Keeping it at 100 or more is linked with lower heart risk.")
                }
            }

            // ---- The day ----
            if (d != null && d.stepsTotal > 0) {
                Heading("Steps hour by hour")
                Card(padding = 12.dp) {
                    val hourly = d.stepsByHour.mapNotNull { h -> h.total?.takeIf { it > 0 }?.let { SeriesPoint(dayStart + h.hour * 3600.0, it) } }
                    InteractiveChart(emptyList(), dayStart.toDouble(), dayStart + 86_400.0, height = 150.dp,
                        bars = listOf(ChartBars(hourly, 3600.0, move, "Steps", "", format = { "%,.0f".format(it) })),
                        readoutX = { t -> "%s–%s".format(epochClock(t - (t - dayStart) % 3600), epochClock(t - (t - dayStart) % 3600 + 3600)) },
                        minSpan = 3 * 3600.0, idleHint = "Touch an hour for its steps.", title = "Steps hour by hour")
                    hourly.maxByOrNull { it.v }?.let { top -> Gap(6); MutedText("Busiest hour: ${epochClock(top.t)}, ${fmtInt(top.v.roundToInt())} steps.") }
                }
            }
            if (d != null && d.minutes.isNotEmpty()) {
                Heading("Minute by minute")
                Card(padding = 12.dp) {
                    dev.heartline.ui.components.MinuteChart(d.minutes, dayStart, title = "Minute by minute, ${date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))}")
                    Gap(8)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("Deep" to MinuteState.SLEEP_DEEP, "Light" to MinuteState.SLEEP_LIGHT, "REM" to MinuteState.SLEEP_REM, "Active" to MinuteState.ACTIVE,
                            "Still" to MinuteState.IDLE, "Charging" to MinuteState.CHARGING).forEach { (l, s) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(stateColor(s, c) ?: Color.Transparent))
                                Spacer(Modifier.width(5.dp)); Text(l, style = HL.type.small.copy(fontSize = 12.sp), color = c.inkSoft)
                            }
                        }
                    }
                    Gap(6)
                    Row(Modifier.fillMaxWidth()) {
                        Fact("Worn", hm(d.wornMinutes), Modifier.weight(1f))
                        Fact("Heart rate", if (d.hrMin != null) "${d.hrMin}–${d.hrMax}" else "–", Modifier.weight(1f), unit = "bpm")
                        Fact("Average", fmtInt(d.hrAvg), Modifier.weight(1f), unit = "bpm")
                    }
                }
            }

            // ---- Two weeks ----
            trends.seriesOf("steps")?.let { s ->
                Heading("Last ${s.points.size} days")
                Card(padding = 14.dp) {
                    val vals = s.points.mapNotNull { it.value }
                    Row(Modifier.fillMaxWidth()) {
                        Fact("Average", fmtInt(vals.takeIf { it.isNotEmpty() }?.average()?.roundToInt()), Modifier.weight(1f), unit = "steps")
                        Fact("Goal met", "${vals.count { it >= goal }} of ${vals.size}", Modifier.weight(1f), unit = "days")
                        Fact("Best", fmtInt(vals.maxOrNull()?.roundToInt()), Modifier.weight(1f))
                    }
                    Gap(10)
                    DayBars(s.points, move, reference = goal.toDouble(), referenceLabel = "goal, ${fmtInt(goal)} steps", unit = "steps")
                    TrendExtras(s, showAbout = false)
                }
            }
            trends.seriesOf("active")?.let { s ->
                Gap(10)
                Card(padding = 14.dp) {
                    AreaLabel("Active time", c.pulse, dot = false)
                    Gap(4)
                    DayBars(s.points, c.pulse, height = 110.dp, unit = "min", reference = 30.0, referenceLabel = "30 min a day")
                    Gap(6)
                    MutedText("Health guidelines suggest at least 150 minutes of moderate activity a week, about 30 minutes on most days.")
                    TrendExtras(s, showAbout = false)
                }
            }
            trends.seriesOf("strain")?.takeIf { s -> s.points.any { it.value != null } }?.let { s ->
                Gap(10)
                Card(padding = 14.dp, onClick = { nav.push(Route.Readiness("strain")) }, onClickLabel = "How strain is worked out") {
                    AreaLabel("Strain", c.pulse, dot = false)
                    Gap(4)
                    DayBars(s.points, c.pulse, height = 110.dp, unit = "", reference = 14.0, referenceLabel = "14, a hard day", format = { "%.1f".format(it) })
                    TrendExtras(s)
                }
            }
            trends.seriesOf("pai")?.takeIf { s -> s.points.any { it.value != null } }?.let { s ->
                Gap(10)
                Card(padding = 14.dp) {
                    AreaLabel("PAI", move, dot = false)
                    Gap(4)
                    DayBars(s.points, move, height = 110.dp, unit = "")
                    TrendExtras(s)
                }
            }

            // ---- Workouts ----
            Gap(26)
            CapturePanel(controller, nav)
            Heading("Watch workouts")
            if (d == null || d.workouts.isEmpty()) MutedText("No watch workouts this day. Start one on the watch and it appears here after a sync.")
            d?.workouts?.forEach { w ->
                Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Open workout", role = Role.Button) { nav.push(Route.Workout(w.start, epochDay)) }
                    .semantics(mergeDescendants = true) {}.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(w.type, style = HL.type.bodyStrong, color = c.ink)
                        MutedText("${timeOf(w.start)}, ${w.durationS / 60} min" + (w.calories?.let { ", $it kcal" } ?: "") + (w.distanceM?.let { ", %.2f km".format(it / 1000.0) } ?: ""))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${fmtInt(w.avgHr)} bpm", style = HL.type.stat.copy(fontSize = 20.sp), color = c.ink)
                        MutedText("max ${fmtInt(w.maxHr)}")
                    }
                }
                HorizontalDivider(color = c.line)
            }
            Gap(16)
            Note("Steps and active minutes come from the watch's minute-by-minute history; syncing reads it without deleting anything from the watch.")
            Gap(28)
        }
    }
}
