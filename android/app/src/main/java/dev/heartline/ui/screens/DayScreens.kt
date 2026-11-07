package dev.heartline.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import kotlinx.coroutines.delay
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.heartline.app.BandTimeUi
import dev.heartline.app.HealthDayUi
import dev.heartline.app.HeartlineController
import dev.heartline.app.HourStatUi
import dev.heartline.app.MinuteState
import dev.heartline.app.SeriesPoint
import dev.heartline.ui.Navigator
import dev.heartline.ui.Route
import dev.heartline.ui.components.ChartBand
import dev.heartline.ui.components.ChartBars
import dev.heartline.ui.components.ChartLine
import dev.heartline.ui.components.ChartSpan
import dev.heartline.ui.components.ChartRange
import dev.heartline.ui.components.InteractiveChart
import dev.heartline.ui.components.LegendDot
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.Panel
import dev.heartline.ui.components.SectionTitle
import dev.heartline.ui.components.Stat
import dev.heartline.ui.components.ZoneBars
import dev.heartline.ui.components.fmtHm
import dev.heartline.ui.components.fmtInt
import dev.heartline.ui.components.paper
import dev.heartline.ui.theme.HL
import dev.heartline.ui.theme.Palette
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

enum class DayMetric(val title: String) { HEART("Heart rate"), STRESS("Stress"), BODY("SpO2 and temperature") }

private val dayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM")

/** Zepp's stress bands, coloured from calm blue to red. */
fun stressColor(name: String, c: Palette): Color = when (name) { "Relaxed" -> c.sleepLight; "Normal" -> c.ok; "Medium" -> c.warn; else -> c.pulse }
fun spo2Color(name: String, c: Palette): Color = when (name) { "Normal" -> c.ok; "Slightly low" -> c.warn; else -> c.pulse }

/** The three day pages share one scaffold: the metric's title, the date with previous/next, then the content. */
@Composable
fun DayScreen(controller: HeartlineController, metric: DayMetric, epochDay: Long, nav: Navigator) {
    val date = LocalDate.ofEpochDay(epochDay)
    val today = LocalDate.now()
    val d by remember(epochDay) { controller.healthDay(date) }.collectAsState(initial = null)
    val trends by controller.trends.collectAsState()
    fun series(key: String) = trends?.series?.firstOrNull { it.key == key }?.takeIf { s -> s.points.any { it.value != null } }
    // Its own scroll, shared through LocalTabScroll so a link's focus (a Trends card) can scroll to a section.
    val scroll = androidx.compose.foundation.rememberScrollState()
    androidx.compose.runtime.CompositionLocalProvider(LocalTabScroll provides scroll) {
    Screen(metric.title, onBack = { nav.pop() }, scroll = scroll) {
        DaySwitcher(date, today, onPick = { nav.replace(dayRoute(metric, it.toEpochDay())) })
        Gap(12)
        val day = d
        if (day == null) { MutedText("Loading…"); return@Screen }
        when (metric) {
            DayMetric.HEART -> HeartDay(day, nav)
            DayMetric.STRESS -> { StressDay(day); series("stress")?.let { TwoWeeks(it, nav, HL.colors.warn) } }
            DayMetric.BODY -> BodyDay(day, live = date == today, onRefresh = controller::refreshTemperature, nav = nav, spo2 = series("spo2"), temp = series("temp"))
        }
        Gap(28)
        // Jump sideways between the metrics for the same day.
        DayMetric.entries.filter { it != metric }.forEach { m -> NavRow(m.title, "Same day") { nav.replace(dayRoute(m, epochDay)) } }
    }
    }
}

/** A metric's last two weeks under its day: one value a day against your usual, then latest, average, range and every day. */
@Composable
private fun TwoWeeks(s: dev.heartline.app.TrendSeries, nav: Navigator, color: Color) {
    Gap(20)
    dev.heartline.ui.components.Card(padding = 14.dp) {
        Text("${s.title}, last ${s.points.size} days", style = HL.type.label, color = HL.colors.inkSoft)
        Gap(6)
        val oneDecimal = s.key == "temp"
        dev.heartline.ui.components.DayBars(s.points, color, line = true, height = 110.dp, reference = s.baseline,
            referenceLabel = s.baseline?.let { "your usual, ${if (oneDecimal) "%.1f".format(it) else it.roundToInt().toString()} ${s.unit}".trim() },
            unit = s.unit, format = { if (oneDecimal) "%.1f".format(it) else it.roundToInt().toString() })
        TrendExtras(s)
    }
}

