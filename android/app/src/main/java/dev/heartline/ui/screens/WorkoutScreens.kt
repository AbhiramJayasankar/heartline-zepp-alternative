package dev.heartline.ui.screens

import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Switch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heartline.app.CaptureAnalysisUi
import dev.heartline.app.HeartlineController
import dev.heartline.app.LinkState
import dev.heartline.app.WorkoutDetailUi
import dev.heartline.ui.Navigator
import dev.heartline.ui.Route
import dev.heartline.ui.components.ActivityBand
import dev.heartline.ui.components.ChartBand
import dev.heartline.ui.components.ChartLine
import dev.heartline.ui.components.ChartSpan
import dev.heartline.ui.components.InteractiveChart
import dev.heartline.ui.components.ButtonStyle
import dev.heartline.ui.components.HrTraceChart
import dev.heartline.ui.components.LegendDot
import dev.heartline.ui.components.MotionStrip
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.Panel
import dev.heartline.ui.components.PillButton
import dev.heartline.ui.components.SectionTitle
import dev.heartline.ui.components.Segmented
import dev.heartline.ui.components.SegmentBar
import dev.heartline.ui.components.SeriesChart
import dev.heartline.ui.components.SessionChart
import dev.heartline.ui.components.Stat
import dev.heartline.ui.components.ZoneBars
import dev.heartline.ui.components.clock
import dev.heartline.ui.components.fmtHm
import dev.heartline.ui.components.fmtInt
import dev.heartline.ui.components.paper
import dev.heartline.ui.components.segmentColor
import dev.heartline.ui.theme.HL
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import kotlin.math.roundToInt

