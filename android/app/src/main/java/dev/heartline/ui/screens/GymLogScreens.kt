package dev.heartline.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heartline.app.HeartlineController
import dev.heartline.app.LoggedSet
import dev.heartline.app.WorkoutUi
import dev.heartline.app.sessionsOf
import dev.heartline.app.setsForWorkout
import dev.heartline.ui.Navigator
import dev.heartline.ui.components.ButtonStyle
import dev.heartline.ui.components.Heading
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.Note
import dev.heartline.ui.components.Panel
import dev.heartline.ui.components.PillButton
import dev.heartline.ui.components.SectionTitle
import dev.heartline.ui.components.fmtInt
import dev.heartline.ui.theme.HL
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private fun num(v: Double): String = if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else "%.1f".format(v)

/** One set in a short form: "45 kg x 8", "assist 55 kg x 8", "3.6 km/h, 20%, 10:03". */
internal fun setText(s: LoggedSet): String = when {
    s.cardio -> listOfNotNull(s.speedKmh?.let { "${num(it)} km/h" }, s.inclinePct?.let { "${num(it)}%" },
        s.durationS?.let { "%d:%02d".format(it.toInt() / 60, it.toInt() % 60) }, s.distanceKm?.let { "%.2f km".format(it) }).joinToString(", ").ifEmpty { "done" }
    else -> buildString {
        when {
            s.assistKg != null -> append("assist ${num(s.assistKg)} kg")
            s.loadKg != null -> append("${num(s.loadKg)} kg")
        }
        s.reps?.let { append(if (isEmpty()) "${num(it)} reps" else " × ${num(it)}") }
        if (s.failure) append(" to failure")
        if (isEmpty()) append("done")
    }
}

/** Sets grouped by exercise, in the order done, with warm-ups and skipped sets marked. */
@Composable
internal fun ExerciseSets(sets: List<LoggedSet>) {
    val c = HL.colors
    sets.groupBy { it.exercise }.forEach { (exercise, rows) ->
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Text(exercise, style = HL.type.bodyStrong, color = c.ink)
            rows.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (s.setType.startsWith("warm")) "W" else "${s.setNumber}", style = HL.type.small, color = c.inkSoft, modifier = Modifier.width(22.dp))
                    Text(setText(s), style = HL.type.body, color = if (s.completed) c.ink else c.inkSoft, modifier = Modifier.weight(1f))
                    val note = when {
                        !s.completed -> "skipped"
                        s.start != null && s.end != null -> "${s.end - s.start} s"
                        else -> null
                    }
                    note?.let { Text(it, style = HL.type.small, color = c.inkSoft) }
                }
            }
        }
        HorizontalDivider(color = c.line)
    }
}

private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM")

/** Every gym log imported: sessions newest first, each opening to its sets. */
@Composable
fun GymLogScreen(controller: HeartlineController, nav: Navigator) {
    val c = HL.colors
    val log by controller.gymLog.collectAsStateWithLifecycle()
    val result by controller.gymImport.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) controller.importGymLog(uris) }
    val sessions = remember(log) { sessionsOf(log) }
    val captures by controller.captures.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    Screen("Gym log", onBack = { nav.pop() }, subtitle = "Sets, loads and treadmill settings from your workout logs.",
        icon = dev.heartline.ui.Icons.activity, tint = c.warn) {
        PillButton("Import log files", { picker.launch(arrayOf("text/*", "application/json", "application/octet-stream", "*/*")) })
        Gap(8)
        MutedText("CSV or JSON workout logs. You can pick several at once; a set already here is kept once, with the copy that has times.")
        result?.let { Gap(12); Panel(wash = true, padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) { Text(it, style = HL.type.body, color = c.ink) } }
        Gap(20)
        if (sessions.isEmpty()) { MutedText("No sessions yet. Import a log to see it here and on the matching watch workout."); return@Screen }
        Heading("Sessions")
        sessions.forEach { s ->
            val id = "${s.date}|${s.name}"
            Column(Modifier.fillMaxWidth().clickable(onClickLabel = if (open == id) "Hide sets" else "Show sets", role = Role.Button) { open = if (open == id) null else id }
                .padding(vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name.replaceFirstChar { it.uppercase() }, style = HL.type.bodyStrong, color = c.ink)
                        MutedText(listOfNotNull(s.date.format(dayFmt),
                            s.start?.let { a -> s.end?.let { b -> "${timeOf(a)} to ${timeOf(b)}" } } ?: "no times in the log",
                            "${s.workingSets} working sets").joinToString(", "))
                    }
                    if (s.volumeKg > 0) Column(horizontalAlignment = Alignment.End) {
                        Text("${fmtInt(s.volumeKg.roundToInt())} kg", style = HL.type.bodyStrong, color = c.ink)
                        MutedText("volume")
                    }
                }
            }
            if (open == id) Column(Modifier.padding(start = 8.dp, bottom = 8.dp)) {
                // The recording over the same time has the sets merged with heart rate and the watch's rep counts.
                val a = s.start; val b = s.end
                captures.filter { cap -> a != null && b != null && cap.start < b && cap.start + cap.durationS > a }.forEach { cap ->
                    NavRow("Open the recording", "${timeOf(cap.start)}, ${cap.durationS / 60} min, sets with heart rate and the watch's rep counts") {
                        nav.push(dev.heartline.ui.Route.CaptureResult(cap.id))
                    }
                }
                ExerciseSets(s.sets)
            }
            HorizontalDivider(color = c.line)
        }
        Gap(16)
        Note("Timed sets merge into a Heartline recording made at the same time: your sets, names and rest times replace the watch's guesses, and the watch adds heart rate and rep counts. Timed sessions also count as workout time for active minutes. Treadmill rows with speed and incline give a VO2max estimate, " +
            "matched to the watch's heart rate for that stretch. Volume is load times reps over working and warm-up sets; for assisted moves the load is your weight minus the assistance.")
        Gap(28)
    }
}