fun dayRoute(metric: DayMetric, epochDay: Long): Route = Route.Day(metric.name, epochDay)

@Composable
private fun DaySwitcher(date: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit) {
    dev.heartline.ui.components.DateStepper(if (date == today) "Today" else if (date == today.minusDays(1)) "Yesterday" else date.format(DateTimeFormatter.ofPattern("EEEE")),
        date.format(DateTimeFormatter.ofPattern("d MMMM")), onPrev = { onPick(date.minusDays(1)) }, onNext = { onPick(date.plusDays(1)) }, nextEnabled = date < today)
}

/** Three stats per row. */
@Composable
private fun Stats(items: List<Triple<String, String, String?>>) {
    items.filter { it.second != "–" }.chunked(3).forEachIndexed { i, row ->
        if (i > 0) Gap(14)
        Row(Modifier.fillMaxWidth()) {
            row.forEach { (label, value, unit) -> Stat(label, value, Modifier.weight(1f), unit = unit) }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun ChartPanel(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(HL.colors.surface).border(1.dp, HL.colors.line, RoundedCornerShape(18.dp)).padding(12.dp)) {
        Text(title, style = HL.type.label, color = HL.colors.inkSoft); Gap(6)
        androidx.compose.runtime.CompositionLocalProvider(dev.heartline.ui.components.LocalChartTitle provides title) { content() }
    }
}

private fun sleepAndWorkoutSpans(d: HealthDayUi, c: Palette): List<ChartSpan> {
    val spans = ArrayList<ChartSpan>()
    // Sleep: contiguous runs of sleep minutes.
    var runStart: Long? = null
    d.minutes.forEach { m ->
        val asleep = m.state == MinuteState.SLEEP_DEEP || m.state == MinuteState.SLEEP_LIGHT || m.state == MinuteState.SLEEP_REM
        if (asleep && runStart == null) runStart = m.ts
        if (!asleep && runStart != null) { spans += ChartSpan(runStart!!.toDouble(), m.ts.toDouble(), c.sleepDeep.copy(alpha = 0.10f), "Asleep"); runStart = null }
    }
    runStart?.let { spans += ChartSpan(it.toDouble(), d.minutes.last().ts + 60.0, c.sleepDeep.copy(alpha = 0.10f), "Asleep") }
    d.workouts.forEach { w -> spans += ChartSpan(w.start.toDouble(), (w.start + w.durationS).toDouble(), c.warn.copy(alpha = 0.18f), w.type) }
    return spans
}

/** Hourly range as bars of the average, with the hour's min and max in the readout. */
@Composable
private fun HourChart(hours: List<HourStatUi>, dayStart: Long, color: Color, label: String, unit: String, sum: Boolean = false) {
    val c = HL.colors
    val bars = hours.mapNotNull { h -> (if (sum) h.total?.takeIf { it > 0 } else h.avg)?.let { SeriesPoint(dayStart + h.hour * 3600.0, it) } }
    val lines = if (sum) emptyList() else listOf(
        ChartLine(hours.mapNotNull { h -> h.max?.let { SeriesPoint(dayStart + h.hour * 3600.0 + 1800, it) } }, c.ink.copy(alpha = 0.5f), "Highest", unit, width = 0.dp, gap = 1900.0),
        ChartLine(hours.mapNotNull { h -> h.min?.let { SeriesPoint(dayStart + h.hour * 3600.0 + 1800, it) } }, c.inkSoft.copy(alpha = 0.5f), "Lowest", unit, width = 0.dp, gap = 1900.0),
    )
    // Averages start near the day's lowest reading, not zero, so differences between hours stay visible.
    val floor = if (sum) null else hours.mapNotNull { it.min }.minOrNull()?.let { (it - 5).coerceAtLeast(0.0) }
    val ranges = if (sum) emptyList() else hours.mapNotNull { h -> if (h.min != null && h.max != null && h.max!! > h.min!!) ChartRange(dayStart + h.hour * 3600.0 + 1800, h.min!!, h.max!!, c.ink.copy(alpha = 0.55f)) else null }
    InteractiveChart(lines, dayStart.toDouble(), dayStart + 86_400.0, height = 170.dp, yMin = floor, bars = listOf(ChartBars(bars, 3600.0, color.copy(alpha = 0.8f), label, unit)), ranges = ranges,
        readoutX = { t -> "%s–%s".format(dev.heartline.ui.components.epochClock(t - (t - dayStart) % 3600), dev.heartline.ui.components.epochClock(t - (t - dayStart) % 3600 + 3600)) },
        minSpan = 3 * 3600.0, idleHint = "Touch an hour for its range.")
}

@Composable
private fun BandBar(bands: List<BandTimeUi>, color: (String) -> Color) {
    val c = HL.colors
    val total = bands.sumOf { it.minutes }.coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp)).background(c.line)) {
        bands.filter { it.minutes > 0 }.forEach { b -> Box(Modifier.weight(b.minutes.toFloat()).fillMaxHeight().background(color(b.name))) }
    }
    Gap(10)
    bands.forEach { b ->
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            LegendDot(color(b.name), b.name)
            MutedText("  ${b.low}–${b.high}", Modifier.weight(1f))
            Text(fmtHm(b.minutes), style = HL.type.bodyStrong, color = c.ink)
            Text("  ${100 * b.minutes / total}%", style = HL.type.small, color = c.inkSoft, modifier = Modifier.width(48.dp))
        }
    }
}