/** Three stats per row, so dense workout numbers stay aligned. */
@Composable
private fun StatGrid(items: List<Triple<String, String, String?>>) {
    items.filter { it.second != "–" }.chunked(3).forEachIndexed { i, row ->
        if (i > 0) Gap(14)
        Row(Modifier.fillMaxWidth()) {
            row.forEach { (label, value, unit) -> Stat(label, value, Modifier.weight(1f), unit = unit) }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

private fun pace(sPerKm: Double?): String = sPerKm?.takeIf { it in 60.0..3600.0 }?.let { "%d:%02d".format(it.toInt() / 60, it.toInt() % 60) } ?: "–"
private fun one(v: Double?): String = v?.let { "%.1f".format(it) } ?: "–"

// ---- Capture: start and live ----

@Composable
fun CaptureScreen(controller: HeartlineController, nav: Navigator) {
    val c = HL.colors
    val run by controller.captureRun.collectAsStateWithLifecycle()
    val conn by controller.connection.collectAsStateWithLifecycle()
    val r = run
    var label by rememberSaveable { mutableStateOf("Gym") }
    var minutes by rememberSaveable { mutableIntStateOf(180) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(r != null) { while (r != null) { delay(1000); now = System.currentTimeMillis() } }
    // When a capture ends, open its report as soon as the analysis lands.
    var endedAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(r) { if (r != null) endedAt = r.startedAt else if (endedAt != null) {
        val started = endedAt!!
        val item = withTimeoutOrNull(30_000) { controller.captures.first { l -> l.any { it.start >= started - 5 } }.first { it.start >= started - 5 } }
        endedAt = null
        if (item != null) nav.replace(Route.CaptureResult(item.id))
    } }
    Screen("Workout capture", onBack = { nav.pop() }, subtitle = "Records everything the watch can stream for the whole session, then breaks it down: sets, reps, rest, heart-rate recovery, zones and load.") {
        if (r == null) {
            Text("What are you doing?", style = HL.type.label, color = c.inkSoft)
            Gap(8)
            Segmented(listOf("Gym" to "Gym", "Run" to "Run", "Other" to "Other"), label, { label = it })
            Gap(18)
            Text("For how long?", style = HL.type.label, color = c.inkSoft)
            Gap(8)
            Segmented(listOf(45 to "45 min", 60 to "1 h", 90 to "1 h 30", 180 to "Until I stop"), minutes, { minutes = it })
            Gap(24)
            PillButton("Start capture", { controller.startCapture(label, minutes) }, Modifier.fillMaxWidth(), style = ButtonStyle.PULSE, enabled = conn.state == LinkState.CONNECTED)
            if (conn.state != LinkState.CONNECTED) { Gap(6); MutedText("Connect the watch first.") }
            Gap(20)
            val auto by controller.autoCapture.collectAsStateWithLifecycle()
            ToggleRow("Start with the watch", "When you start a workout on the watch, a capture named after it begins and stops when the workout ends. The watch reports workouts in batches, so this can be up to about 30 minutes late: for a whole session, start the capture here.", auto) { controller.setAutoCapture(it) }
            Gap(24)
            Panel {
                Text("Before you start", style = HL.type.bodyStrong, color = c.ink)
                Gap(6)
                MutedText("Wear the watch snug, a finger above the wrist bone. Also start a workout on the watch: its own heart rate copes with arm movement better than the raw pulse does.")
                Gap(6)
                MutedText("Keep the phone within about 10 m. The screen can be off. Streaming uses the watch battery faster; charge it first if it is under 30%.")
            }
        } else {
            val elapsed = ((now / 1000) - r.startedAt).coerceAtLeast(0)
            Column(Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
                Text(r.label, style = HL.type.label, color = c.inkSoft)
                Text(clock(elapsed.toDouble()), style = HL.type.hero.copy(fontSize = 96.sp), color = c.ink)
                MutedText(if (r.plannedS >= 180 * 60) "until you tap Stop (stops itself after 3 h)" else "of ${r.plannedS / 60} min, stops by itself")
            }
            Gap(12)
            Row(Modifier.fillMaxWidth()) {
                Stat("Heart rate", fmtInt(r.hr), Modifier.weight(1f), unit = "bpm")
                Stat("Samples", fmtInt(r.samples), Modifier.weight(1f))
                Stat("Link", if (conn.state == LinkState.CONNECTED) "OK" else "Lost", Modifier.weight(1f))
            }
            r.error?.let { Gap(10); Text(it, style = HL.type.small, color = c.pulse) }
            if (conn.state != LinkState.CONNECTED) { Gap(8); MutedText("The watch dropped out. The capture keeps going and resumes when it reconnects; the gap is noted.") }
            Gap(24)
            PillButton("Stop and analyse", { controller.stopCapture() }, Modifier.fillMaxWidth())
        }
    }
}

/** Shown above every screen while a capture runs; tapping opens the live view. */
@Composable
fun CaptureBanner(controller: HeartlineController, onOpen: () -> Unit) {
    val run by controller.captureRun.collectAsStateWithLifecycle()
    val r = run ?: return
    val c = HL.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    Row(Modifier.fillMaxWidth().background(c.ink).clickable(onClick = onOpen).padding(horizontal = Gutter, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        dev.heartline.ui.components.Dot(c.pulse, 8.dp)
        Spacer(Modifier.width(10.dp))
        Text("Capturing ${r.label.lowercase()}, ${clock(((now / 1000) - r.startedAt).coerceAtLeast(0).toDouble())}", style = HL.type.bodyStrong, color = c.paper, modifier = Modifier.weight(1f))
        Text(r.hr?.let { "$it bpm" } ?: "", style = HL.type.label, color = c.paper)
    }
}

/** Entry point on the Activity tab: start a capture and see past sessions. */
@Composable
fun CapturePanel(controller: HeartlineController, nav: Navigator) {
    val c = HL.colors
    val list by controller.captures.collectAsStateWithLifecycle()
    val run by controller.captureRun.collectAsStateWithLifecycle()
    SectionTitle("Workout capture")
    Gap(4)
    MutedText("Record everything the watch can measure for a session and get sets, reps, rest and recovery afterwards.")
    Gap(10)
    PillButton(if (run != null) "Capture running, open" else "Start a capture", { nav.push(Route.Capture) }, style = if (run != null) ButtonStyle.QUIET else ButtonStyle.PULSE)
    list.take(5).forEach { s ->
        Row(Modifier.fillMaxWidth().clickable { nav.push(Route.CaptureResult(s.id)) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.label, style = HL.type.bodyStrong, color = c.ink)
                MutedText("${dateTimeOf(s.start)}, ${s.durationS / 60} min" + (s.sets?.let { ", $it sets" } ?: ""))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${fmtInt(s.avgHr)} bpm", style = HL.type.stat.copy(fontSize = 20.sp), color = c.ink)
                s.load?.let { MutedText("load ${it.roundToInt()}") }
            }
        }
        HorizontalDivider(color = c.line)
    }
}

// ---- Capture analysis ----

@Composable
fun CaptureResultScreen(controller: HeartlineController, id: String, nav: Navigator) {
    val c = HL.colors
    val result by produceState<CaptureAnalysisUi?>(null, id) { value = controller.captureDetail(id) }
    var confirm by remember { mutableStateOf(false) }
    val a = result
    Screen(a?.label ?: "Session", onBack = { nav.pop() }, subtitle = a?.let { "${dateTimeOf(it.start)}, ${fmtHm(it.durationS / 60)}" },
        trailing = { TextButton({ confirm = true }) { Text("Delete", color = c.inkSoft) } }) {
        if (a == null) { MutedText("Analysing the recording…"); return@Screen }
        val restLengths = a.rests.filter { r -> r.end - r.start <= 600 && a.cardio.none { it.start < r.end && it.end > r.start } }.map { it.end - it.start }.sorted()
        val medianRest = restLengths.getOrNull(restLengths.size / 2)
        StatGrid(listOf(
            Triple("Average", fmtInt(a.avgHr), "bpm"), Triple("Max", fmtInt(a.maxHr), "bpm"), Triple("Lowest", fmtInt(a.minHr), "bpm"),
            Triple("Load", a.load?.roundToInt()?.toString() ?: "–", null), Triple("Calories", fmtInt(a.calories), "kcal"), Triple("Steps", fmtInt(a.steps), null),
            Triple("Sets", if (a.sets.isEmpty()) "–" else a.sets.size.toString(), null),
            // With a log, its reps are the true count; the watch's own count is compared set by set below.
            Triple("Reps", if (a.sets.isEmpty()) "–" else a.sets.sumOf { s -> s.loggedReps ?: s.reps.toDouble() }.let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) }, null),
            Triple("Median rest", medianRest?.let { clock(it) } ?: "–", null),
            Triple("Under load", a.workS?.let { fmtHm(((it + 30) / 60)) } ?: "–", null),
            Triple("Work share", a.workS?.let { w -> a.restS?.takeIf { w + it > 0 }?.let { "${100 * w / (w + it)}" } } ?: "–", "%"),
            Triple("Exercises", if (a.sets.isEmpty()) "–" else a.sets.map { it.group }.distinct().size.toString(), null),
        ))
        Gap(20)
        Panel(padding = PaddingValues(12.dp)) {
            Text(if (a.floor.size > 1) "Heart rate, sets shaded by exercise, dashed line is your lowest per 5 min" else "Heart rate, sets shaded", style = HL.type.label, color = c.inkSoft)
            Gap(6)
            val setLabels = a.sets.mapIndexed { i, s -> ChartSpan(s.start, s.end, dev.heartline.ui.components.groupColor(s.group, c).copy(alpha = 0.25f),
                if (s.exercise != null) "${s.exercise}, ${s.setLabel?.lowercase()}" + (loadReps(s)?.let { ", $it" } ?: "") else "Set ${i + 1}, ${s.reps} reps") }
            val cardioSpans = a.cardio.map { k -> ChartSpan(k.start, k.end, c.warn.copy(alpha = 0.15f), k.name ?: k.kind.replaceFirstChar { it.uppercase() }) }
            InteractiveChart(
                listOf(ChartLine(a.hr, c.pulse, "Heart rate", "bpm", gap = 30.0)) +
                    if (a.floor.size > 1) listOf(ChartLine(a.floor, c.pulse.copy(alpha = 0.7f), "5-min low", "bpm", width = 1.5.dp, dashed = true, step = true, gap = 400.0)) else emptyList(),
                0.0, a.durationS.toDouble().coerceAtLeast(60.0), height = 210.dp, spans = setLabels + cardioSpans,
                bands = a.zones.filter { it.zone >= 3 }.map { z -> ChartBand(z.lowBpm.toDouble(), (z.highBpm ?: 240).toDouble(), dev.heartline.ui.components.zoneColor(z.zone, c).copy(alpha = 0.06f)) },
                xLabel = { clock(it) }, readoutX = { "${clock(it)} in, at " + dev.heartline.ui.components.epochClock(a.start + it, seconds = true) }, minSpan = 60.0,
                idleHint = "Touch for heart rate and the set at that moment. Pinch to zoom into a set.")
            MotionStrip(a.motion, a.durationS)
            Gap(8)
            SegmentBar(a.segments, a.durationS)
            if (a.activities.isNotEmpty()) { Gap(6); ActivityBand(a.activities, a.durationS) }
            Gap(8)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                a.segments.map { it.kind }.distinct().forEach { k -> LegendDot(segmentColor(k, c), k.replaceFirstChar { it.uppercase() }) }
                if (a.activities.isNotEmpty()) LegendDot(c.ink.copy(alpha = 0.3f), "Watch workout")
            }
        }
        BaselineNote(a)
        if (a.activities.isNotEmpty()) WatchWorkouts(a)
        if (a.logTotal != null) LogAndWatch(a)
        if (a.sets.isNotEmpty()) Exercises(a)
        if (a.recovery.size >= 2) Recovery(a, medianRest)
        a.cardio.forEach { CardioBlock(a, it) }
        a.pulse?.let { PulseSection(it) }
        if (a.zones.isNotEmpty()) {
            Gap(24); SectionTitle("Heart-rate zones"); Gap(12); ZoneBars(a.zones)
        }
        val gait = a.segments.filter { it.kind == "walking" || it.kind == "running" }
        val parts = gait.filter { it.end - it.start >= 120 }
        val shortWalks = gait.filter { it.end - it.start < 120 }
        if (parts.isNotEmpty()) {
            Gap(24); SectionTitle("Parts of the session"); Gap(4)
            parts.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    dev.heartline.ui.components.Dot(segmentColor(s.kind, c), 10.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.kind.replaceFirstChar { it.uppercase() }, style = HL.type.bodyStrong, color = c.ink)
                        MutedText("${clock(s.start)} to ${clock(s.end)}" + (s.cadenceSpm?.let { ", $it steps/min" } ?: ""))
                    }
                    Text("${fmtInt(s.avgHr)} bpm", style = HL.type.label, color = c.ink)
                }
                HorizontalDivider(color = c.line)
            }
        }
        if (shortWalks.isNotEmpty()) {
            Gap(if (parts.isEmpty()) 24 else 10)
            MutedText("Plus ${shortWalks.size} short walk${if (shortWalks.size == 1) "" else "s"} between sets, ${clock(shortWalks.sumOf { it.end - it.start })} in all" +
                (shortWalks.mapNotNull { it.cadenceSpm }.takeIf { it.isNotEmpty() }?.let { ", about ${it.average().roundToInt()} steps/min" } ?: "") + ".")
        }
        Gap(24)
        SectionTitle("How this was measured")
        Gap(4)
        MutedText(a.hrSource)
        a.notes.forEach { Gap(4); MutedText(it) }
    }
    if (confirm) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirm = false }, containerColor = c.surface,
        title = { Text("Delete this session?", style = HL.type.section, color = c.ink) },
        text = { MutedText("The recording and its analysis are removed from this phone.") },
        confirmButton = { TextButton({ controller.deleteCapture(id); confirm = false; nav.pop() }) { Text("Delete", color = c.pulse) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel", color = c.inkSoft) } },
    )
}

