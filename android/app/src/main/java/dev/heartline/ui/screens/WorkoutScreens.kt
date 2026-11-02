package dev.heartline.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heartline.app.CaptureAnalysisUi
import dev.heartline.app.HeartlineController
import dev.heartline.app.LinkState
import dev.heartline.app.WorkoutDetailUi
import dev.heartline.ui.Navigator
import dev.heartline.ui.Route
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
    var minutes by rememberSaveable { mutableIntStateOf(60) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(r != null) { while (r != null) { delay(1000); now = System.currentTimeMillis() } }
    Screen("Workout capture", onBack = { nav.pop() }, subtitle = "Records everything the watch can stream for the whole session, then breaks it down: sets, reps, rest, heart-rate recovery, zones and load.") {
        if (r == null) {
            Text("What are you doing?", style = HL.type.label, color = c.inkSoft)
            Gap(8)
            Segmented(listOf("Gym" to "Gym", "Run" to "Run", "Other" to "Other"), label, { label = it })
            Gap(18)
            Text("For how long?", style = HL.type.label, color = c.inkSoft)
            Gap(8)
            Segmented(listOf(30 to "30 min", 45 to "45 min", 60 to "1 h", 90 to "1 h 30"), minutes, { minutes = it })
            Gap(24)
            PillButton("Start capture", { controller.startCapture(label, minutes) }, Modifier.fillMaxWidth(), style = ButtonStyle.PULSE, enabled = conn.state == LinkState.CONNECTED)
            if (conn.state != LinkState.CONNECTED) { Gap(6); MutedText("Connect the watch first.") }
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
            Column(Modifier.fillMaxWidth().paper().padding(vertical = 18.dp)) {
                Text(r.label, style = HL.type.label, color = c.inkSoft)
                Text(clock(elapsed.toDouble()), style = HL.type.hero.copy(fontSize = 96.sp), color = c.ink)
                MutedText("of ${r.plannedS / 60} min, stops by itself")
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
        StatGrid(listOf(
            Triple("Average", fmtInt(a.avgHr), "bpm"), Triple("Max", fmtInt(a.maxHr), "bpm"), Triple("Lowest", fmtInt(a.minHr), "bpm"),
            Triple("Load", a.load?.roundToInt()?.toString() ?: "–", null), Triple("Calories", fmtInt(a.calories), "kcal"), Triple("Steps", fmtInt(a.steps), null),
            Triple("Sets", if (a.sets.isEmpty()) "–" else a.sets.size.toString(), null), Triple("Reps", if (a.sets.isEmpty()) "–" else a.sets.sumOf { it.reps }.toString(), null),
            Triple("Avg rest", a.rests.takeIf { it.isNotEmpty() }?.let { clock(it.map { r -> r.end - r.start }.average()) } ?: "–", null),
        ))
        Gap(20)
        Panel(padding = PaddingValues(12.dp)) {
            Text("Heart rate, sets shaded", style = HL.type.label, color = c.inkSoft)
            Gap(6)
            SessionChart(a.hr, a.durationS, a.zones, a.sets)
            MotionStrip(a.motion, a.durationS)
            Gap(8)
            SegmentBar(a.segments, a.durationS)
            Gap(8)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                a.segments.map { it.kind }.distinct().forEach { k -> LegendDot(segmentColor(k, c), k.replaceFirstChar { it.uppercase() }) }
            }
        }
        if (a.zones.isNotEmpty()) {
            Gap(24); SectionTitle("Heart-rate zones"); Gap(12); ZoneBars(a.zones)
        }
        val parts = a.segments.filter { it.kind != "rest" }
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
        if (a.sets.isNotEmpty()) {
            Gap(24); SectionTitle("Sets"); Gap(4)
            a.sets.forEachIndexed { i, s ->
                val rest = a.rests.firstOrNull { it.start >= s.end - 1 && it.start <= s.end + 5 }
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", style = HL.type.stat.copy(fontSize = 22.sp), color = c.inkSoft, modifier = Modifier.width(36.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${s.reps} reps, ${clock(s.end - s.start)}", style = HL.type.bodyStrong, color = c.ink)
                        MutedText(listOfNotNull(s.tempoS?.let { "%.1f s per rep".format(it) }, rest?.let { "then ${clock(it.end - it.start)} rest" }, rest?.drop60?.let { "recovered $it bpm in 1 min" }).joinToString(", "))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${fmtInt(s.peakHr)} peak", style = HL.type.label, color = c.ink)
                        MutedText("${fmtInt(s.avgHr)} avg")
                    }
                }
                HorizontalDivider(color = c.line)
            }
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
        if (w.route.size >= 2) {
            Gap(20)
            Panel(padding = PaddingValues(12.dp)) {
                Text("Route", style = HL.type.label, color = c.inkSoft); Gap(6); dev.heartline.ui.components.RouteMap(w.route)
            }
        }
        Gap(20)
        Panel(padding = PaddingValues(12.dp)) {
            Text("Heart rate", style = HL.type.label, color = c.inkSoft)
            if (d.zones.isNotEmpty() && w.track.size > 1) {
                Gap(6)
                SessionChart(w.track.map { dev.heartline.app.SeriesPoint((it.ts - w.start).toDouble(), it.bpm.toDouble()) }, w.durationS, d.zones, emptyList())
            } else HrTraceChart(w.track, w.durationS.toLong().coerceAtLeast(60), w.start + w.durationS)
        }
        if (d.zones.isNotEmpty()) { Gap(24); SectionTitle("Heart-rate zones"); Gap(12); ZoneBars(d.zones) }
        listOf(Triple("Speed", d.speedKmh, "km/h"), Triple("Cadence", d.cadence, "spm"), Triple("Altitude", d.altitude, "m")).forEach { (title, s, unit) ->
            if (s.size >= 2) {
                Gap(16)
                Panel(padding = PaddingValues(12.dp)) {
                    Text(title, style = HL.type.label, color = c.inkSoft); Gap(6)
                    SeriesChart(s, if (title == "Speed") c.ok else if (title == "Cadence") c.warn else c.sleepDeep, unit)
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
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(k, style = HL.type.body, color = c.inkSoft, modifier = Modifier.weight(1f)); Text(v, style = HL.type.bodyStrong, color = c.ink)
                }
                HorizontalDivider(color = c.line)
            }
        }
    }
}
