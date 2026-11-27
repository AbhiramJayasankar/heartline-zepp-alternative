package dev.heartline.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heartline.app.CaptureAnalysisUi
import dev.heartline.app.HeartlineController
import dev.heartline.ui.Navigator
import dev.heartline.ui.Route
import dev.heartline.ui.components.ChartBand
import dev.heartline.ui.components.ChartLane
import dev.heartline.ui.components.ChartLine
import dev.heartline.ui.components.ChartSpan
import dev.heartline.ui.components.InteractiveChart
import dev.heartline.ui.components.ButtonStyle
import dev.heartline.ui.components.LegendDot
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.Panel
import dev.heartline.ui.components.PillButton
import dev.heartline.ui.components.SectionTitle
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

// ---- Capture analysis ----

@Composable
fun CaptureResultScreen(controller: HeartlineController, id: String, nav: Navigator, focus: String? = null) {
    val c = HL.colors
    // Scrolls to [focus] (an exercise) once its heading is laid out: opened from another session's link, or coming back.
    val scroll = androidx.compose.foundation.rememberScrollState()
    val tops = remember { androidx.compose.runtime.mutableStateMapOf<String, Float>() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val anchored = focus != null && tops.containsKey(focus)
    LaunchedEffect(focus, anchored) {
        if (!anchored) return@LaunchedEffect
        delay(150)
        val y = tops[focus] ?: return@LaunchedEffect
        scroll.animateScrollTo((scroll.value + y - with(density) { 96.dp.toPx() }).roundToInt().coerceAtLeast(0))
    }
    val result by produceState<CaptureAnalysisUi?>(null, id) { value = controller.captureDetail(id) }
    var confirm by remember { mutableStateOf(false) }
    val a = result
    // With a gym log over this capture: compare with the last session doing the same exercises, and its recording.
    val log by controller.gymLog.collectAsStateWithLifecycle()
    val allCaptures by controller.captures.collectAsStateWithLifecycle()
    // A friend's session isn't matched to your gym log or your other sessions, and its calories aren't worked out from your profile.
    val friend = allCaptures.firstOrNull { it.id == id }?.friend == true
    val captures = remember(allCaptures) { allCaptures.filter { !it.friend } }
    val base = remember(a, log, friend) { if (friend) null else a?.let { dev.heartline.app.sessionInsights(it, log) } }
    val prevId = remember(base, captures) { base?.previous?.let { dev.heartline.app.captureForSession(it, captures) }?.id?.takeIf { it != id } }
    val prev by produceState<CaptureAnalysisUi?>(null, prevId) { value = prevId?.let { controller.captureDetail(it) } }
    val skin by produceState<Pair<Double, Double>?>(null, a?.id, base != null) {
        val cap = a ?: return@produceState
        if (base == null) return@produceState
        val zone = java.time.ZoneId.systemDefault()
        val days = listOf(cap.start, cap.start + cap.durationS).map { java.time.Instant.ofEpochSecond(it).atZone(zone).toLocalDate() }.distinct()
        val temps = days.flatMap { d -> withTimeoutOrNull(5000) { controller.healthDay(d).first() }?.temperature ?: emptyList() }
            .filter { it.t >= cap.start && it.t <= cap.start + cap.durationS }.map { it.v }
        value = temps.takeIf { it.size >= 5 }?.let { it.min() to it.max() }
    }
    // Calories every 5 s: heart rate, the logged treadmill's speed and incline, and the watch's own workouts.
    val prefs by controller.prefs.collectAsStateWithLifecycle()
    val trends by controller.trends.collectAsStateWithLifecycle()
    val energyOf: (CaptureAnalysisUi) -> dev.heartline.app.CaptureEnergyUi? = remember(log, prefs.profile, trends?.hrRest, trends?.vo2max) {
        { x -> dev.heartline.app.captureEnergy(x, log, prefs.profile, trends?.hrRest, trends?.vo2max) }
    }
    val energy = remember(a, energyOf, friend) { if (friend) null else a?.let(energyOf) }
    // A run or walk reads as one: its intervals instead of sets, reps and rests.
    val gait = remember(a) { a?.let { dev.heartline.app.captureGait(it) } }
    // A ride in it (a watch Cycling workout, or a stretch at bike speed): its seconds are left out of the running and walking.
    val ride = remember(a) { a?.let { dev.heartline.app.captureRide(it) } }
    val rideSpeed = remember(a, ride) { if (ride == null || a == null) emptyList() else dev.heartline.app.captureRideSpeed(a) }
    val onFoot = a != null && dev.heartline.app.captureOnFoot(a, gait)
    // With the phone's GPS: its distance, and laps of a loop in its track.
    val phoneM = remember(a) { a?.let { dev.heartline.app.capturePhoneDistanceM(it) } }
    // Laps only for a session on foot: a gym session that passes the same spot isn't going round a loop.
    val laps = remember(a, gait, onFoot) { if (onFoot) a?.let { dev.heartline.app.captureLaps(it, gait) } else null }
    val ins = remember(base, prev, skin, onFoot) { if (base == null || a == null || onFoot) null else dev.heartline.app.sessionInsights(a, log, prev?.recovery ?: emptyList(), skin) }
    Screen(a?.label ?: "Session", onBack = { nav.pop() }, subtitle = a?.let { "${dateTimeOf(it.start)}, ${fmtHm(it.durationS / 60)}" }, scroll = scroll,
        trailing = { TextButton({ confirm = true }) { Text("Delete", color = c.inkSoft) } }) {
        if (a == null) {
            MutedText("Analysing the recording…")
            Gap(24); PillButton("Delete this recording", { confirm = true }, style = ButtonStyle.QUIET)
            return@Screen
        }
        val restLengths = a.rests.filter { r -> r.end - r.start <= 600 && a.cardio.none { it.start < r.end && it.end > r.start } }.map { it.end - it.start }.sorted()
        val medianRest = restLengths.getOrNull(restLengths.size / 2)
        if (friend) { dev.heartline.ui.components.Note("A friend's session: read on its own, not matched to your gym log, your days or your other sessions."); Gap(14) }
        val watchDistance = a.activities.mapNotNull { it.distanceM }.filter { it > 0 }.takeIf { it.isNotEmpty() }?.sum()
        if (onFoot) StatGrid(listOf(
            Triple("Average", fmtInt(a.avgHr), "bpm"), Triple("Max", fmtInt(a.maxHr), "bpm"), Triple("Lowest", fmtInt(a.minHr), "bpm"),
            Triple("Load", a.load?.roundToInt()?.toString() ?: "–", null), Triple("Calories", fmtInt(energy?.total?.roundToInt() ?: a.calories), "kcal"), Triple("Steps", fmtInt(a.steps), null),
            Triple("Distance", (phoneM ?: watchDistance?.toDouble())?.let { "%.2f".format(it / 1000.0) } ?: "–", "km"),
            // Over the seconds the track covers: a capture often runs on before and after the workouts in it.
            if (ride != null) Triple("Riding", ride.avgKmh?.let { "%.1f".format(it) } ?: "–", "km/h")
            else Triple("Pace", pace(phoneM?.takeIf { it >= 100 }?.let { m -> dev.heartline.app.captureCoveredS(a) * 1000.0 / m }), "/km"),
        )) else StatGrid(listOf(
            Triple("Average", fmtInt(a.avgHr), "bpm"), Triple("Max", fmtInt(a.maxHr), "bpm"), Triple("Lowest", fmtInt(a.minHr), "bpm"),
            Triple("Load", a.load?.roundToInt()?.toString() ?: "–", null), Triple("Calories", fmtInt(energy?.total?.roundToInt() ?: a.calories), "kcal"), Triple("Steps", fmtInt(a.steps), null),
            Triple("Sets", if (a.sets.isEmpty()) "–" else a.sets.size.toString(), null),
            // With a log, its reps are the true count; the watch's own count is compared set by set below.
            // Logged partial reps (5.4) count as whole reps here; the sets below keep the partial.
            Triple("Reps", if (a.sets.isEmpty()) "–" else a.sets.sumOf { s -> s.loggedReps?.let { kotlin.math.floor(it) } ?: s.reps.toDouble() }.roundToInt().toString(), null),
            Triple("Median rest", medianRest?.let { clock(it) } ?: "–", null),
            Triple("Under load", a.workS?.let { fmtHm(((it + 30) / 60)) } ?: "–", null),
            Triple("Work share", a.workS?.let { w -> a.restS?.takeIf { w + it > 0 }?.let { "${100 * w / (w + it)}" } } ?: "–", "%"),
            Triple("Exercises", if (a.sets.isEmpty()) "–" else a.sets.map { it.group }.distinct().size.toString(), null),
        ) + listOfNotNull(ins?.takeIf { it.volumeKg > 0 }?.let { Triple("Volume", "%,d".format(it.volumeKg.roundToInt()), "kg") }))
        if (a.route.size >= 2) {
            Gap(20)
            Panel(padding = PaddingValues(12.dp)) {
                val from = if (a.routeSource == dev.heartline.app.RouteSource.PHONE) "Route from the phone's GPS" else "Route from the watch's workouts"
                Text(when { ride != null -> "$from, riding, running and walking coloured"; gait?.runs?.isNotEmpty() == true -> "$from, running and walking coloured"; else -> from },
                    style = HL.type.label, color = c.inkSoft); Gap(6)
                dev.heartline.ui.components.RouteMap(a.route, bouts = (ride?.routeBouts().orEmpty() + gait?.routeBouts().orEmpty()).sortedBy { it.start },
                    laps = laps?.mapLaps().orEmpty(),
                    lapPoint = laps?.line?.let { it.lat to it.lon }, title = a.label,
                    routeName = if (a.routeSource == dev.heartline.app.RouteSource.PHONE) "Phone GPS" else "Watch GPS",
                    other = a.watchRoute.takeIf { it.size >= 60 && a.routeSource == dev.heartline.app.RouteSource.PHONE }?.let { dev.heartline.ui.components.MapTrack("Watch GPS", it) },
                    hr = a.hr, startEpoch = a.start)
            }
        }
        Gap(20)
        Panel(padding = PaddingValues(12.dp)) {
            Text(if (onFoot) "Heart rate, with what your feet were doing underneath" else if (a.floor.size > 1) "Heart rate, sets shaded by exercise, dashed line is your lowest per 5 min" else "Heart rate, sets shaded", style = HL.type.label, color = c.inkSoft)
            Gap(6)
            val setLabels = if (onFoot) emptyList() else a.sets.mapIndexed { i, s -> ChartSpan(s.start, s.end, dev.heartline.ui.components.groupColor(s.group, c).copy(alpha = 0.25f),
                if (s.exercise != null) "${s.exercise}, ${s.setLabel?.lowercase()}" + (loadReps(s)?.let { ", $it" } ?: "") else "Set ${i + 1}, ${s.reps} reps") }
            val cardioSpans = if (onFoot) emptyList() else a.cardio.map { k -> ChartSpan(k.start, k.end, c.warn.copy(alpha = 0.15f), k.name ?: k.kind.replaceFirstChar { it.uppercase() }) }
            // Under the plot, on its time axis: each exercise's run of sets by name, how hard the arm moved, what the
            // motion looked like, and the watch's own workouts.
            val runs = ArrayList<MutableList<dev.heartline.app.SetUi>>()
            if (!onFoot) a.sets.forEach { s -> if (runs.lastOrNull()?.last()?.group == s.group) runs.last().add(s) else runs.add(mutableListOf(s)) }
            val motionStep = a.motion.zipWithNext { p, q -> q.t - p.t }.filter { it > 0 }.sorted().let { if (it.isEmpty()) 5.0 else it[it.size / 2] }
            val lanes = listOfNotNull(
                runs.takeIf { it.isNotEmpty() }?.let { rs -> ChartLane("Lift", 16.dp, spans = rs.mapIndexed { i, r ->
                    ChartSpan(r.first().start, r.last().end, dev.heartline.ui.components.groupColor(r.first().group, c).copy(alpha = 0.32f),
                        r.first().exercise?.let(::shortExercise) ?: "Exercise ${exerciseLetter(i)}") }, named = true, inReadout = false) },
                a.motion.takeIf { it.isNotEmpty() }?.let { m -> ChartLane("Motion", 24.dp, bars = m, barWidth = motionStep, barColor = c.inkSoft.copy(alpha = 0.85f),
                    readout = { t -> m.lastOrNull { it.t <= t && t < it.t + motionStep }?.let { p -> "%.2f g".format(p.v) } }) },
                // On foot: running and walking from the strides; the gym's task labels (lifting, rest) mean nothing here.
                if (onFoot) (gait?.bouts.orEmpty().map { b -> ChartSpan(b.start.toDouble(), b.end.toDouble(), if (b.running) c.warn else c.ok.copy(alpha = 0.55f), if (b.running) "Running" else "Walking") } +
                    ride?.bouts.orEmpty().filter { it.riding }.map { b -> ChartSpan(b.start.toDouble(), b.end.toDouble(), c.oxygen, "Riding") })
                    .takeIf { it.isNotEmpty() }?.let { sp -> ChartLane("Feet", 10.dp, spans = sp.sortedBy { it.start }) }
                else a.segments.takeIf { it.isNotEmpty() }?.let { sg -> ChartLane("Task", 10.dp, spans = sg.map { s -> ChartSpan(s.start, s.end, segmentColor(s.kind, c), s.kind.replaceFirstChar { it.uppercase() }) }) },
                a.activities.takeIf { it.isNotEmpty() }?.let { ws -> ChartLane("Watch", 16.dp, spans = ws.map { w -> ChartSpan(w.start, w.end, c.ink.copy(alpha = 0.12f), w.type) }, named = true) },
            )
            InteractiveChart(
                listOf(ChartLine(a.hr, c.pulse, "Heart rate", "bpm", gap = 30.0)) +
                    if (a.floor.size > 1 && !onFoot) listOf(ChartLine(a.floor, c.pulse.copy(alpha = 0.7f), "5-min low", "bpm", width = 1.5.dp, dashed = true, step = true, gap = 400.0)) else emptyList(),
                0.0, a.durationS.toDouble().coerceAtLeast(60.0), height = 210.dp, spans = setLabels + cardioSpans,
                bands = a.zones.filter { it.zone >= 3 }.map { z -> ChartBand(z.lowBpm.toDouble(), (z.highBpm ?: 240).toDouble(), dev.heartline.ui.components.zoneColor(z.zone, c).copy(alpha = 0.06f)) },
                xLabel = { clock(it) }, readoutX = { t -> "${clock(t)} in, at " + dev.heartline.ui.components.epochClock(a.start + t, seconds = true) +
                    (energy?.let { e -> ", ${e.at(t).roundToInt()} kcal so far" } ?: "") }, minSpan = 60.0,
                idleHint = if (onFoot) "Touch for heart rate at that moment. Pinch to zoom into an interval." else "Touch for heart rate and the set at that moment. Pinch to zoom into a set.", lanes = lanes, axisInside = true)
            Gap(8)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (onFoot) { LegendDot(c.warn, "Running"); LegendDot(c.ok, "Walking"); if (ride != null) LegendDot(c.oxygen, "Riding"); LegendDot(c.line, "Standing") }
                else a.segments.map { it.kind }.distinct().forEach { k -> LegendDot(segmentColor(k, c), k.replaceFirstChar { it.uppercase() }) }
                if (a.activities.isNotEmpty()) LegendDot(c.ink.copy(alpha = 0.3f), "Watch workout")
            }
        }
        if (onFoot) ride?.let { RideSection(it, rideSpeed) }
        // With a ride, the phone's distance and the watch's include it: the runs and walks carry their own.
        if (onFoot) gait?.let { GaitSection(it, if (ride != null) null else phoneM?.roundToInt() ?: watchDistance) } else BaselineNote(a)
        if (onFoot) laps?.let { LapsSection(it, when (a.routeSource) { dev.heartline.app.RouteSource.PHONE -> LapSource.PHONE
            dev.heartline.app.RouteSource.WATCH -> LapSource.WATCH; dev.heartline.app.RouteSource.WATCH_STEPS -> LapSource.WATCH_STEPS }) }
        ins?.let { Findings(it) }
        ins?.let { i -> CoachNote(controller, a, i) }
        if (onFoot && a.activities.isNotEmpty()) WatchWorkouts(controller, a, nav, energy, onFoot = true)
        if (!friend) AroundSession(controller, a, nav)
        if (!onFoot && a.activities.isNotEmpty()) WatchWorkouts(controller, a, nav, energy)
        if (a.sets.isNotEmpty() && !onFoot) Exercises(a, ins, energy, anchor = { key -> androidx.compose.ui.Modifier.onGloballyPositioned { tops[key] = it.positionInRoot().y } },
            openPrev = { e ->
                // The same exercise in the session before: open its recording there. This page is kept in the back
                // stack focused on the exercise, so Back returns to the same spot.
                // With no recording of it, the session in the gym log instead.
                val prevSession = e.prev?.session
                val prevCap = prevSession?.let { dev.heartline.app.captureForSession(it, captures) }?.takeIf { it.id != id }
                prevSession?.let { ps -> {
                    if (nav.stack.isNotEmpty()) nav.stack[nav.stack.lastIndex] = Route.CaptureResult(id, e.exercise)
                    nav.push(prevCap?.let { Route.CaptureResult(it.id, e.prev!!.name) } ?: Route.GymLogAt("${ps.date}|${ps.name}"))
                } }
            })
        ins?.let { RestRepsSection(it); HeartCost(it) }
        if (a.recovery.size >= 2 && !onFoot) Recovery(a, medianRest, prev?.recovery ?: emptyList(), ins?.previous?.date)
        ins?.let { FloorSection(a, it) }
        if (a.logTotal != null && !onFoot) LogAndWatch(a, ins?.score)
        // A short walk between machines isn't cardio worth a section; the watch's cardio workouts and 5 minutes or more are.
        // On foot, the runs, walks and laps above already cover these stretches.
        if (!onFoot) a.cardio.filter { it.name != null || it.end - it.start >= 300 }.forEach { k ->
            // The same kind of block (the watch's name for it, a treadmill say) in the latest earlier recording that has one.
            val prevCardio by produceState<Pair<CaptureAnalysisUi, dev.heartline.app.CardioUi>?>(null, a.id, k.name, captures.size) {
                val name = k.name?.takeIf { !friend } ?: return@produceState
                value = captures.filter { it.start < a.start && it.id != id }.sortedByDescending { it.start }.take(8).firstNotNullOfOrNull { item ->
                    controller.captureDetail(item.id)?.let { d -> d.cardio.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { d to it } }
                }
            }
            val key = "cardio:${k.name ?: k.kind}"
            CardioBlock(a, k, log, prevCardio, energyOf = energyOf, anchor = Modifier.onGloballyPositioned { tops[key] = it.positionInRoot().y },
                onOpenPrev = prevCardio?.let { (pa, pk) -> {
                    if (nav.stack.isNotEmpty()) nav.stack[nav.stack.lastIndex] = Route.CaptureResult(id, key)
                    nav.push(Route.CaptureResult(pa.id, "cardio:${pk.name ?: pk.kind}"))
                } })
        }
        energy?.let { CaloriesSection(a, it, onFoot, gait, ride) }
        a.pulse?.let { PulseSection(it) }
        if (a.zones.isNotEmpty()) {
            Gap(24); SectionTitle("Heart-rate zones"); Gap(12); ZoneBars(a.zones)
        }
        val gait = a.segments.filter { it.kind == "walking" || it.kind == "running" }
        val parts = gait.filter { it.end - it.start >= 120 }
        val shortWalks = gait.filter { it.end - it.start < 120 }
        if (parts.isNotEmpty() && !onFoot) {
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
        if (shortWalks.isNotEmpty() && !onFoot) {
            Gap(if (parts.isEmpty()) 24 else 10)
            MutedText("Plus ${shortWalks.size} short walk${if (shortWalks.size == 1) "" else "s"} between sets, ${clock(shortWalks.sumOf { it.end - it.start })} in all" +
                (shortWalks.mapNotNull { it.cadenceSpm }.takeIf { it.isNotEmpty() }?.let { ", about ${it.average().roundToInt()} steps/min" } ?: "") + ".")
        }
        Gap(24)
        SectionTitle("How this was measured")
        Gap(4)
        MutedText(a.hrSource)
        // The notes about telling exercises and reps apart are for the gym.
        a.notes.filter { !onFoot || !Regex("exercise|repetition", RegexOption.IGNORE_CASE).containsMatchIn(it) }.forEach { Gap(4); MutedText(it) }
        WhoseSection("session", friend, { controller.setFriend(dev.heartline.app.FriendMark.Kind.CAPTURE, id, a.start, a.start + a.durationS, it) }, "Delete this recording", { confirm = true })
    }
    if (confirm) DeleteCaptureDialog(a?.label ?: "this session", a?.start, onDelete = { controller.deleteCapture(id); confirm = false; nav.pop() }, onDismiss = { confirm = false })
}

/** Asks before deleting a capture, naming which one: deleting can't be undone. */
@Composable
internal fun DeleteCaptureDialog(label: String, start: Long?, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val c = HL.colors
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.surface,
        title = { Text("Delete $label?", style = HL.type.section, color = c.ink) },
        text = { MutedText((start?.let { "Recorded ${dateTimeOf(it)}. " } ?: "") + "The recording and its analysis are removed from this phone. This can't be undone; your gym log is kept.") },
        confirmButton = { TextButton(onDelete) { Text("Delete", color = c.pulse) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = c.inkSoft) } },
    )
}