/** A panel ruled like ECG paper, for the charts that plot heart signals. */
@Composable
private fun PaperPanel(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val c = HL.colors
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape).padding(12.dp), content = content)
}

@Composable
private fun BaselineNote(a: CaptureAnalysisUi) {
    if (a.floor.size < 3) return
    val first = a.floor.first().v.roundToInt(); val top = a.floor.maxBy { it.v }; val last = a.floor.last().v.roundToInt()
    if (top.v.roundToInt() - first < 8) return
    Gap(10)
    MutedText("Your lowest heart rate climbed from $first bpm at the start to ${top.v.roundToInt()} around ${clock(top.t)}, and was $last by the end. Each set starts from a higher floor as the session goes on.")
}

/** One label with the watch's figure and the raw sensors' figure side by side. */
@Composable
private fun CompareRow(label: String, watch: String, sensors: String) {
    val c = HL.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(label, style = HL.type.body, color = c.inkSoft, modifier = Modifier.weight(1.2f))
        Text(watch, style = HL.type.bodyStrong, color = c.ink, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        Text(sensors, style = HL.type.bodyStrong, color = c.ink, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
    HorizontalDivider(color = c.line)
}

@Composable
private fun WatchWorkouts(a: CaptureAnalysisUi) {
    val c = HL.colors
    Gap(24); SectionTitle("The watch's own workouts"); Gap(4)
    MutedText("What the watch logged for the workouts you started on it, beside what the raw sensors measured over the same minutes. The sensors see individual sets and reps; the watch adds names, distance and its calorie estimate.")
    a.activities.forEach { w ->
        val hr = a.hr.filter { it.t in w.start..w.end }.map { it.v }
        val sets = a.sets.filter { it.activity == w.type && (it.start + it.end) / 2 in w.start..w.end }
        val partial = w.activeS != null && w.activeS > (w.end - w.start) + 60
        val cardio = a.cardio.filter { it.end > w.start && it.start < w.end }
        val steps = a.segments.filter { it.cadenceSpm != null }.sumOf { s -> (minOf(s.end, w.end) - maxOf(s.start, w.start)).coerceAtLeast(0.0) * s.cadenceSpm!! / 60 }.roundToInt()
        Gap(14)
        Text(w.type, style = HL.type.bodyStrong, color = c.ink)
        MutedText("${clock(w.start)} to ${clock(w.end)} into the capture" + if (partial) ". Only part of this ${clock(w.activeS!!.toDouble())} workout falls in this capture, so the watch's figures cover more time than the sensors' do." else "")
        Gap(6)
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1.2f))
            Text("Watch", style = HL.type.label, color = c.inkSoft, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Text("Sensors", style = HL.type.label, color = c.inkSoft, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        HorizontalDivider(color = c.line)
        CompareRow("Average HR", fmtInt(w.avgHr), if (hr.isEmpty()) "–" else fmtInt(hr.average().roundToInt()))
        CompareRow("Max HR", fmtInt(w.maxHr), if (hr.isEmpty()) "–" else fmtInt(hr.max().roundToInt()))
        CompareRow("Active time", w.activeS?.let { clock(it.toDouble()) } ?: "–",
            when { cardio.isNotEmpty() -> clock(cardio.sumOf { it.end - it.start }); sets.isNotEmpty() -> clock(sets.sumOf { it.end - it.start }) + " in sets"; else -> "–" })
        CompareRow("Steps", fmtInt(w.steps), if (steps > 0) fmtInt(steps) else "–")
        if (w.distanceM != null && w.distanceM > 0) CompareRow("Distance", "%.2f km".format(w.distanceM / 1000.0), "–")
        CompareRow("Calories", w.calories?.let { "$it kcal" } ?: "–", "–")
        if (sets.isNotEmpty()) CompareRow("Sets / reps", "–", "${sets.size} / ${sets.sumOf { it.reps }}")
    }
}

@Composable
private fun Exercises(a: CaptureAnalysisUi) {
    val c = HL.colors
    val logged = a.logTotal != null
    Gap(24); SectionTitle("Exercises and sets"); Gap(4)
    MutedText(if (logged) "Sets, names and times from your gym log. Each block is one rep the watch counted: its width is how long the rep took, the solid part is the slower half, and its height is how far the wrist turned."
        else "Back-to-back sets that move the wrist the same way are grouped as one exercise. Each block is one rep: its width is how long the rep took, the solid part is the slower half, and its height is how far the wrist turned.")
    val groups = ArrayList<MutableList<dev.heartline.app.SetUi>>()
    a.sets.forEach { s -> if (groups.lastOrNull()?.last()?.group == s.group) groups.last().add(s) else groups.add(mutableListOf(s)) }
    val maxDur = a.sets.maxOfOrNull { s -> s.repDetail.sumOf { it.durationS } }?.coerceAtLeast(1.0) ?: 1.0
    var setNo = 0
    groups.forEachIndexed { gi, sets ->
        val color = dev.heartline.ui.components.groupColor(sets.first().group, c)
        val arcMax = sets.flatMap { s -> s.repDetail.map { minOf(it.arcDeg, 160.0) } }.maxOrNull() ?: 1.0
        Gap(18)
        Row(verticalAlignment = Alignment.CenterVertically) {
            dev.heartline.ui.components.Dot(color, 10.dp); Spacer(Modifier.width(8.dp))
            Text(sets.first().exercise ?: "Exercise ${exerciseLetter(gi)}", style = HL.type.section.copy(fontSize = 20.sp), color = c.ink)
        }
        MutedText(listOfNotNull(if (sets.size == 1) "1 set" else "${sets.size} sets",
            if (logged) null else "${sets.sumOf { it.reps }} reps",
            sets.mapNotNull { it.tempoS }.takeIf { it.isNotEmpty() }?.let { "about %.1f s per rep".format(it.average()) },
            sets.mapNotNull { s -> s.repDetail.map { it.arcDeg }.sorted().let { r -> r.getOrNull(r.size / 2) } }.takeIf { it.isNotEmpty() }?.let { "wrist turns about ${it.average().roundToInt()}°" },
            sets.first().activity).joinToString(", "))
        sets.forEach { s ->
            setNo++
            val rest = a.rests.firstOrNull { it.start >= s.end - 1 && it.start <= s.end + 5 }
            Gap(10)
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.width(if (logged) 108.dp else 70.dp)) {
                    Text(s.setLabel ?: "Set $setNo", style = HL.type.label, color = c.inkSoft)
                    Text(loadReps(s) ?: "${s.reps} reps", style = HL.type.bodyStrong, color = c.ink)
                }
                if (s.repDetail.isNotEmpty()) dev.heartline.ui.components.RepStrip(s.repDetail, maxDur, arcMax, color, Modifier.weight(1f))
                else Spacer(Modifier.weight(1f))
            }
            MutedText(listOfNotNull(
                watchCount(s),
                "${clock(s.start)}, lasted ${clock(s.end - s.start)}",
                if (s.startHr != null && s.peakHr != null) "HR ${s.startHr} to ${s.peakHr}" + (s.hrRise?.let { " (+$it)" } ?: "") else s.peakHr?.let { "peak $it bpm" },
                s.slowdownS?.takeIf { it >= 0.1 }?.let { "slowed %.2f s per rep".format(it) },
                if (s.partialLast) "last rep was short" else null,
                rest?.let { "then ${clock(it.end - it.start)} rest" + (it.drop60?.let { d -> ", HR down $d in 1 min" } ?: "") },
            ).joinToString(", "))
        }
    }
}

