package dev.heartline.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.sp
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
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface).padding(12.dp)) {
            val dayLabel = { t: Double -> Instant.ofEpochSecond(t.toLong()).atZone(zone).format(shortDay) }
            val dayLong = { t: Double -> Instant.ofEpochSecond(t.toLong()).atZone(zone).format(longDay) }
            val baseline = s.baseline?.let { b -> listOf(ChartLine(listOf(SeriesPoint(start, b), SeriesPoint(end, b)), c.inkSoft, "28-day baseline", s.unit, width = 1.dp, dashed = true, gap = 1e12, format = ::fmt)) } ?: emptyList()
            if (s.bars) InteractiveChart(baseline, start, end, height = 220.dp, bars = listOf(ChartBars(pts, 86_400.0, if (s.accent) c.pulse else c.ink, s.title, s.unit, format = ::fmt)),
                xLabel = dayLabel, readoutX = dayLong, minSpan = 3 * 86_400.0, idleHint = "Touch a day for its value.")
            else InteractiveChart(listOf(ChartLine(pts.map { SeriesPoint(it.t + 43_200, it.v) }, if (s.accent) c.pulse else c.ink, s.title, s.unit, gap = 8 * 86_400.0, spacing = 86_400.0, format = ::fmt)) + baseline,
                start, end, height = 220.dp, xLabel = dayLabel, readoutX = { dayLong(it - 43_200) }, minSpan = 3 * 86_400.0, idleHint = "Touch a day for its value.")
        }
        trendAbout(key)?.let { a ->
            Gap(24); SectionTitle("About ${s.title.lowercase().replaceFirstChar { it.uppercase() }}"); Gap(8)
            dev.heartline.ui.components.Card(padding = 16.dp) {
                Text(a.what, style = HL.type.body.copy(fontSize = 15.sp, lineHeight = 22.sp), color = c.ink)
                Gap(8); Text("How it's worked out", style = HL.type.label, color = c.ink); MutedText(a.how)
                Gap(8); Text("Typical", style = HL.type.label, color = c.ink); MutedText(a.typical)
                if (a.readiness != null) {
                    Gap(10)
                    Text("How it feeds readiness ›", style = HL.type.label, color = c.pulse,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = androidx.compose.ui.semantics.Role.Button) { nav.push(dev.heartline.ui.Route.Readiness(a.readiness)) }.padding(vertical = 6.dp))
                }
            }
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

/** What a trend is, how Heartline works it out, the usual range, and which readiness part it feeds (a focus for Route.Readiness). */
private class About(val what: String, val how: String, val typical: String, val readiness: String? = null)

private fun trendAbout(key: String): About? = when (key) {
    "readiness" -> About("How ready your body is for effort today, from 0 to 100.",
        "70 plus or minus points from HRV, resting heart rate, last night's sleep and yesterday's strain, each compared with your own last 28 days.",
        "Around 70 is an ordinary day for you. 80 and up: go hard if you like. Under 50: rest. A run of low days matters more than one.", "")
    "rhr" -> About("Your lowest steady heart rate each day, usually reached during sleep.",
        "The watch's own resting value when it gives one; otherwise the lowest 30-minute median of your heart rate while asleep.",
        "60–100 bpm for most adults; 40s–50s is common in fit people. A rise of 5 or more above your baseline for a few days often means poor recovery or a coming illness.", "Resting heart rate")
    "hrv" -> About("Heart rate variability (RMSSD): how much the gap between beats changes. Higher usually means better recovered.",
        "From Heartline's heart checks with at least 70% clean signal and 45 s of beats; the median when there are several in a day. The GTR 2e answers requests for its own HRV record with no data.",
        "About 19–75 ms in short resting readings from healthy adults; it falls with age and varies hugely between people. Compare with your own baseline only.", "HRV")
    "sleep" -> About("Hours asleep each night, light, deep and REM together.",
        "The watch classifies every minute; Heartline joins asleep minutes into a night, bridging awake gaps of up to 30 minutes.",
        "7–9 hours for adults. Consistently under 6 is linked with worse recovery, mood and health.", "sleep_score")
    "sleep_score" -> About("A 0–100 grade for each night.",
        "Up to 50 points for time asleep (full at 8 h), 20 for efficiency (95% of time in bed asleep), 15 for deep sleep (13% of sleep) and 15 for REM (20%).",
        "70 is a decent night; 85+ is excellent.", "sleep_score")
    "strain" -> About("How hard your heart worked each day, from 0 to 21.",
        "Every awake minute above half your maximum heart rate adds load, more steeply near your maximum; the total is squeezed onto 0–21.",
        "Under 8 is a light day, 14+ a hard one. Above 14 lowers the next day's readiness.", "strain")
    "steps" -> About("Steps counted by the watch each day.", "The watch's own step counter, synced minute by minute.",
        "7,000–10,000 a day is a common healthy target; benefits rise steeply from 2,000 to about 8,000.")
    "active" -> About("Minutes each day with steps or active movement.", "Minutes the watch marked active, or with any steps.",
        "The WHO suggests 150–300 minutes of moderate activity a week.")
    "spo2" -> About("Blood oxygen saturation: how much of your blood's haemoglobin is carrying oxygen.",
        "The watch's red and infrared light readings, including the overnight ones when sleep breathing quality is on.",
        "95–100% is normal at sea level. Brief overnight dips are common; frequent dips below 90% are worth asking a doctor about.")
    "stress" -> About("The watch's all-day stress score, from 0 to 100.", "The watch's own estimate from heart rate variability while you are still.",
        "Under 40 relaxed, 40–59 normal, 60–79 medium, 80+ high.")
    "pai" -> About("Personal Activity Intelligence: a weekly score of heart-rate-raising activity.", "The watch's own PAI, summed over the last 7 days.",
        "Keeping 100 or more over a week is linked with lower heart disease risk in the HUNT study.")
    "temp" -> About("Skin temperature at the wrist.", "The watch's skin sensor, averaged over the day.",
        "Wrist skin runs a few degrees below core temperature and moves with the room. A night well above your usual can come with illness.")
    else -> null
}