/** A panel ruled like ECG paper, for the charts that plot heart signals. */
@Composable
internal fun PaperPanel(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val c = HL.colors
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape).padding(12.dp), content = content)
}

@Composable
internal fun BaselineNote(a: CaptureAnalysisUi) {
    if (a.floor.size < 3) return
    val first = a.floor.first().v.roundToInt(); val top = a.floor.maxBy { it.v }; val last = a.floor.last().v.roundToInt()
    if (top.v.roundToInt() - first < 8) return
    Gap(10)
    MutedText("Your lowest heart rate climbed from $first bpm at the start to ${top.v.roundToInt()} around ${clock(top.t)}, and was $last by the end. Each set starts from a higher floor as the session goes on.")
}

/** One label with the watch's figure and the raw sensors' figure side by side. */
@Composable
internal fun CompareRow(label: String, watch: String, sensors: String) {
    val c = HL.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(label, style = HL.type.body, color = c.inkSoft, modifier = Modifier.weight(1.2f))
        Text(watch, style = HL.type.bodyStrong, color = c.ink, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        Text(sensors, style = HL.type.bodyStrong, color = c.ink, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
    HorizontalDivider(color = c.line)
}

/**
 * A short note from the AI, written from this page's own numbers (sets, rests, reps in reserve, last time, what stood
 * out), with up to three things to try next session. Kept per session; written again only when those numbers change.
 */
@Composable
internal fun CoachNote(controller: HeartlineController, a: CaptureAnalysisUi, ins: dev.heartline.app.SessionInsights) {
    val c = HL.colors
    val trends by controller.trends.collectAsStateWithLifecycle()
    val day = dev.heartline.app.sessionDate(a.start, java.time.ZoneId.systemDefault())
    fun value(key: String) = trends?.series?.firstOrNull { it.key == key }?.points?.firstOrNull { it.date == day }?.value
    // Readiness and sleep going in, once the daily figures are in (null until then, so the note doesn't wait on them twice).
    val going = trends?.let {
        listOfNotNull(value("readiness")?.let { "readiness ${it.roundToInt()}" }, value("sleep")?.let { "slept ${fmtHm((it * 60).roundToInt())} the night before" })
            .takeIf { l -> l.isNotEmpty() }?.joinToString(", ", prefix = "Going in: ", postfix = ".")
    }
    val facts = remember(a, ins, going) { dev.heartline.app.coachFacts(a, ins, going) }
    var refresh by remember { mutableStateOf(0) }
    val ready = trends != null
    // A saved note shows at once. A first note waits until the page's comparisons (the last session, skin temperature,
    // the day's readiness) have stopped arriving, so it is written once from the settled numbers.
    // "Write it again" is one request: [handled] is the last one answered, so later changes in the numbers don't repeat it.
    val handled = remember { intArrayOf(0) }
    val saved by produceState<dev.heartline.app.CoachUi?>(null, a.id) { value = controller.savedCaptureCoach(a.id) }
    val state by produceState<Pair<dev.heartline.app.CoachUi?, String?>?>(null, facts, refresh, ready, saved) {
        val again = refresh > handled[0]
        if (saved != null && !again) { value = controller.captureCoach(a.id, facts, false) to null; return@produceState }
        value = null
        if (!ready) return@produceState
        if (!again) delay(2500)
        handled[0] = refresh
        value = try { controller.captureCoach(a.id, facts, again) to null }
            catch (e: Exception) { dev.heartline.ble.rethrowIfCancelled(e); null to (e.message ?: "The coach couldn't write a note just now.") }
    }
    Gap(24); SectionTitle("Coach's note"); Gap(8)
    Panel(wash = true, padding = PaddingValues(14.dp)) {
        val s = state
        when {
            s == null -> MutedText("Reading this session's numbers…")
            s.first != null -> {
                val note = s.first!!
                Text(note.note, style = HL.type.body, color = c.ink)
                if (note.next.isNotEmpty()) {
                    Gap(12)
                    Text("Next time", style = HL.type.label, color = c.inkSoft)
                    note.next.forEach { line ->
                        Gap(6)
                        Row {
                            Text("›", style = HL.type.bodyStrong, color = c.pulse, modifier = Modifier.width(16.dp))
                            Text(line, style = HL.type.body, color = c.ink)
                        }
                    }
                }
                Gap(10)
                MutedText("Written by ${note.model.removePrefix("models/")} from the numbers on this page. If it disagrees with them, the measured numbers win." +
                    if (note.stale) " This page's numbers have changed since it was written." else "")
                TextButton({ refresh++ }, contentPadding = PaddingValues(0.dp)) { Text("Write it again", style = HL.type.label, color = c.pulse) }
            }
            else -> {
                MutedText(s.second ?: "The coach couldn't write a note just now.")
                TextButton({ refresh++ }, contentPadding = PaddingValues(0.dp)) { Text("Try again", style = HL.type.label, color = c.pulse) }
            }
        }
    }
}

/** How the session sat in the rest of the day, each line opening the page it comes from. */
@Composable
internal fun AroundSession(controller: HeartlineController, a: CaptureAnalysisUi, nav: Navigator) {
    val zone = java.time.ZoneId.systemDefault()
    val trends by controller.trends.collectAsStateWithLifecycle()
    val checks by controller.checks.collectAsStateWithLifecycle()
    val day = dev.heartline.app.sessionDate(a.start, zone)
    fun value(key: String, d: java.time.LocalDate) = trends?.series?.firstOrNull { it.key == key }?.points?.firstOrNull { it.date == d }?.value
    val end = a.start + a.durationS
    val rows = buildList<Triple<String, String, () -> Unit>> {
        val readiness = value("readiness", day); val sleepH = value("sleep", day); val sleepScore = value("sleep_score", day)
        if (readiness != null || sleepH != null) add(Triple("Going in",
            listOfNotNull(readiness?.let { "readiness ${it.roundToInt()}" }, sleepH?.let { "${fmtHm((it * 60).roundToInt())} asleep the night before" + (sleepScore?.let { s -> ", score ${s.roundToInt()}" } ?: "") }).joinToString("; ").replaceFirstChar { it.uppercase() },
        ) { nav.push(Route.Trend("readiness")) })
        // The day's load is Banister TRIMP like this session's; strain is 21 x (1 - e^(-TRIMP/120)).
        val strain = value("strain", day)
        val dayLoad = strain?.takeIf { it < 21 }?.let { -120 * kotlin.math.ln(1 - it / 21) }
        a.load?.let { l -> add(Triple("Load", if (dayLoad != null && dayLoad >= 1)
            "${l.roundToInt()} of the day's ${dayLoad.roundToInt()}, strain ${"%.1f".format(strain)} of 21" + (if (l > dayLoad * 1.05) ". The day's figure uses minute averages, so it can read below a session's" else "")
            else "${l.roundToInt()}, on the same scale as the day's load and strain") { nav.push(Route.Trend("strain")) }) }
        // The first heart check within three hours of finishing: how far heart rate and HRV had come back.
        checks.filter { !it.friend && it.start >= end && it.start <= end + 3 * 3600 && it.rmssd != null }.minByOrNull { it.start }?.let { k ->
            add(Triple("Heart check after", "HRV ${k.rmssd!!.roundToInt()} ms at ${k.hr?.roundToInt() ?: "–"} bpm, ${((k.start - end) / 60).coerceAtLeast(0)} min after you finished") { nav.push(Route.CheckResult(k.id)) })
        }
        val next = day.plusDays(1)
        val rhr = value("rhr", next); val usual = trends?.series?.firstOrNull { it.key == "rhr" }?.baseline; val hrvNight = value("hrv_sleep", next)
        if (rhr != null) add(Triple("The night after",
            "sleeping heart rate ${rhr.roundToInt()} bpm" + (usual?.let { " (usual ${it.roundToInt()})" } ?: "") + (hrvNight?.let { ", overnight HRV ${it.roundToInt()} ms" } ?: "")
        ) { nav.push(Route.Trend("rhr")) })
        add(Triple("Heart rate that day", "the session shaded among the rest of ${day.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))}") {
            nav.push(dev.heartline.ui.screens.dayRoute(DayMetric.HEART, day.toEpochDay())) })
    }
    Gap(24); SectionTitle("Around this session"); Gap(4)
    MutedText("How the session sat in the rest of your day. Each line opens the page it comes from.")
    Gap(6)
    rows.forEach { (title, detail, go) -> NavRow(title, detail, go) }
}

@Composable
internal fun WatchWorkouts(controller: HeartlineController, a: CaptureAnalysisUi, nav: Navigator, energy: dev.heartline.app.CaptureEnergyUi?, onFoot: Boolean = false) {
    val c = HL.colors
    // The watch's own workout pages (laps, zones, its HR track): matched by overlap, since the capture clips their times.
    val day = java.time.Instant.ofEpochSecond(a.start).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val watchDay by produceState<List<dev.heartline.app.WorkoutUi>>(emptyList(), a.id) {
        value = withTimeoutOrNull(5000) { (controller.activityDay(day).first().workouts + controller.activityDay(day.plusDays(1)).first().workouts) } ?: emptyList()
    }
    fun open(w: dev.heartline.app.CaptureActivityUi): (() -> Unit)? {
        val abs = a.start + w.start; val absEnd = a.start + w.end
        return watchDay.firstOrNull { it.type == w.type && it.start < absEnd && it.start + it.durationS > abs }?.let { ww -> {
            nav.push(Route.Workout(ww.start, java.time.Instant.ofEpochSecond(ww.start).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()))
        } }
    }
    if (onFoot) {
        // A run or walk: one line per watch workout, opening its own page; the figures above already merge them.
        Gap(24); SectionTitle("The watch's own workouts"); Gap(4)
        MutedText("The workouts you started on the watch during this session, with their distances corrected as on their own pages. Each opens its page.")
        Gap(6)
        a.activities.forEach { w ->
            val detail = listOfNotNull(dev.heartline.ui.components.epochClock(a.start + w.start), (w.activeS ?: (w.end - w.start).roundToInt()).let { fmtHm((it + 30) / 60) },
                w.distanceM?.takeIf { it > 0 }?.let { "%.2f km".format(it / 1000.0) }, w.avgHr?.let { "$it bpm" }, w.calories?.let { "$it kcal" }).joinToString(", ")
            val go = open(w)
            if (go != null) NavRow(w.type, detail, go) else {
                Gap(10); Text(w.type, style = HL.type.bodyStrong, color = c.ink); MutedText(detail); Gap(10); HorizontalDivider(color = c.line)
            }
        }
        return
    }
    Gap(24); SectionTitle("The watch's own workouts"); Gap(4)
    MutedText("What the watch logged for the workouts you started on it, beside what the raw sensors measured over the same minutes. " +
        if (onFoot) "The sensors see each stride and each change of pace; the watch adds GPS distance, its route and its calorie estimate."
        else "The sensors see individual sets and reps; the watch adds names, distance and its calorie estimate.")
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
        // A strength workout's sensor figure is time in sets; a short walk inside it isn't its active time.
        CompareRow("Active time", w.activeS?.let { clock(it.toDouble()) } ?: "–",
            when { sets.isNotEmpty() -> clock(sets.sumOf { it.end - it.start }) + " in sets"; cardio.isNotEmpty() -> clock(cardio.sumOf { it.end - it.start }); else -> "–" })
        CompareRow("Steps", fmtInt(w.steps), if (steps > 0) fmtInt(steps) else "–")
        if (w.distanceM != null && w.distanceM > 0) CompareRow("Distance", "%.2f km".format(w.distanceM / 1000.0), "–")
        CompareRow("Calories", w.calories?.let { "$it kcal" } ?: "–", energy?.let { "${it.between(w.start, w.end).roundToInt()} kcal" } ?: "–")
        if (sets.isNotEmpty()) CompareRow("Sets / reps", "–", "${sets.size} / ${sets.sumOf { it.reps }}")
        open(w)?.let { go -> NavRow("The watch's ${w.type.lowercase()} workout", "its own page: heart-rate track, zones and splits", go) }
    }
}
