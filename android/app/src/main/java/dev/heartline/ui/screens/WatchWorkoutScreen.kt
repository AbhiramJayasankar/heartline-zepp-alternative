package dev.heartline.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heartline.app.HeartlineController
import dev.heartline.app.WorkoutDetailUi
import dev.heartline.ui.Navigator
import dev.heartline.ui.Route
import dev.heartline.ui.components.ChartBand
import dev.heartline.ui.components.ChartLine
import dev.heartline.ui.components.ChartSpan
import dev.heartline.ui.components.InteractiveChart
import dev.heartline.ui.components.HrTraceChart
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.Panel
import dev.heartline.ui.components.SectionTitle
import dev.heartline.ui.components.ZoneBars
import dev.heartline.ui.components.clock
import dev.heartline.ui.components.fmtHm
import dev.heartline.ui.components.fmtInt
import dev.heartline.ui.theme.HL
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import kotlin.math.roundToInt

// ---- Synced watch workouts ----

/** Why a workout's distance and pace aren't the watch's, when its GPS track wandered. */
internal fun gpsNote(g: dev.heartline.app.GpsCheckUi): String {
    val km = g.watchM?.let { "%.2f km".format(it / 1000.0) } ?: "distance"
    val lengths = listOfNotNull(g.walkStepM?.let { "%.2f m a step walking".format(it) }, g.runStepM?.let { "%.2f m running".format(it) }).joinToString(" and ")
    return "The GPS position wandered, so the watch's $km is ${((g.ratio - 1) * 100).roundToInt()}% more than " +
        (if (g.fromSteps) "your steps can cover at $lengths, your step lengths from workouts with clean GPS. Distance and pace here, and each run's and walk's, come from your steps."
        else "the watch's own speed readings add up to. Distance and pace here come from the speed, until Heartline has learnt your step length from workouts with clean GPS.") +
        " Kilometre splits are left out, and this workout doesn't count towards VO2max."
}