// ---- Heart rate ----

@Composable
internal fun HeartDay(d: HealthDayUi, nav: Navigator) {
    val c = HL.colors
    if (d.hr.isEmpty()) { MutedText("No heart rate recorded this day."); return }
    Stats(listOf(
        Triple("Sleeping", fmtInt(d.restingHr), "bpm"), Triple("Awake resting", fmtInt(d.awakeRestingHr), "bpm"), Triple("Average", fmtInt(d.hrAvg), "bpm"),
        Triple("Range", "${fmtInt(d.hrMin)}–${fmtInt(d.hrMax)}", "bpm"), Triple("Readings", fmtInt(d.hr.size), null), Triple("High HR", if (d.hrHighMinutes > 0) fmtHm(d.hrHighMinutes) else "0", "outside workouts"),
    ))
    Gap(6)
    MutedText("Sleeping: your calmest half hour asleep (lowest 30-minute median). Awake resting: your calmest 10 still, awake minutes. " +
        "Sleeping is usually a few beats lower.")
    Gap(18)
    ChartPanel("Heart rate through the day") {
        InteractiveChart(
            listOf(ChartLine(d.hr, c.pulse, "Heart rate", "bpm", fill = true, gap = 900.0)),
            d.dayStart.toDouble(), d.dayStart + 86_400.0, height = 220.dp,
            spans = sleepAndWorkoutSpans(d, c),
            bands = d.hrZones.filter { it.zone >= 3 }.map { z -> ChartBand(z.lowBpm.toDouble(), (z.highBpm ?: 240).toDouble(), dev.heartline.ui.components.zoneColor(z.zone, c).copy(alpha = 0.06f)) },
            summary = { MutedText("Shaded: asleep (blue) and workouts (amber). Faint bands are zones 3 to 5. Touch for exact values; pinch to zoom.") },
        )
        Gap(8)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendDot(c.sleepDeep.copy(alpha = 0.4f), "Asleep"); LegendDot(c.warn.copy(alpha = 0.5f), "Workout")
        }
    }
    Gap(24); SectionTitle("Time in each zone"); Gap(4)
    MutedText("Zones are 50–90% of your maximum heart rate (set in your profile, or 220 minus your age).")
    Gap(12); ZoneBars(d.hrZones)
    Gap(24); SectionTitle("Hour by hour"); Gap(4)
    MutedText("Bars are each hour's average; the thin line through each bar runs from its lowest to its highest reading.")
    Gap(8)
    ChartPanel("Average heart rate per hour") { HourChart(d.hrByHour, d.dayStart, c.pulse, "Average", "bpm") }
    if (d.workouts.isNotEmpty()) {
        Gap(24); SectionTitle("Workouts"); Gap(4)
        d.workouts.forEach { w ->
            Row(Modifier.fillMaxWidth().clickable { nav.push(Route.Workout(w.start, d.date.toEpochDay())) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(w.type, style = HL.type.bodyStrong, color = c.ink)
                    MutedText("${dev.heartline.ui.components.epochClock(w.start.toDouble())}, ${fmtHm(w.durationS / 60)}")
                }
                Text("${fmtInt(w.avgHr)} avg · ${fmtInt(w.maxHr)} max", style = HL.type.label, color = c.ink)
            }
            HorizontalDivider(color = c.line)
        }
    }
}

