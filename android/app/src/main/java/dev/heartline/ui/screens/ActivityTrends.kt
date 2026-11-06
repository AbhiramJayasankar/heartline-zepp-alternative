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
fun TrendsScreen(controller: HeartlineController, nav: Navigator, onBack: (() -> Unit)? = null) {
    val c = HL.colors
    val trends by controller.trends.collectAsStateWithLifecycle()
    val t = trends
    Screen("Trends", onBack = onBack, subtitle = "Two weeks of your numbers. Dashed lines are your 28-day baseline. Tap any card for every day.") {
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
                    Panel(Modifier.weight(1f).clickable { nav.push(Route.Trend(s.key)) }, padding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
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
