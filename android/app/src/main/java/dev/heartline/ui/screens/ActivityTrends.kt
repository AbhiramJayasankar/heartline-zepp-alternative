package dev.heartline.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heartline.app.HeartlineController
import dev.heartline.app.MinuteState
import dev.heartline.ui.Navigator
import dev.heartline.ui.Route
import dev.heartline.ui.components.DayTimeline
import dev.heartline.ui.components.HrTraceChart
import dev.heartline.ui.components.MiniTrend
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.Panel
import dev.heartline.ui.components.SectionTitle
import dev.heartline.ui.components.StagesBar
import dev.heartline.ui.components.Stat
import dev.heartline.ui.components.fmtHm
import dev.heartline.ui.components.fmtInt
import dev.heartline.ui.components.fmtOne
import dev.heartline.ui.components.rememberDayStart
import dev.heartline.ui.components.stateColor
import dev.heartline.ui.theme.HL
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

@Composable
fun ActivityScreen(controller: HeartlineController, nav: Navigator) {
    val c = HL.colors
    val today = remember { LocalDate.now(ZoneId.systemDefault()) }
    var epochDay by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    val date = LocalDate.ofEpochDay(epochDay)
    val day by remember(epochDay) { controller.activityDay(date) }.collectAsState(initial = null)
    val dayStart = rememberDayStart(epochDay)

    Screen("Activity", subtitle = "Minute by minute from the watch. Syncing reads the history without deleting it.") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (0..6).forEach { back ->
                val d = today.minusDays(back.toLong())
                val on = d.toEpochDay() == epochDay
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(if (on) c.ink else c.surface)
                        .border(1.dp, if (on) c.ink else c.line, RoundedCornerShape(50))
                        .clickable { epochDay = d.toEpochDay() }.padding(horizontal = 14.dp, vertical = 8.dp),
                ) { Text(dayLabel(d), style = HL.type.label, color = if (on) c.paper else c.inkSoft) }
            }
        }
        Gap(20)
        val d = day
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("Steps", fmtInt(d?.steps))
            Stat("Active", fmtHm(d?.activeMinutes))
            Stat("Sleep", fmtHm(d?.sleepMinutes))
            Stat("Worn", fmtHm(d?.wornMinutes))
        }
        Gap(14)
        Stat("Heart rate", if (d?.hrMin != null) "${d.hrMin}–${d.hrMax}" else "–", unit = d?.hrAvg?.let { "avg $it" })
        Gap(16)
        Panel(padding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            if (d == null || d.minutes.isEmpty()) MutedText("No history for this day yet. Connect and sync from the Today tab.")
            else DayTimeline(d.minutes, dayStart)
        }
        Gap(10)
        Legend()
        d?.sleep?.let { n ->
            Gap(24)
            SectionTitle("Sleep")
            Gap(10)
            StagesBar(n)
            Gap(8)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Deep", fmtHm(n.deepMin)); Stat("Light", fmtHm(n.lightMin)); Stat("REM", fmtHm(n.remMin)); Stat("Awake", fmtHm(n.awakeMin))
            }
            Gap(6)
            MutedText("${timeOf(n.start)} to ${timeOf(n.end)}. REM is estimated from the watch's sleep fields.")
        }
        Gap(24)
        CapturePanel(controller, nav)
        Gap(24)
        SectionTitle("Workouts")
        Gap(4)
        if (d == null || d.workouts.isEmpty()) MutedText("No workouts this day.")
        d?.workouts?.forEach { w ->
            Row(Modifier.fillMaxWidth().clickable { nav.push(Route.Workout(w.start, epochDay)) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(w.type, style = HL.type.bodyStrong, color = c.ink)
                    MutedText("${timeOf(w.start)}, ${w.durationS / 60} min" + (w.calories?.let { ", $it kcal" } ?: ""))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${fmtInt(w.avgHr)} bpm", style = HL.type.stat.copy(fontSize = 20.sp), color = c.ink)
                    MutedText("max ${fmtInt(w.maxHr)}")
                }
            }
            HorizontalDivider(color = c.line)
        }
    }
}

@Composable
private fun Legend() {
    val c = HL.colors
    val items = listOf(
        "Deep" to stateColor(MinuteState.SLEEP_DEEP, c), "Light" to stateColor(MinuteState.SLEEP_LIGHT, c),
        "REM" to stateColor(MinuteState.SLEEP_REM, c), "Active" to stateColor(MinuteState.ACTIVE, c),
        "Still" to stateColor(MinuteState.IDLE, c), "Charging" to stateColor(MinuteState.CHARGING, c), "Not worn" to c.line,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color ?: Color.Transparent))
                Spacer(Modifier.width(5.dp))
                Text(label, style = HL.type.small, color = c.inkSoft)
            }
        }
    }
}

@Composable
fun TrendsScreen(controller: HeartlineController) {
    val c = HL.colors
    val trends by controller.trends.collectAsStateWithLifecycle()
    val t = trends
    Screen("Trends", subtitle = "Two weeks of your numbers. Dashed lines are your 28-day baseline.") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("VO2max", fmtOne(t?.vo2max)); Stat("Fitness age", fmtInt(t?.fitnessAge)); Stat("Sleep regularity", fmtInt(t?.sleepRegularity), unit = "/100")
        }
        Gap(12)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Stat("Load, 7 days", fmtInt(t?.load7d?.roundToInt())); Stat("Acute : chronic", t?.acwr?.let { "%.2f".format(it) } ?: "–")
        }
        Gap(20)
        if (t == null) { MutedText("Trends appear after the first sync."); return@Screen }
        t.series.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { s ->
                    val last = s.points.lastOrNull { it.value != null }?.value
                    Panel(Modifier.weight(1f), padding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                        Text(s.title, style = HL.type.label, color = c.inkSoft)
                        Text(
                            when {
                                last == null -> "–"
                                s.key == "sleep" -> "%.1f".format(last)
                                last >= 100 || last % 1.0 == 0.0 -> fmtInt(last.roundToInt())
                                else -> fmtOne(last)
                            } + if (last != null && s.unit.isNotEmpty()) " ${s.unit}" else "",
                            style = HL.type.stat.copy(fontSize = 22.sp), color = c.ink,
                        )
                        Gap(6)
                        MiniTrend(s)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
            Gap(10)
        }
    }
}
