package dev.heartline.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heartline.app.HeartlineController
import dev.heartline.app.SeriesPoint
import dev.heartline.app.SleepNightUi
import dev.heartline.ui.Navigator
import dev.heartline.ui.Route
import dev.heartline.ui.components.AreaLabel
import dev.heartline.ui.components.Card
import dev.heartline.ui.components.ChartLine
import dev.heartline.ui.components.ChartSpan
import dev.heartline.ui.components.Fact
import dev.heartline.ui.components.Heading
import dev.heartline.ui.components.InteractiveChart
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.Segmented
import dev.heartline.service.OvernightSettings
import dev.heartline.ui.theme.Area
import dev.heartline.ui.theme.HL
import dev.heartline.ui.theme.hue
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Overnight HRV for the night ending on [date]: the switch, what the engine is doing, the night's HRV (median of clean
 * samples), its breathing and heart rate, every sample on a chart across the night, and why any were set aside.
 */
@Composable
fun OvernightCard(controller: HeartlineController, date: LocalDate, night: SleepNightUi?, nav: Navigator) {
    val c = HL.colors
    val o by controller.overnight.collectAsStateWithLifecycle()
    val samples = o.samples.filter { it.night == date }.sortedBy { it.ts }
    val clean = samples.filter { it.rmssd != null }
    fun med(v: List<Double>) = v.sorted().let { if (it.isEmpty()) null else if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
    Heading("Overnight HRV")
    Card(Modifier.section(nav, "hrv_sleep"), padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                AreaLabel("Automatic heart checks while you sleep", c.hue(Area.SLEEP), dot = false)
                Text(if (o.enabled) o.status.takeIf { it != "Off" } ?: "Starting…" else "Off", style = HL.type.small, color = c.inkSoft)
            }
            Switch(o.enabled, { controller.setOvernight(it) },
                colors = SwitchDefaults.colors(checkedTrackColor = c.ok, checkedThumbColor = c.surface, uncheckedTrackColor = c.gridMajor, uncheckedThumbColor = c.surface, uncheckedBorderColor = c.gridMajor))
        }
        if (o.enabled) Schedule(o.nightEvery, o.nightLen, OvernightSettings.NIGHT_EVERY) { e, l -> controller.setHrvSchedule(true, e, l) }
        if (samples.isEmpty()) {
            Gap(8)
            MutedText(if (o.enabled) "No samples for this night yet. Once the watch has labelled 15 minutes as sleep, a ${o.nightLen}-minute heart check runs every ${o.nightEvery} minutes until you wake. " +
                "One that isn't clean is retried twice, 2 minutes apart. Keep the phone near the watch." else
                "When on, Heartline takes short heart checks (3 minutes every 15, adjustable) while the watch says you're asleep, inside a window around your usual sleep times. " +
                    "A check that isn't clean is retried twice. It gives a nightly HRV and breathing rate, and once three nights have it, readiness uses overnight HRV. " +
                    "It costs some watch and phone battery; the event log records how much.")
            return@Card
        }
        Gap(10)
        Row(Modifier.fillMaxWidth()) {
            Fact("HRV, median", med(clean.map { it.rmssd!! })?.roundToInt()?.toString() ?: "–", Modifier.weight(1f), unit = "ms")
            Fact("Breathing", med(clean.mapNotNull { it.breathing })?.let { "%.1f".format(it) } ?: "–", Modifier.weight(1f), unit = "/min")
            Fact("Heart rate", med(clean.mapNotNull { it.hr })?.roundToInt()?.toString() ?: "–", Modifier.weight(1f), unit = "bpm")
        }
        Gap(4)
        val retried = samples.count { it.attempt > 0 }
        MutedText("${clean.size} of ${samples.size} samples clean" + (if (retried > 0) " ($retried were retries)" else "") + if (samples.size > clean.size) {
            val reasons = samples.filter { it.rmssd == null }.groupingBy { it.reason ?: "unclear" }.eachCount()
            "; set aside: " + reasons.entries.joinToString(", ") { (r, n) -> if (n > 1) "$r ($n)" else r }
        } else ".")
        if (clean.size >= 2) {
            Gap(10)
            val from = (night?.start ?: samples.first().ts - 1800).toDouble().coerceAtMost(samples.first().ts.toDouble() - 600)
            val to = (night?.end ?: samples.last().ts + 1800).toDouble().coerceAtLeast(samples.last().ts.toDouble() + 600)
            InteractiveChart(
                listOf(
                    ChartLine(clean.map { SeriesPoint(it.ts.toDouble(), it.rmssd!!) }, c.hue(Area.SLEEP), "HRV", "ms", gap = 4 * 3600.0, spacing = 1800.0),
                    ChartLine(clean.mapNotNull { s -> s.hr?.let { SeriesPoint(s.ts.toDouble(), it) } }, c.pulse, "Heart rate", "bpm", width = 0.dp, axis = false, gap = 4 * 3600.0),
                    ChartLine(clean.mapNotNull { s -> s.breathing?.let { SeriesPoint(s.ts.toDouble(), it) } }, c.oxygen, "Breathing", "/min", width = 0.dp, axis = false, gap = 4 * 3600.0, format = { "%.1f".format(it) }),
                ),
                from, to, height = 150.dp,
                spans = listOfNotNull(night?.let { ChartSpan(it.start.toDouble(), it.end.toDouble(), c.hue(Area.SLEEP).copy(alpha = 0.08f), "Asleep") }),
                idleHint = "Each dot is one clean sample. Touch for its HRV, heart rate and breathing.",
            )
        }
        val trends by controller.trends.collectAsStateWithLifecycle()
        trends?.series?.firstOrNull { it.key == "hrv_sleep" }?.takeIf { s -> s.points.count { it.value != null } >= 2 }?.let { s ->
            Gap(12)
            Text("Night by night", style = HL.type.label, color = c.inkSoft)
            dev.heartline.ui.components.DayBars(s.points, c.hue(Area.SLEEP), line = true, height = 100.dp, reference = s.baseline, referenceLabel = s.baseline?.let { "your usual, ${it.roundToInt()} ms" }, unit = "ms")
            TrendExtras(s, showAbout = false)
        }
        Gap(8)
        Text("HRV asleep runs higher than in a daytime check, so it is only compared with other nights. It's usually highest in deep sleep, early in the night.",
            style = HL.type.small.copy(fontSize = 12.sp), color = c.inkSoft)
    }
}