@Composable
fun WorkoutScreen(controller: HeartlineController, route: Route.Workout, nav: Navigator) {
    val c = HL.colors
    val day by remember(route.epochDay) { controller.activityDay(LocalDate.ofEpochDay(route.epochDay)) }.collectAsState(initial = null)
    val w = day?.workouts?.firstOrNull { it.start == route.start }
    val friends by controller.friends.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    Screen(w?.type ?: "Workout", onBack = { nav.pop() }, subtitle = w?.let { "${dateTimeOf(it.start)}, ${fmtHm(it.durationS / 60)}" }) {
        if (w == null) { MutedText(if (day == null) "Loading…" else "This workout was deleted."); return@Screen }
        val d = w.detail ?: WorkoutDetailUi()
        if (w.friend) { dev.heartline.ui.components.Note("A friend's workout: shown here, but left out of $LEFT_OUT_OF."); Gap(14) }
        StatGrid(listOf(
            Triple("Average", fmtInt(w.avgHr), "bpm"), Triple("Max", fmtInt(w.maxHr), "bpm"), Triple("Lowest", fmtInt(d.minHr), "bpm"),
            Triple("Duration", fmtHm(w.durationS / 60), null), Triple("Active", d.activeS?.let { fmtHm(it / 60) } ?: "–", null), Triple("Calories", fmtInt(w.calories), "kcal"),
            Triple("Distance", ((d.phoneM ?: d.gps?.distanceM ?: w.distanceM?.takeIf { it > 0 })?.let { "%.2f".format(it / 1000.0) }) ?: "–", "km"),
            Triple("Avg pace", pace(d.avgPaceSPerKm), "/km"), Triple("Best pace", pace(d.bestPaceSPerKm), "/km"),
            Triple("Avg speed", one(d.avgSpeedKmh), "km/h"), Triple("Max speed", one(d.maxSpeedKmh), "km/h"), Triple("Steps", fmtInt(w.steps), null),
            // A zero from the watch on a gym workout means "not measured"; those tiles are left out.
            Triple("Cadence", fmtInt(d.avgCadence?.takeIf { it > 0 }), "spm"), Triple("Max cadence", fmtInt(d.maxCadence?.takeIf { it > 0 }), "spm"), Triple("Stride", fmtInt(d.avgStrideCm?.takeIf { it > 0 }), "cm"),
            Triple("Ascent", d.ascentM?.takeIf { it > 0 }?.roundToInt()?.toString() ?: "–", "m"), Triple("Descent", d.descentM?.takeIf { it > 0 }?.roundToInt()?.toString() ?: "–", "m"), Triple("Load", d.load?.roundToInt()?.toString() ?: "–", null),
            Triple("Training effect", one(d.trainingEffect), null), Triple("Recovery", d.recoveryHours?.let { "$it h" } ?: "–", null),
        ))
        d.gps?.let { g -> Gap(14); dev.heartline.ui.components.Note(gpsNote(g)) }
        d.phoneM?.let { m -> Gap(14); dev.heartline.ui.components.Note("Distance, pace, route and laps come from the phone's GPS, recorded by the capture alongside: " +
            "%.2f km, where the watch said %s.".format(m / 1000.0, w.distanceM?.takeIf { it > 0 }?.let { "%.2f km".format(it / 1000.0) } ?: "nothing")) }
        // A capture recorded over this workout has far finer data: offer it here.
        val captures by controller.captures.collectAsStateWithLifecycle()
        captures.firstOrNull { it.start < w.start + w.durationS && it.start + it.durationS > w.start }?.let { cap ->
            Gap(16)
            // The capture recorded over this workout: one row, linking the two.
            NavRow("Part of your ${cap.label.lowercase()} capture, ${dev.heartline.ui.components.epochClock(cap.start.toDouble())}, ${fmtHm(cap.durationS / 60)}",
                if (cap.onFoot) "The whole session from the watch's raw sensors: every run and walk, laps across all of it, and heart rate second by second."
                else "The watch's raw sensors over the session: " + (cap.sets?.let { if (it == 1) "1 set" else "$it sets" } ?: "sets") + " with every rep, rest recovery and heart-rate kinetics.") {
                nav.push(Route.CaptureResult(cap.id))
            }
        }
        if (w.route.size >= 2) {
            Gap(20)
            Panel(padding = PaddingValues(12.dp)) {
                Text(if (d.gait?.runs?.isNotEmpty() == true) "Route, running and walking coloured" else if (d.ride != null) "Route, riding coloured, stops grey" else "Route", style = HL.type.label, color = c.inkSoft); Gap(6)
                dev.heartline.ui.components.RouteMap(w.route, bouts = d.gait?.routeBouts() ?: d.ride?.routeBouts().orEmpty(), laps = d.loop?.mapLaps().orEmpty(),
                    lapPoint = d.loop?.line?.let { it.lat to it.lon }, title = w.type,
                    routeName = if (d.phoneM != null) "Phone GPS" else "Watch GPS",
                    other = w.watchRoute.takeIf { it.size >= 60 }?.let { dev.heartline.ui.components.MapTrack("Watch GPS", it) },
                    hr = remember(w.track) { w.track.map { dev.heartline.app.SeriesPoint((it.ts - w.start).toDouble(), it.bpm.toDouble()) } }, startEpoch = w.start)
            }
        }
        Gap(20)
        val gymLog by controller.gymLog.collectAsStateWithLifecycle()
        // Timed sets from a gym log for this workout, shaded on the heart rate chart.
        val setSpans = remember(gymLog, w.start) {
            dev.heartline.app.setsForWorkout(gymLog, w.start, w.start + w.durationS, w.type, java.time.ZoneId.systemDefault())
                .filter { it.completed && it.start != null && it.end != null }
                .map { ChartSpan((it.start!! - w.start).toDouble(), (it.end!! - w.start).toDouble(), c.warn.copy(alpha = 0.16f)) } +
                // Runs inside a run-walk, shaded like the route.
                (d.gait?.runs?.takeIf { it.size >= 2 }?.map { ChartSpan(it.start.toDouble(), it.end.toDouble(), c.warn.copy(alpha = 0.12f)) } ?: emptyList())
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
        d.gait?.takeIf { it.runS >= 60 || it.walkS >= 300 }?.let { GaitSection(it, d.phoneM ?: d.gps?.distanceM ?: w.distanceM?.takeIf { d -> d > 0 }) }
        d.ride?.let { RideSection(it, title = "The ride") }
        d.loop?.let { LapsSection(it, when { d.phoneM != null -> LapSource.PHONE; d.gps != null -> LapSource.WATCH_STEPS; else -> LapSource.WATCH }) }
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
            Gap(24); SectionTitle(if (d.laps.all { (it.distanceM ?: 1000) <= 1000 }) "Kilometres" else "Laps"); Gap(4)
            d.laps.forEach { l ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Text(if (l.distanceM != null && l.distanceM < 1000) "%.2f".format(l.distanceM / 1000.0) else "${l.index}", style = HL.type.label, color = c.inkSoft, modifier = Modifier.width(44.dp))
                    Text(clock(l.durationS.toDouble()), style = HL.type.body, color = c.ink, modifier = Modifier.weight(1f))
                    // A whole kilometre's time is its pace, so the pace is only written for laps of another length.
                    Text(if (l.distanceM == 1000) "" else pace(l.paceSPerKm) + " /km", style = HL.type.body, color = c.inkSoft, modifier = Modifier.weight(1f))
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
        // A workout inside a friend's capture or check is theirs through that; it's changed there, not here.
        val own = friends.isFriend(dev.heartline.app.FriendMark.Kind.WORKOUT, w.start.toString())
        WhoseSection("workout", w.friend, { controller.setFriend(dev.heartline.app.FriendMark.Kind.WORKOUT, w.start.toString(), w.start, w.start + w.durationS, it) },
            "Delete this workout", { confirm = true }, lockedBy = if (w.friend && !own) "It ran inside a friend's capture or heart check; change that one to make it yours again." else null)
        if (confirm) ConfirmDelete("Delete this workout?", "${w.type}, ${dateTimeOf(w.start)}. Heartline drops it from your days, trends, workout lists and Health Connect. " +
            "The watch keeps its own copy in its workout history. This can't be undone.",
            onDelete = { confirm = false; controller.deleteWatchWorkout(w.start); nav.pop() }, onDismiss = { confirm = false })
    }
}
