package dev.heartline.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heartline.app.HeartlineController
import dev.heartline.app.SeriesPoint
import dev.heartline.ui.Navigator
import dev.heartline.ui.components.ChartBars
import dev.heartline.ui.components.ChartLine
import dev.heartline.ui.components.InteractiveChart
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.SectionTitle
import dev.heartline.ui.components.Stat
import dev.heartline.ui.components.paper
import dev.heartline.ui.theme.HL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val shortDay = DateTimeFormatter.ofPattern("d MMM")
private val longDay = DateTimeFormatter.ofPattern("EEEE d MMMM")

/** One trend in full: every day's value, touchable, against the 28-day baseline. */
@Composable
fun TrendDetailScreen(controller: HeartlineController, key: String, nav: Navigator) {
    val c = HL.colors
    val trends by controller.trends.collectAsStateWithLifecycle()
    val s = trends?.series?.firstOrNull { it.key == key }
    Screen(s?.title ?: "Trend", onBack = { nav.pop() }, subtitle = s?.let { "Daily values over ${it.points.size} days" + (it.baseline?.let { b -> ", 28-day baseline ${fmt(b)} ${it.unit}" } ?: "") }) {
        if (s == null) { MutedText("No data yet."); return@Screen }
        val zone = ZoneId.systemDefault()
        val pts = s.points.mapNotNull { p -> p.value?.let { SeriesPoint(p.date.atStartOfDay(zone).toEpochSecond().toDouble(), it) } }
        if (pts.isEmpty()) { MutedText("No values in this period."); return@Screen }
        val values = pts.map { it.v }
        val start = s.points.first().date.atStartOfDay(zone).toEpochSecond().toDouble()
        val end = s.points.last().date.plusDays(1).atStartOfDay(zone).toEpochSecond().toDouble()
        Row(Modifier.fillMaxWidth()) {
            Stat("Latest", fmt(values.last()), Modifier.weight(1f), unit = s.unit)
            Stat("Average", fmt(values.average()), Modifier.weight(1f), unit = s.unit)
            Stat("Range", "${fmt(values.min())}–${fmt(values.max())}", Modifier.weight(1f))
        }
        Gap(18)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface).paper().padding(12.dp)) {
            val dayLabel = { t: Double -> Instant.ofEpochSecond(t.toLong()).atZone(zone).format(shortDay) }
            val dayLong = { t: Double -> Instant.ofEpochSecond(t.toLong()).atZone(zone).format(longDay) }
            val baseline = s.baseline?.let { b -> listOf(ChartLine(listOf(SeriesPoint(start, b), SeriesPoint(end, b)), c.inkSoft, "28-day baseline", s.unit, width = 1.dp, dashed = true, gap = 1e12, format = ::fmt)) } ?: emptyList()
            if (s.bars) InteractiveChart(baseline, start, end, height = 220.dp, bars = listOf(ChartBars(pts, 86_400.0, if (s.accent) c.pulse else c.ink, s.title, s.unit, format = ::fmt)),
                xLabel = dayLabel, readoutX = dayLong, minSpan = 3 * 86_400.0, idleHint = "Touch a day for its value.")
            else InteractiveChart(listOf(ChartLine(pts.map { SeriesPoint(it.t + 43_200, it.v) }, if (s.accent) c.pulse else c.ink, s.title, s.unit, gap = 2 * 86_400.0, format = ::fmt)) + baseline,
                start, end, height = 220.dp, xLabel = dayLabel, readoutX = { dayLong(it - 43_200) }, minSpan = 3 * 86_400.0, idleHint = "Touch a day for its value.")
        }
        Gap(24); SectionTitle("Every day"); Gap(4)
        s.points.reversed().forEach { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                Text(p.date.format(longDay), style = HL.type.body, color = c.ink, modifier = Modifier.weight(1f))
                Text(p.value?.let { fmt(it) + if (s.unit.isNotEmpty()) " ${s.unit}" else "" } ?: "–", style = HL.type.bodyStrong, color = c.ink)
                s.baseline?.let { b -> p.value?.let { v -> Spacer(Modifier.padding(start = 10.dp)); Text("%+.0f%%".format((v - b) / b * 100), style = HL.type.small, color = c.inkSoft) } }
            }
            HorizontalDivider(color = c.line)
        }
    }
}

private fun fmt(v: Double): String = if (v >= 100 || v % 1.0 == 0.0) "%,.0f".format(v) else "%.1f".format(v)