/** On a watch workout: the logged sets from the same session, and for a treadmill workout, its speed and incline. */
@Composable
fun WorkoutLogSection(controller: HeartlineController, w: WorkoutUi) {
    val c = HL.colors
    val log by controller.gymLog.collectAsStateWithLifecycle()
    val trends by controller.trends.collectAsStateWithLifecycle()
    val end = w.start + w.durationS
    val zone = ZoneId.systemDefault()
    val sets = remember(log, w.start) { setsForWorkout(log, w.start, end, w.type, zone) }
    val treadmill = w.type == "Treadmill"
    if (sets.isNotEmpty() && !treadmill) {
        Gap(24); SectionTitle("Your log"); Gap(4)
        val volume = sets.filter { it.completed && !it.cardio }.sumOf { (it.loadKg ?: 0.0) * (it.reps ?: 0.0) }
        MutedText("${sets.count { it.completed && !it.cardio && it.setType == "working" }} working sets" +
            (if (volume > 0) ", ${fmtInt(volume.roundToInt())} kg volume" else "") +
            (if (sets.any { it.start != null }) ". Shaded on the heart rate chart." else "."))
        ExerciseSets(sets)
    }
    if (!treadmill) return
    val mine = sets.firstOrNull { it.cardio }
    var speed by remember(mine) { mutableStateOf(mine?.speedKmh?.let(::num) ?: "") }
    var incline by remember(mine) { mutableStateOf(mine?.inclinePct?.let(::num) ?: "") }
    Gap(24); SectionTitle("Treadmill settings"); Gap(8)
    Panel(padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        MutedText(if (mine != null) "From your log${if (mine.session != "Typed in") " (${mine.session})" else ""}. Change them if they're off." else
            "The watch can't tell belt speed or incline. Enter them to get a VO2max estimate from this walk or run.")
        Gap(10)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(speed, { t -> speed = t.filter { it.isDigit() || it == '.' }.take(5) }, label = { Text("Speed, km/h") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
            OutlinedTextField(incline, { t -> incline = t.filter { it.isDigit() || it == '.' }.take(4) }, label = { Text("Incline, %") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
        }
        Gap(10)
        val s = speed.toDoubleOrNull(); val i = incline.toDoubleOrNull()
        val valid = s != null && s in 1.0..25.0 && i != null && i in 0.0..40.0
        val changed = s != mine?.speedKmh || i != mine?.inclinePct
        PillButton(if (mine == null) "Save settings" else "Save changes", { controller.saveTreadmill(w.start, end, s!!, i!!) },
            style = ButtonStyle.QUIET, enabled = valid && changed)
        val est = trends?.vo2Used?.firstOrNull { it.type == "Treadmill" && it.start in (w.start - 900)..(end + 900) }
        val skip = trends?.vo2Skipped?.firstOrNull { it.startsWith("treadmill (logged) on ${java.time.Instant.ofEpochSecond(w.start - 6 * 3600).atZone(zone).toLocalDate().format(DateTimeFormatter.ofPattern("d MMM"))}") }
        Gap(12)
        when {
            est != null -> {
                Text("VO2max from this session: ${num(est.vo2max)}", style = HL.type.bodyStrong, color = c.ink)
                MutedText("${num(est.speedKmh)} km/h at ${est.inclinePct?.let(::num) ?: "0"}% incline, heart rate settled at ${est.avgHr} bpm, " +
                    "${est.reservePct}% of your heart rate reserve. Used in the VO2max on Trends.")
            }
            skip != null -> MutedText("No VO2max from this one: ${skip.substringAfter(": ")}.")
            mine != null -> MutedText("Working out the VO2max for this; it shows here within a minute.")
        }
    }
    Gap(8)
    Note("Fair only if you didn't hold the handrails; holding on takes a large part of the load off and makes VO2max read high.")
}