// ---- Stress ----

@Composable
private fun StressDay(d: HealthDayUi) {
    val c = HL.colors
    if (d.stress.isEmpty()) {
        MutedText("No stress readings this day. The watch measures stress only while \"All-day stress\" is on (Watch settings) and you are still.")
        return
    }
    val relaxed = d.stressBands.firstOrNull { it.name == "Relaxed" }?.minutes ?: 0
    Stats(listOf(
        Triple("Average", fmtInt(d.stressAvg), null), Triple("Peak", fmtInt(d.stressMax), null), Triple("Readings", fmtInt(d.stress.size), null),
        Triple("Relaxed", fmtHm(relaxed), null), Triple("Level", stressLevel(d.stressAvg), null),
    ))
    Gap(18)
    ChartPanel("Stress through the day (0–100)") {
        InteractiveChart(
            listOf(ChartLine(d.stress, c.ink, "Stress", "", gap = 1800.0)),
            d.dayStart.toDouble(), d.dayStart + 86_400.0, height = 200.dp, yMin = 0.0, yMax = 100.0,
            bands = d.stressBands.map { b -> ChartBand(b.low.toDouble(), b.high + 1.0, stressColor(b.name, c).copy(alpha = 0.14f)) },
            spans = sleepAndWorkoutSpans(d, c),
            summary = { MutedText("Coloured bands: relaxed, normal, medium, high. Touch for the reading at a time.") },
        )
    }
    Gap(24); SectionTitle("Time in each level"); Gap(10)
    BandBar(d.stressBands) { stressColor(it, c) }
    Gap(24); SectionTitle("Hour by hour"); Gap(8)
    ChartPanel("Average stress per hour") { HourChart(d.stressByHour, d.dayStart, c.warn, "Average", "") }
    Gap(16)
    MutedText("The watch estimates stress from heart-rate variability while you are still. Readings during exercise are not taken, and a high reading after a workout can be recovery rather than mental stress.")
}

private fun stressLevel(avg: Int?): String = when { avg == null -> "–"; avg < 40 -> "Relaxed"; avg < 60 -> "Normal"; avg < 80 -> "Medium"; else -> "High" }

// ---- SpO2 and temperature ----