private fun num(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')

/** "45 kg × 8" from the log, or null without one. */
private fun loadReps(s: dev.heartline.app.SetUi): String? = s.loggedReps?.let { r ->
    (s.loadKg?.takeIf { it > 0 }?.let { "${num(it)} kg × " } ?: "") + num(r) + if (s.loadKg == null || s.loadKg == 0.0) " reps" else ""
}

/** What the watch made of a logged set. */
private fun watchCount(s: dev.heartline.app.SetUi): String? = when {
    s.loggedReps == null -> null
    s.source == "log" && s.reps == 0 -> "the watch didn't see a rep rhythm here, so it couldn't count this set"
    s.source == "log" -> "missed by the watch on its own; it counted ${s.reps} reps in your logged time"
    kotlin.math.abs(s.reps - s.loggedReps) < 0.5 -> "watch counted ${s.reps}, same as your log"
    else -> "watch counted ${s.reps}"
}

/** How the gym log and the watch's own set finding line up for this capture. */
@Composable
private fun LogAndWatch(a: CaptureAnalysisUi) {
    val total = a.logTotal ?: return
    val found = a.logMatched ?: 0
    val counted = a.sets.filter { it.loggedReps != null && it.reps > 0 }
    val close = counted.count { kotlin.math.abs(it.reps - it.loggedReps!!) <= 2 }
    Gap(24); SectionTitle("Your log and the watch"); Gap(4)
    MutedText("Your gym log has $total timed sets in this recording, and they set the sets, names and rest times on this page. " +
        "The watch found $found of them from wrist motion alone" + (if (found < total) "; for the other ${total - found}, it had only your logged times." else ".") +
        (if (counted.isNotEmpty()) " Where it counted reps, it was within 2 of your log on $close of ${counted.size} sets." else ""))
    if (a.unlogged.isNotEmpty()) {
        Gap(6)
        MutedText("It also found ${a.unlogged.size} rep-like stretch${if (a.unlogged.size == 1) "" else "es"} that ${if (a.unlogged.size == 1) "isn't" else "aren't"} in your log: " +
            a.unlogged.joinToString { "${clock(it.start)} (${it.reps} reps, ${clock(it.end - it.start)})" } +
            ". If you didn't do a set then, it was other movement, and it is left out of rests and recovery.")
    }
}

private fun exerciseLetter(i: Int): String = if (i < 26) ('A' + i).toString() else "${i + 1}"

@Composable
private fun Recovery(a: CaptureAnalysisUi, medianRest: Double?) {
    val m = a.recovery.minBy { it.delta }
    val at60 = a.recovery.minByOrNull { kotlin.math.abs(it.t - 60) }
    Gap(24); SectionTitle("Heart rate between sets"); Gap(4)
    MutedText("Average change from each set's peak while you rested, across ${a.recovery.first().n} rests.")
    Gap(10)
    PaperPanel { dev.heartline.ui.components.RecoveryChart(a.recovery, medianRest) }
    Gap(10)
    MutedText(buildString {
        at60?.let { append("Heart rate fell about ${(-it.delta).roundToInt()} bpm in the first minute. ") }
        append("It stopped falling around ${m.t} s")
        medianRest?.let { append(if (it > m.t + 30) ", well before your median rest of ${clock(it)} ended" else ", close to your median rest of ${clock(it)}") }
        append(". Heart-rate recovery is not muscle recovery, so longer rests before hard sets can still pay off.")
    })
}

@Composable
private fun CardioBlock(a: CaptureAnalysisUi, k: dev.heartline.app.CardioUi) {
    val c = HL.colors
    Gap(24); SectionTitle(k.name ?: k.kind.replaceFirstChar { it.uppercase() }); Gap(4)
    MutedText("${clock(k.start)} to ${clock(k.end)} into the capture, ${clock(k.end - k.start)} of steady ${k.kind}" + (k.name?.let { ", named by the watch" } ?: ""))
    Gap(10)
    PaperPanel { dev.heartline.ui.components.HrWindowChart(a.hr, (k.start - 120).coerceAtLeast(0.0), (k.end + 240).coerceAtMost(a.durationS.toDouble()), k.start, k.end) }
    Gap(14)
    StatGrid(listOf(
        Triple("Steady", fmtInt(k.steadyHr), "bpm"), Triple("Peak", fmtInt(k.peakHr), "bpm"), Triple("Average", fmtInt(k.avgHr), "bpm"),
        Triple("Rise time", fmtInt(k.riseTauS), "s"), Triple("Drift", k.driftPerMin?.let { "%+.1f".format(it) } ?: "–", "bpm/min"), Triple("Cadence", fmtInt(k.cadenceSpm), "spm"),
        Triple("Drop 1 min", fmtInt(k.drop60), "bpm"), Triple("Drop 2 min", fmtInt(k.drop120), "bpm"), Triple("Recovery", fmtInt(k.recoveryTauS), "s"),
        Triple("Distance", k.distanceM?.takeIf { it > 0 }?.let { "%.2f".format(it / 1000.0) } ?: "–", "km"), Triple("Speed", k.speedKmh?.let { "%.1f".format(it) } ?: "–", "km/h"),
    ))
    Gap(10)
    val notes = listOfNotNull(
        k.riseTauS?.let { "Rise time is how quickly heart rate settled towards its plateau (a time constant). Fitter hearts settle in under a minute; $it s is where regular cardio shows up first." },
        k.drop60?.let { if (it > 12) "Heart rate fell $it bpm in the first minute after stopping, comfortably above the 12 bpm warning level." else "Heart rate fell only $it bpm in the first minute after stopping; 12 or less is worth keeping an eye on." },
        if (k.distanceM != null || k.speedKmh != null) "Distance and speed come from the watch's workout; heart rate, cadence and timing from the raw sensors." else null,
    )
    notes.forEach { MutedText(it); Gap(4) }
}

@Composable
private fun PulseSection(p: dev.heartline.app.PulseUi) {
    val c = HL.colors
    val valid = p.windows.filter { it.status != "unreliable" }
    // Under a minute of clean signal is too little for a verdict, whatever the beats looked like.
    val enough = p.usableS >= 60
    val (title, color) = when {
        !enough || p.status == "insufficient_data" -> "Too little still time" to c.inkSoft
        p.status == "regular" -> "Regular" to c.ok; p.status == "irregular" -> "Some irregular beats" to c.warn
        else -> "Irregular pattern" to c.pulse
    }
    Gap(24); SectionTitle("Pulse regularity"); Gap(4)
    MutedText("Individual beats from the raw optical signal, whenever your wrist was still. A screening look, not an ECG.")
    Gap(4)
    MutedText("Why only still moments: when the arm moves, pressure under the strap swamps the pulse (it falls from about a fifth of the signal to about a twentieth). Cancelling it with the motion sensors was tested on a real session and could not recover trustworthy beats, so moving stretches are left out rather than guessed. Heart rate itself still works while moving.")
    Gap(10)
    Text(title, style = HL.type.score.copy(fontSize = 34.sp, lineHeight = 36.sp), color = color)
    val noisy = p.windows.size - valid.size
    MutedText("From ${clock(p.usableS.toDouble())} of still wrist in ${valid.size} stretch${if (valid.size == 1) "" else "es"}" +
        (if (noisy > 0) "; $noisy more ${if (noisy == 1) "was" else "were"} too noisy to use." else ".") +
        (if (!enough && valid.isNotEmpty()) " What there was looked ${if (p.status == "regular") "regular" else "uneven"}, but a verdict needs a minute or more; sit still for a few minutes during a capture to get one." else ""))
    if (p.excerpt.size > 1) {
        Gap(12)
        PaperPanel {
            Text("Filtered pulse at ${clock(p.excerptStart)}, each beat marked", style = HL.type.label, color = c.inkSoft)
            Gap(6)
            dev.heartline.ui.components.PulseWave(p.excerpt, p.excerptFs, p.excerptBeats)
        }
    }
    if (p.pairs.size >= 20) {
        Gap(12)
        PaperPanel {
            Text("Each beat gap against the next (ms)", style = HL.type.label, color = c.inkSoft)
            Gap(6)
            dev.heartline.ui.components.PoincarePlot(p.pairs)
            Gap(4)
            MutedText("A regular rhythm hugs the diagonal. An irregular one, such as atrial fibrillation, scatters into a wide fan.")
        }
    }
    Gap(14)
    StatGrid(listOf(
        Triple("Irregularity", p.nrmssd?.let { "%.3f".format(it) } ?: "–", null), Triple("Entropy", p.entropy?.let { "%.2f".format(it) } ?: "–", null),
        Triple("Early beats", p.ectopic.toString(), null),
    ))
    Gap(6)
    MutedText("Irregularity is beat-to-beat variation relative to the average gap (nRMSSD). Atrial fibrillation usually shows above 0.10 together with high entropy.")
    if (p.windows.isNotEmpty()) {
        Gap(12)
        p.windows.sortedBy { it.start }.forEach { w ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${clock(w.start)}–${clock(w.end)}", style = HL.type.body, color = c.ink, modifier = Modifier.weight(1.3f))
                Text("${w.beats} beats", style = HL.type.small, color = c.inkSoft, modifier = Modifier.weight(1f))
                Text("${w.hrPpg} / ${fmtInt(w.hrWatch)}", style = HL.type.small, color = c.inkSoft, modifier = Modifier.weight(1f))
                Text(if (w.status == "unreliable") "noisy" else "%.3f".format(w.nrmssd), style = HL.type.label,
                    color = when (w.status) { "regular" -> c.ok; "unreliable" -> c.inkSoft; "possible_af" -> c.pulse; else -> c.warn }, textAlign = TextAlign.End, modifier = Modifier.weight(0.8f))
            }
            HorizontalDivider(color = c.line)
        }
        Gap(4)
        MutedText("Columns: still stretch, beats found, heart rate from beats / from the watch, irregularity.")
    }
    p.notes.forEach { Gap(4); MutedText(it) }
}