/**
 * Daytime HRV on [date]: the switch, its status, the day's automatic samples taken while you were still, and the heart
 * checks you started yourself that day (from the app, the watch or the assistant), drawn as their own dots.
 */
@Composable
fun DayHrvCard(controller: HeartlineController, date: LocalDate) {
    val c = HL.colors
    val o by controller.overnight.collectAsStateWithLifecycle()
    val allChecks by controller.checks.collectAsStateWithLifecycle()
    val zone = java.time.ZoneId.systemDefault()
    val samples = o.daySamples.filter { it.night == date }.sortedBy { it.ts }
    val clean = samples.filter { it.rmssd != null }
    val manual = allChecks.filter { it.rmssd != null && java.time.Instant.ofEpochSecond(it.start).atZone(zone).toLocalDate() == date }.sortedBy { it.start }
    Heading("HRV through the day")
    Card(padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                AreaLabel("Automatic heart checks when you're still", c.pulse, dot = false)
                Text(if (o.allDay) o.dayStatus.takeIf { it != "Off" } ?: "Starting…" else "Off", style = HL.type.small, color = c.inkSoft)
            }
            Switch(o.allDay, { controller.setAllDayHrv(it) },
                colors = SwitchDefaults.colors(checkedTrackColor = c.ok, checkedThumbColor = c.surface, uncheckedTrackColor = c.gridMajor, uncheckedThumbColor = c.surface, uncheckedBorderColor = c.gridMajor))
        }
        if (o.allDay) RunningCheck(controller)
        if (o.allDay) Schedule(o.dayEvery, o.dayLen, OvernightSettings.DAY_EVERY, day = true) { e, l -> controller.setHrvSchedule(false, e, l) }
        if (samples.isEmpty() && manual.isEmpty()) {
            Gap(8)
            MutedText(if (o.allDay) "No samples this day yet. Once the watch has counted no steps for a minute, a ${o.dayLen}-minute heart check runs, and checks keep going back to back until one is clean. " +
                "Then it waits ${o.dayEvery} minutes, as it does after 7 checks without steps that weren't clean. A check you walk during waits 2, 4, 8, then 16 minutes before the next try." else
                "When on, Heartline takes a short heart check whenever you've stopped walking for a minute, trying until it gets a clean one, then waits (30 minutes by default), so you can see how HRV moves through the day. " +
                    "Daytime HRV changes a lot with posture, food, caffeine and stress, so it doesn't feed readiness. It costs some battery.")
            return@Card
        }
        Gap(10)
        val v = (clean.mapNotNull { it.rmssd } + manual.mapNotNull { it.rmssd }).sorted()
        Row(Modifier.fillMaxWidth()) {
            Fact("HRV, median", v.takeIf { it.isNotEmpty() }?.let { it[it.size / 2].roundToInt().toString() } ?: "–", Modifier.weight(1f), unit = "ms")
            Fact("Range", if (v.size >= 2) "${v.first().roundToInt()}–${v.last().roundToInt()}" else "–", Modifier.weight(1f), unit = "ms")
            if (samples.isNotEmpty()) Fact("Clean", "${clean.size}", Modifier.weight(1f), unit = "of ${samples.size} tries")
            else Fact("Heart checks", "${manual.size}", Modifier.weight(1f), unit = "by you")
        }
        if (manual.isNotEmpty() && samples.isNotEmpty()) {
            Gap(4)
            MutedText("Includes ${manual.size} heart check${if (manual.size == 1) "" else "s"} you started, shown as the separate dots.")
        }
        if (v.isNotEmpty()) {
            Gap(10)
            val start = date.atStartOfDay(zone).toEpochSecond().toDouble()
            InteractiveChart(
                listOf(
                    ChartLine(clean.map { SeriesPoint(it.ts.toDouble(), it.rmssd!!) }, c.pulse, "HRV", "ms", gap = 6 * 3600.0, spacing = 3600.0),
                    // Heart checks you started: dots only (no joins), so they stand apart from the automatic line.
                    ChartLine(manual.map { SeriesPoint(it.start.toDouble(), it.rmssd!!) }, c.ok, "Your heart check", "ms", gap = 1.0),
                    ChartLine(clean.mapNotNull { s -> s.hr?.let { SeriesPoint(s.ts.toDouble(), it) } } + manual.mapNotNull { m -> m.hr?.let { SeriesPoint(m.start.toDouble(), it) } }
                        .sortedBy { it.t }, c.ink, "Heart rate", "bpm", width = 0.dp, axis = false, gap = 6 * 3600.0),
                ),
                start, start + 86_400, height = 150.dp,
                idleHint = if (manual.isEmpty()) "Each dot is one 2-minute sample taken while you were still. Touch for its HRV and heart rate."
                    else "The line's dots are automatic samples taken while you were still; the separate dots are heart checks you started. Touch for HRV and heart rate.",
            )
        }
        Gap(8)
        Text("Compare times of day with each other, not with overnight or morning checks: HRV is usually lowest in the afternoon and after meals or coffee.",
            style = HL.type.small.copy(fontSize = 12.sp), color = c.inkSoft)
    }
}