@Composable
private fun BodyDay(d: HealthDayUi, live: Boolean, onRefresh: () -> Unit, nav: Navigator, spo2: dev.heartline.app.TrendSeries?, temp: dev.heartline.app.TrendSeries?) {
    val c = HL.colors
    // While today's page is open, fetch the newest temperature every minute (the service otherwise does every 5 minutes).
    if (live) LaunchedEffect(Unit) { while (true) { onRefresh(); delay(60_000) } }
    FocusTitle(nav, "spo2", "Blood oxygen (SpO2)"); Gap(2)
    if (d.spo2.isEmpty()) MutedText("No SpO2 readings this day. The watch records SpO2 during sleep and when you measure on the watch.")
    else {
        Stats(listOf(Triple("Average", d.spo2Avg?.let { "%.1f".format(it) } ?: "–", "%"), Triple("Lowest", d.spo2Min?.roundToInt()?.toString() ?: "–", "%"), Triple("Readings", fmtInt(d.spo2.size), null)))
        Gap(14)
        ChartPanel("SpO2 (%)") {
            InteractiveChart(listOf(ChartLine(d.spo2, c.sleepDeep, "SpO2", "%", gap = 1800.0)),
                // Down to 85%, or lower when a reading is: nothing drawn below the axis.
                d.dayStart.toDouble(), d.dayStart + 86_400.0, height = 170.dp, yMin = minOf(85.0, kotlin.math.floor((d.spo2Min ?: 85.0) - 1)), yMax = 100.0,
                bands = listOf(ChartBand(95.0, 100.0, c.ok.copy(alpha = 0.10f)), ChartBand(90.0, 95.0, c.warn.copy(alpha = 0.10f)),
                    ChartBand(minOf(85.0, kotlin.math.floor((d.spo2Min ?: 85.0) - 1)), 90.0, c.pulse.copy(alpha = 0.10f))),
                spans = sleepAndWorkoutSpans(d, c))
        }
        Gap(14); BandBar(d.spo2Bands) { spo2Color(it, c) }
        Gap(8); MutedText("Wrist SpO2 is a wellness estimate. Brief dips during sleep are common; repeated readings under 90% are worth mentioning to a doctor.")
    }
    spo2?.let { TwoWeeks(it, nav, c.sleepDeep) }
    Gap(18); FocusTitle(nav, "temp", "Temperature"); Gap(2)
    if (live) d.temperature.lastOrNull()?.let { p -> TemperatureNow(p, onRefresh); Gap(14) }
    if (d.temperature.isEmpty()) MutedText("No temperature readings this day.")
    else {
        Stats(listOf(Triple("Average", d.tempAvg?.let { "%.1f".format(it) } ?: "–", "°C"), Triple("Lowest", d.tempMin?.let { "%.1f".format(it) } ?: "–", "°C"),
            Triple("Highest", d.tempMax?.let { "%.1f".format(it) } ?: "–", "°C")))
        Gap(14)
        ChartPanel("Temperature (°C), one reading a minute") {
            val lines = listOf(ChartLine(d.temperature, c.warn, "Skin", "°C", gap = 300.0, format = { "%.2f".format(it) })) +
                (if (d.temperature2.isNotEmpty()) listOf(ChartLine(d.temperature2, c.sleepLight, "Second sensor", "°C", gap = 300.0, width = 1.5.dp, format = { "%.2f".format(it) })) else emptyList()) +
                (if (d.temperatureSetAside.isNotEmpty()) listOf(ChartLine(d.temperatureSetAside, c.inkSoft.copy(alpha = 0.5f), "Set aside", "°C", gap = 300.0, width = 1.dp, format = { "%.2f".format(it) })) else emptyList())
            InteractiveChart(lines, d.dayStart.toDouble(), d.dayStart + 86_400.0, height = 190.dp, spans = sleepAndWorkoutSpans(d, c))
        }
        Gap(8)
        MutedText("The watch's temperature sensor reads the skin under it while worn and the air when it is off your wrist, and needs about 15 minutes to settle after you put it on. Each minute's record holds two readings. Off the wrist both match the room; worn, the second sits about 1.3 °C below the first (measured on your watch, 7 Nov), so it looks like a second sensor further from the skin, probably inside the case. Amazfit doesn't document it, so Heartline uses the first as your skin temperature." +
            " Skin temperature only uses readings taken on your wrist: a heart rate that minute, or the two sensors apart. Readings off the wrist, in the first 10 minutes after putting it back on, and one-off spikes are set aside (grey)" +
            (if (d.temperatureSetAside.isNotEmpty()) "; ${d.temperatureSetAside.size} this day." else "."))
    }
    temp?.let { TwoWeeks(it, nav, c.warn) }
}

/** The newest reading and how old it is; the watch writes its temperature records with a delay of its own. */
@Composable
private fun TemperatureNow(p: SeriesPoint, onRefresh: () -> Unit) {
    val c = HL.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() / 1000 } }
    val age = ((now - p.t) / 60).toLong().coerceAtLeast(0)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("%.1f °C".format(p.v), style = MaterialTheme.typography.headlineMedium, color = c.ink)
            Text(if (age < 1) "Latest reading, just now" else "Latest reading, $age min ago", style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
        }
        TextButton(onClick = onRefresh) { Text("Refresh") }
    }
}