// ---- Synced watch workouts ----

@Composable
fun WorkoutScreen(controller: HeartlineController, route: Route.Workout, nav: Navigator) {
    val c = HL.colors
    val day by remember(route.epochDay) { controller.activityDay(LocalDate.ofEpochDay(route.epochDay)) }.collectAsState(initial = null)
    val w = day?.workouts?.firstOrNull { it.start == route.start }
    Screen(w?.type ?: "Workout", onBack = { nav.pop() }, subtitle = w?.let { "${dateTimeOf(it.start)}, ${fmtHm(it.durationS / 60)}" }) {
        if (w == null) { MutedText("Loading…"); return@Screen }
        val d = w.detail ?: WorkoutDetailUi()
        StatGrid(listOf(
            Triple("Average", fmtInt(w.avgHr), "bpm"), Triple("Max", fmtInt(w.maxHr), "bpm"), Triple("Lowest", fmtInt(d.minHr), "bpm"),
            Triple("Duration", fmtHm(w.durationS / 60), null), Triple("Active", d.activeS?.let { fmtHm(it / 60) } ?: "–", null), Triple("Calories", fmtInt(w.calories), "kcal"),
            Triple("Distance", w.distanceM?.takeIf { it > 0 }?.let { "%.2f".format(it / 1000.0) } ?: "–", "km"), Triple("Avg pace", pace(d.avgPaceSPerKm), "/km"), Triple("Best pace", pace(d.bestPaceSPerKm), "/km"),
            Triple("Avg speed", one(d.avgSpeedKmh), "km/h"), Triple("Max speed", one(d.maxSpeedKmh), "km/h"), Triple("Steps", fmtInt(w.steps), null),
            Triple("Cadence", fmtInt(d.avgCadence), "spm"), Triple("Max cadence", fmtInt(d.maxCadence), "spm"), Triple("Stride", fmtInt(d.avgStrideCm), "cm"),
            Triple("Ascent", d.ascentM?.roundToInt()?.toString() ?: "–", "m"), Triple("Descent", d.descentM?.roundToInt()?.toString() ?: "–", "m"), Triple("Load", d.load?.roundToInt()?.toString() ?: "–", null),
            Triple("Training effect", one(d.trainingEffect), null), Triple("Recovery", d.recoveryHours?.let { "$it h" } ?: "–", null),
        ))
        // A capture recorded over this workout has far finer data: offer it here.
        val captures by controller.captures.collectAsStateWithLifecycle()
        captures.firstOrNull { it.start < w.start + w.durationS && it.start + it.durationS > w.start }?.let { cap ->
            Gap(20)
            Panel(wash = true) {
                Text("Raw-sensor analysis available", style = HL.type.bodyStrong, color = c.ink)
                Gap(4)
                MutedText("A Heartline capture was running during this workout" + (cap.sets?.let { ", with $it sets found" } ?: "") +
                    ". It has every set and rep, rest recovery, heart-rate kinetics and a pulse regularity check.")
                Gap(10)
                PillButton("Open the capture", { nav.push(Route.CaptureResult(cap.id)) }, style = ButtonStyle.QUIET)
            }
        }
        if (w.route.size >= 2) {
            Gap(20)
            Panel(padding = PaddingValues(12.dp)) {
                Text("Route", style = HL.type.label, color = c.inkSoft); Gap(6); dev.heartline.ui.components.RouteMap(w.route)
            }
        }
        Gap(20)
        val gymLog by controller.gymLog.collectAsStateWithLifecycle()
        // Timed sets from a gym log for this workout, shaded on the heart rate chart.
        val setSpans = remember(gymLog, w.start) {
            dev.heartline.app.setsForWorkout(gymLog, w.start, w.start + w.durationS, w.type, java.time.ZoneId.systemDefault())
                .filter { it.completed && it.start != null && it.end != null }
                .map { ChartSpan((it.start!! - w.start).toDouble(), (it.end!! - w.start).toDouble(), c.warn.copy(alpha = 0.16f)) }
        }
        Panel(padding = PaddingValues(12.dp)) {
            Text("Heart rate", style = HL.type.label, color = c.inkSoft)
            Gap(6)
            if (w.track.size > 1) InteractiveChart(
                listOf(ChartLine(w.track.map { dev.heartline.app.SeriesPoint((it.ts - w.start).toDouble(), it.bpm.toDouble()) }, c.pulse, "Heart rate", "bpm", fill = true, gap = 60.0)),
                0.0, w.durationS.toDouble().coerceAtLeast(60.0), height = 210.dp, spans = setSpans,
                bands = d.zones.map { z -> ChartBand(z.lowBpm.toDouble(), (z.highBpm ?: 240).toDouble(), dev.heartline.ui.components.zoneColor(z.zone, c).copy(alpha = 0.08f)) },
                xLabel = { clock(it) }, readoutX = { "${clock(it)} in, at " + dev.heartline.ui.components.epochClock(w.start + it, seconds = true) }, minSpan = 60.0)
            else HrTraceChart(w.track, w.durationS.toLong().coerceAtLeast(60), w.start + w.durationS)
        }
        WorkoutLogSection(controller, w)
        if (d.zones.isNotEmpty()) { Gap(24); SectionTitle("Heart-rate zones"); Gap(12); ZoneBars(d.zones) }
        listOf(Triple("Speed", d.speedKmh, "km/h"), Triple("Cadence", d.cadence, "spm"), Triple("Altitude", d.altitude, "m")).forEach { (title, s, unit) ->
            if (s.size >= 2) {
                Gap(16)
                Panel(padding = PaddingValues(12.dp)) {
                    Text(title, style = HL.type.label, color = c.inkSoft); Gap(6)
                    InteractiveChart(listOf(ChartLine(s, if (title == "Speed") c.ok else if (title == "Cadence") c.warn else c.sleepDeep, title, unit,
                        gap = 120.0, format = { if (title == "Speed") "%.1f".format(it) else "%.0f".format(it) })),
                        s.first().t, s.last().t.coerceAtLeast(s.first().t + 60), height = 140.dp, xLabel = { clock(it) }, readoutX = { "${clock(it)} in" }, minSpan = 60.0, idleHint = null)
                }
            }
        }
        if (d.laps.isNotEmpty()) {
            Gap(24); SectionTitle("Laps"); Gap(4)
            d.laps.forEach { l ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Text("${l.index}", style = HL.type.label, color = c.inkSoft, modifier = Modifier.width(32.dp))
                    Text(clock(l.durationS.toDouble()), style = HL.type.body, color = c.ink, modifier = Modifier.weight(1f))
                    Text(pace(l.paceSPerKm) + " /km", style = HL.type.body, color = c.ink, modifier = Modifier.weight(1f))
                    Text("${fmtInt(l.avgHr)} bpm", style = HL.type.body, color = c.ink)
                }
                HorizontalDivider(color = c.line)
            }
        }
        if (d.extras.isNotEmpty()) {
            Gap(24); SectionTitle("Everything else the watch recorded"); Gap(4)
            d.extras.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(k, style = HL.type.body, color = c.inkSoft, modifier = Modifier.weight(1f))
                    Text(v, style = HL.type.bodyStrong, color = c.ink, textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f))
                }
                HorizontalDivider(color = c.line)
            }
        }
    }
}
