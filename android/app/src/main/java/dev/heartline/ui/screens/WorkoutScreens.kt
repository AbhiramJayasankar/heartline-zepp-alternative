package dev.heartline.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heartline.app.HeartlineController
import dev.heartline.app.LinkState
import dev.heartline.ui.Navigator
import dev.heartline.ui.Route
import dev.heartline.ui.components.ButtonStyle
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.Panel
import dev.heartline.ui.components.PillButton
import dev.heartline.ui.components.SectionTitle
import dev.heartline.ui.components.Segmented
import dev.heartline.ui.components.Stat
import dev.heartline.ui.components.clock
import dev.heartline.ui.components.fmtHm
import dev.heartline.ui.components.fmtInt
import dev.heartline.ui.components.paper
import dev.heartline.ui.theme.HL
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import kotlin.math.roundToInt

/** Three stats per row, so dense workout numbers stay aligned. */
@Composable
internal fun StatGrid(items: List<Triple<String, String, String?>>) {
    items.filter { it.second != "–" }.chunked(3).forEachIndexed { i, row ->
        if (i > 0) Gap(14)
        Row(Modifier.fillMaxWidth()) {
            row.forEach { (label, value, unit) -> Stat(label, value, Modifier.weight(1f), unit = unit) }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

internal fun pace(sPerKm: Double?): String = sPerKm?.takeIf { it in 60.0..3600.0 }?.let { "%d:%02d".format(it.toInt() / 60, it.toInt() % 60) } ?: "–"
internal fun one(v: Double?): String = v?.let { "%.1f".format(it) } ?: "–"

// ---- Capture: start and live ----

@Composable
fun CaptureScreen(controller: HeartlineController, nav: Navigator) {
    val c = HL.colors
    val run by controller.captureRun.collectAsStateWithLifecycle()
    val conn by controller.connection.collectAsStateWithLifecycle()
    val r = run
    // One switch for a session on foot (runs, walks, laps, the walk there and back); otherwise the gym or something else.
    var onFootOn by rememberSaveable { mutableStateOf(false) }
    var kind by rememberSaveable { mutableStateOf("Gym") }
    val label = if (onFootOn) dev.heartline.app.ON_FOOT_LABEL else kind
    var minutes by rememberSaveable { mutableIntStateOf(180) }
    var forFriend by rememberSaveable { mutableStateOf(false) }
    val gpsOn by controller.phoneGps.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    fun located() = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    var gpsDenied by remember { mutableStateOf(false) }
    val askLocation = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        gpsDenied = !ok; if (ok) controller.setPhoneGps(true)
    }
    val onFootLabel = onFootOn
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
    Screen("Workout capture", onBack = { nav.pop() }, subtitle = "Records everything the watch can stream for the whole session, then breaks it down: sets, reps and recovery in the gym; runs, walks and laps on foot.") {
        if (r == null) {
            ToggleRow("Run / walk", "The whole session on foot: the walk there, runs and walks, laps of a loop, the walk back. Heartline splits it into " +
                "runs and walks and finds the laps, however often you change the workout on the watch.", onFootOn) { onFootOn = it }
            if (!onFootLabel) {
                Gap(10)
                Text("What are you doing?", style = HL.type.label, color = c.inkSoft)
                Gap(8)
                Segmented(listOf("Gym" to "Gym", "Other" to "Other"), kind, { kind = it })
            }
            if (onFootLabel) {
                Gap(6)
                ToggleRow("Track with the phone's GPS too", "Carry the phone and it records the route once a second: it keeps to the path better than the watch, " +
                    "so distance, pace and laps of a loop come out right. Start the capture here before you set off: the watch only reports a workout it started once it has ended.", gpsOn && located()) { on ->
                    if (!on) controller.setPhoneGps(false) else if (located()) controller.setPhoneGps(true)
                    else askLocation.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                }
                if (gpsDenied) { Gap(4); MutedText("Heartline needs precise location for this. Allow it under Heartline's permissions in Settings.") }
            }
            Gap(18)
            Text("For how long?", style = HL.type.label, color = c.inkSoft)
            Gap(8)
            Segmented(listOf(45 to "45 min", 60 to "1 h", 90 to "1 h 30", 180 to "Until I stop"), minutes, { minutes = it })
            Gap(10)
            ForFriendToggle("session", forFriend) { forFriend = it }
            Gap(14)
            PillButton(if (forFriend) "Start a friend's capture" else "Start capture", {
                    controller.startCapture(label, minutes, forFriend, phoneGps = onFootLabel && gpsOn && located()); forFriend = false },
                Modifier.fillMaxWidth(), style = ButtonStyle.PULSE, enabled = conn.state == LinkState.CONNECTED)
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
            r.gps?.let { g ->
                Gap(16)
                Row(Modifier.fillMaxWidth()) {
                    Stat("Distance", if (g.distanceM >= 1000) "%.2f".format(g.distanceM / 1000) else g.distanceM.roundToInt().toString(), Modifier.weight(1f),
                        unit = if (g.distanceM >= 1000) "km" else "m")
                    Stat("Pace now", pace(g.paceSPerKm), Modifier.weight(1f), unit = "/km")
                    Stat("Laps", if (g.laps > 0) g.laps.toString() else "–", Modifier.weight(1f), unit = g.lastLapS?.let { "last ${clock(it)}" })
                }
                Gap(6)
                MutedText(when {
                    g.fixes == 0 -> "Phone GPS: waiting for a fix. Outdoors with a view of the sky, it takes up to a minute."
                    else -> "Phone GPS: ${g.fixes} fixes" + (g.accuracyM?.let { ", latest within ${it.roundToInt()} m" } ?: "") +
                        ". Laps show once you've gone round a loop twice."
                })
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
    Row(Modifier.fillMaxWidth().windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars).background(c.ink)
        .clickable(onClick = onOpen).padding(horizontal = Gutter, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        dev.heartline.ui.components.Dot(c.pulse, 8.dp)
        Spacer(Modifier.width(10.dp))
        Text("Capturing ${r.label.lowercase()}, ${clock(((now / 1000) - r.startedAt).coerceAtLeast(0).toDouble())}", style = HL.type.bodyStrong, color = c.paper, modifier = Modifier.weight(1f))
        Text(r.hr?.let { "$it bpm" } ?: "", style = HL.type.label, color = c.paper)
    }
}

/** A session's date as a small calendar leaf: weekday, a large day of the month, and the month. */
@Composable
internal fun DateChip(day: LocalDate) {
    val c = HL.colors
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.width(54.dp).clip(shape).background(c.surface).border(1.dp, c.line, shape).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(day.format(java.time.format.DateTimeFormatter.ofPattern("EEE")), style = HL.type.label, color = c.pulse)
        Text("${day.dayOfMonth}", style = HL.type.stat.copy(fontSize = 24.sp, lineHeight = 26.sp), color = c.ink)
        Text(day.format(java.time.format.DateTimeFormatter.ofPattern("MMM")), style = HL.type.label, color = c.inkSoft)
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
    MutedText("Record everything the watch can measure for a session: sets, reps and rests in the gym, runs, walks and laps on foot.")
    Gap(10)
    PillButton(if (run != null) "Capture running, open" else "Start a capture", { nav.push(Route.Capture) }, style = if (run != null) ButtonStyle.QUIET else ButtonStyle.PULSE)
    Gap(6)
    var deleting by remember { mutableStateOf<dev.heartline.app.CaptureItemUi?>(null) }
    deleting?.let { d -> DeleteCaptureDialog(d.label, d.start, onDelete = { controller.deleteCapture(d.id); deleting = null }, onDismiss = { deleting = null }) }
    list.take(5).forEach { s ->
        // A session that runs past midnight belongs to the evening it started, as in the gym log.
        val day = dev.heartline.app.sessionDate(s.start, java.time.ZoneId.systemDefault())
        val ago = java.time.temporal.ChronoUnit.DAYS.between(day, dev.heartline.app.sessionDate(System.currentTimeMillis() / 1000, java.time.ZoneId.systemDefault()))
        Row(Modifier.fillMaxWidth().clickable { nav.push(Route.CaptureResult(s.id)) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            // The date first and large: which session is which is mostly a question of when.
            DateChip(day)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.label, style = HL.type.bodyStrong, color = c.ink)
                    if (s.friend) FriendTag(Modifier.padding(start = 8.dp))
                }
                MutedText(listOfNotNull(when (ago) { 0L -> "Today"; 1L -> "Yesterday"; in 2L..6L -> "$ago days ago"; else -> null },
                    dev.heartline.ui.components.epochClock(s.start.toDouble()), fmtHm(s.durationS / 60), if (s.onFoot && s.ride) "ride, run and walk" else if (s.onFoot) "run and walk" else s.sets?.let { if (it == 1) "1 set" else "$it sets" }).joinToString(", "))
            }
            // An accidental recording (under 2 minutes, no sets) gets its Delete right here; any other is deleted from its page.
            if (s.durationS < 120 && (s.sets ?: 0) == 0) TextButton({ deleting = s }) { Text("Delete", style = HL.type.label, color = c.pulse) }
            else Column(horizontalAlignment = Alignment.End) {
                Text("${fmtInt(s.avgHr)} bpm", style = HL.type.stat.copy(fontSize = 20.sp), color = c.ink)
                s.load?.let { MutedText("load ${it.roundToInt()}") }
            }
        }
        HorizontalDivider(color = c.line)
    }
}