/** How often and how long the automatic samples run. Longer and more often gives steadier numbers at more battery. */
@Composable
private fun Schedule(every: Int, length: Int, everyOptions: List<Int>, day: Boolean = false, onChange: (Int, Int) -> Unit) {
    val c = HL.colors
    Gap(10)
    Text(if (day) "After a clean reading, wait" else "Every", style = HL.type.label, color = c.inkSoft)
    Gap(4)
    Segmented(everyOptions.map { it to if (it == 60) "1 h" else "$it min" }, every, { onChange(it, length) }, Modifier.fillMaxWidth())
    Gap(8)
    Text("Each sample lasts", style = HL.type.label, color = c.inkSoft)
    Gap(4)
    Segmented(OvernightSettings.LENGTHS.map { it to "$it min" }, length, { onChange(every, it) }, Modifier.fillMaxWidth())
    Gap(4)
    Text(if (day) "Checks of $length minutes run back to back until one is clean (or 7 have failed while you sat still), then wait $every minutes. Longer checks give steadier HRV but are harder to get clean." else
        "Recording ${length} of every ${every} minutes (${(100.0 * length / every).roundToInt()}% of the time). Longer samples give steadier HRV; more often costs more battery.",
        style = HL.type.small.copy(fontSize = 12.sp), color = c.inkSoft)
}

