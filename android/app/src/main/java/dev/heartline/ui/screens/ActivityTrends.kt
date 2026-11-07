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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
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
fun TrendsScreen(controller: HeartlineController, nav: Navigator, onBack: (() -> Unit)? = null) {
    val c = HL.colors
    val trends by controller.trends.collectAsStateWithLifecycle()
    val t = trends
    Screen("Trends", onBack = onBack, subtitle = "Two weeks of the numbers without a page of their own, against your 28-day usual level. Tap a card for every day.") {
        if (t == null) { MutedText("Trends appear after the first sync."); return@Screen }
        dev.heartline.ui.components.Card(padding = 14.dp) {
            dev.heartline.ui.components.FactGrid(listOf(
                dev.heartline.ui.components.FactItem("VO2max", fmtOne(t.vo2max), note = "ml/kg/min"),
                dev.heartline.ui.components.FactItem("Fitness age", fmtInt(t.fitnessAge), note = "from VO2max"),
                dev.heartline.ui.components.FactItem("Sleep regularity", fmtInt(t.sleepRegularity), "/100"),
                dev.heartline.ui.components.FactItem("Load, 7 days", fmtInt(t.load7d?.roundToInt())),
                dev.heartline.ui.components.FactItem("Acute : chronic", t.acwr?.let { "%.2f".format(it) } ?: "–", note = t.acwr?.let { when { it > 1.5 -> "ramping fast"; it < 0.8 -> "easing off"; else -> "steady" } }),
            ))
        }
        Gap(14)
        // Sleep, heart, activity and readiness trends live on their own tabs and pages now.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Sleep" to "sleep", "Heart" to "rhr", "Activity" to "steps", "Readiness" to "readiness").forEach { (label, key) ->
                dev.heartline.ui.components.PillButton(label, { openTrend(nav, key) }, style = dev.heartline.ui.components.ButtonStyle.QUIET)
            }
        }
        Gap(6)
        MutedText("Sleep, heart, activity and readiness trends are on their own pages. Below: everything that doesn't have one.")
        Gap(12)
        t.series.filter { it.key !in setOf("sleep", "sleep_score", "hrv_sleep", "rhr", "awake_rhr", "hrv", "steps", "strain", "active", "pai", "readiness") }.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { s -> TrendCard(s, Modifier.weight(1f).fillMaxHeight()) { nav.push(Route.Trend(s.key)) } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
            Gap(10)
        }
    }
}

/** Lower is better for these, higher for [HIGHER_BETTER]; the rest are shown without a judgement. */
private val LOWER_BETTER = setOf("rhr", "stress")
private val HIGHER_BETTER = setOf("readiness", "hrv", "sleep", "sleep_score")
/** Totals that build up through the day: today's partial value isn't compared, the last whole day is. */
private val DAY_TOTALS = setOf("steps", "active", "strain", "pai")
/** Shown as a difference in their own unit rather than a percentage. */
private val ABSOLUTE = setOf("temp", "spo2", "rhr")

@Composable
private fun TrendCard(s: dev.heartline.app.TrendSeries, modifier: Modifier, onClick: () -> Unit) {
    val c = HL.colors
    val today = java.time.LocalDate.now()
    val last = s.points.lastOrNull { it.value != null && !(s.key in DAY_TOTALS && it.date == today) } ?: s.points.lastOrNull { it.value != null }
    val ref = s.baseline ?: s.points.filter { it.date != last?.date && it.date != today }.mapNotNull { it.value }.takeIf { it.size >= 3 }?.average()
    fun fmt(v: Double) = when { s.key == "sleep" -> "%.1f".format(v); v >= 100 || v % 1.0 == 0.0 -> fmtInt(v.roundToInt()); else -> fmtOne(v) }
    dev.heartline.ui.components.Card(modifier, onClick = onClick, onClickLabel = "Open ${s.title}", padding = 12.dp) {
        Text(s.title, style = HL.type.label, color = c.inkSoft, maxLines = 1)
        dev.heartline.ui.components.BigValue(last?.value?.let { fmt(it) } ?: "–", s.unit.ifEmpty { null }, size = 24)
        if (last != null && last.date != today) Text(if (last.date == today.minusDays(1)) "Yesterday" else dev.heartline.ui.components.shortDate(last.date), style = HL.type.small.copy(fontSize = 11.sp), color = c.inkSoft)
        if (last?.value != null && ref != null && ref != 0.0) {
            val diff = last.value!! - ref
            val pct = diff / ref * 100
            val near = kotlin.math.abs(pct) < 3
            val text = when {
                near -> "near usual"
                s.key in ABSOLUTE -> "${if (diff > 0) "▲" else "▼"} ${if (s.key == "temp") "%.1f".format(kotlin.math.abs(diff)) else kotlin.math.abs(diff).roundToInt().toString()} ${s.unit} vs usual"
                else -> "${if (pct > 0) "▲" else "▼"} ${kotlin.math.abs(pct).roundToInt()}% vs usual"
            }
            val good = when { near -> null; s.key in LOWER_BETTER -> diff < 0; s.key in HIGHER_BETTER -> diff > 0; else -> null }
            Gap(4)
            dev.heartline.ui.components.Delta(text, good)
        }
        Gap(6)
        MiniTrend(s)
    }
}