/** Seconds left and the share done of a running heart check, ticking each second. */
@Composable
private fun checkProgress(r: dev.heartline.app.HrvCheckUi): Pair<Long, Float> {
    var now by androidx.compose.runtime.remember(r) { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(r) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    val elapsed = (now / 1000 - r.startedAt).coerceIn(0, r.seconds.toLong())
    return (r.seconds - elapsed) to (elapsed.toFloat() / r.seconds)
}

private fun mss(s: Long) = "%d:%02d".format(s / 60, s % 60)

/**
 * Across the top of every screen while an automatic heart check records: what it is, the time left, and a reminder to
 * keep the wrist still, with a bar that fills as it runs. Tapping it opens the page that shows the result.
 */
@Composable
fun HrvCheckBanner(controller: HeartlineController, onOpen: (night: Boolean) -> Unit) {
    val run by controller.hrvCheck.collectAsStateWithLifecycle()
    val r = run ?: return
    val c = HL.colors
    val (left, done) = checkProgress(r)
    val beat by androidx.compose.animation.core.rememberInfiniteTransition(label = "beat").animateFloat(0.3f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(550), androidx.compose.animation.core.RepeatMode.Reverse), label = "beat")
    // Screens draw behind the status bar, so the banner pads itself below it.
    Column(Modifier.fillMaxWidth().windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars).background(c.ink)
        .clickable(onClickLabel = "Open heart checks") { onOpen(r.night) }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            dev.heartline.ui.components.Dot(c.pulse.copy(alpha = beat), 9.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (r.night) "Overnight heart check" else "Heart check running" + if (r.attempt > 0) ", try ${r.attempt + 1}" else "",
                    style = HL.type.bodyStrong, color = c.paper)
                Text(if (r.night) "Recording while you sleep" else "Keep your wrist still if you can", style = HL.type.small, color = c.paper.copy(alpha = 0.75f))
            }
            Text("${mss(left)} left", style = HL.type.label, color = c.paper)
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(c.paper.copy(alpha = 0.15f))) {
            Box(Modifier.fillMaxWidth(done).height(3.dp).background(c.pulse))
        }
    }
}

/** Inside the daytime HRV card while a check records: a bar filling over its length, and the seconds left. */
@Composable
internal fun RunningCheck(controller: HeartlineController) {
    val run by controller.hrvCheck.collectAsStateWithLifecycle()
    val r = run?.takeIf { !it.night } ?: return
    val c = HL.colors
    val (left, done) = checkProgress(r)
    Gap(10)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Recording now" + if (r.attempt > 0) ", try ${r.attempt + 1}" else "", style = HL.type.bodyStrong, color = c.pulse, modifier = Modifier.weight(1f))
        Text("${mss(left)} left", style = HL.type.label, color = c.ink)
    }
    Gap(6)
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.gridMajor)) {
        Box(Modifier.fillMaxWidth(done).height(6.dp).background(c.pulse))
    }
}
