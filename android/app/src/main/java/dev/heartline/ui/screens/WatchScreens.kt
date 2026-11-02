package dev.heartline.ui.screens

import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heartline.app.ForwardingUi
import dev.heartline.app.HeartlineController
import dev.heartline.app.LinkState
import dev.heartline.app.NotifyKind
import dev.heartline.app.Sensor
import dev.heartline.app.WatchSettingUi
import dev.heartline.ui.Navigator
import dev.heartline.ui.Route
import dev.heartline.ui.components.BatteryGlyph
import dev.heartline.ui.components.ButtonStyle
import dev.heartline.ui.components.CompassDial
import dev.heartline.ui.components.ConnectionChip
import dev.heartline.ui.components.LevelBar
import dev.heartline.ui.components.LevelHistory
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.Panel
import dev.heartline.ui.components.PillButton
import dev.heartline.ui.components.SectionTitle
import dev.heartline.ui.components.Segmented
import dev.heartline.ui.components.SignalCanvas
import dev.heartline.ui.components.SkyPlot
import dev.heartline.ui.components.Stat
import dev.heartline.ui.components.detrendPulse
import dev.heartline.ui.components.paper
import dev.heartline.ui.theme.HL
import dev.heartline.ui.theme.ThemeMode
import kotlin.math.roundToInt

@Composable
fun WatchScreen(controller: HeartlineController, nav: Navigator) {
    val c = HL.colors
    val conn by controller.connection.collectAsStateWithLifecycle()
    val battery by controller.battery.collectAsStateWithLifecycle()
    val device by controller.device.collectAsStateWithLifecycle()
    val prefs by controller.prefs.collectAsStateWithLifecycle()
    val connected = conn.state == LinkState.CONNECTED
    val busy = conn.state in setOf(LinkState.SCANNING, LinkState.CONNECTING, LinkState.AUTHENTICATING)

    Screen("Watch") {
        KeepRunningBanner(controller) { nav.push(Route.KeepRunning) }
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(device?.model ?: "Amazfit GTR 2e", style = HL.type.section, color = c.ink)
                    Gap(4)
                    ConnectionChip(conn)
                }
                battery?.let { BatteryGlyph(it.level, it.charging) }
            }
            conn.error?.let { Gap(8); Text(it, style = HL.type.small, color = c.pulse) }
            Gap(14)
            PillButton(
                when { connected -> "Disconnect"; busy -> "Connecting…"; else -> "Connect" },
                { if (connected) controller.disconnect() else controller.connect() },
                Modifier.fillMaxWidth(), style = if (connected) ButtonStyle.QUIET else ButtonStyle.PRIMARY, enabled = !busy,
            )
            Gap(14)
            Row(Modifier.fillMaxWidth()) {
                Stat("Firmware", device?.firmware ?: "–", Modifier.weight(1f))
                Stat("Last charged", battery?.lastCharged ?: "–", Modifier.weight(1f))
            }
            Gap(10)
            MutedText("${prefs.watchMac ?: "No watch paired"}${device?.serial?.let { ", serial $it" } ?: ""}")
        }
        Gap(14)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Find watch", { controller.vibrate() }, Modifier.weight(1f), style = ButtonStyle.QUIET, enabled = connected)
            PillButton("Sync now", { controller.syncNow() }, Modifier.weight(1f), style = ButtonStyle.QUIET, enabled = connected)
            PillButton("Set clock", { controller.setWatchClock() }, Modifier.weight(1f), style = ButtonStyle.QUIET, enabled = connected)
        }
        Gap(22)
        NotifyComposer(controller, connected)
        Gap(18)
        NavRow("Sensors lab", "Raw pulse, motion, compass, GPS and microphone, live") { nav.push(Route.Sensors) }
        NavRow("Watch settings", "All-day heart rate, stress, alerts, display") { nav.push(Route.WatchSettings) }
        NavRow("Alarms", "Vibration alarms on the watch") { nav.push(Route.Alarms) }
        NavRow("Weather", "Forecast for the watch's weather app") { nav.push(Route.Weather) }
        NavRow("Calendar", "Upcoming events as watch reminders") { nav.push(Route.Reminders) }
        NavRow("Music and find phone", "Control music, make a lost phone ring") { nav.push(Route.PhoneLink) }
        NavRow("Phone notifications", if (prefs.forwarding.enabled) "Forwarding to your wrist" else "Off") { nav.push(Route.Forwarding) }
        NavRow("Keep running", "Battery and permissions for the background connection") { nav.push(Route.KeepRunning) }
        NavRow("Your profile", "Used for zones, VO2max and calories") { nav.push(Route.Profile) }
        NavRow("App settings", "Theme and units") { nav.push(Route.Preferences) }
        NavRow("Health Connect", "Share heart rate, sleep and steps with other apps") { nav.push(Route.HealthConnect) }
        NavRow("Data", "Import from the PC dashboard, export, backup") { nav.push(Route.Data) }
        NavRow("Activity log", "Connection steps, syncs and errors") { nav.push(Route.Events) }
    }
}

@Composable
private fun NotifyComposer(controller: HeartlineController, connected: Boolean) {
    var title by remember { mutableStateOf("Heartline") }
    var body by remember { mutableStateOf("Time to stand up and stretch.") }
    var kind by remember { mutableStateOf(NotifyKind.MESSAGE) }
    var sent by remember { mutableStateOf<String?>(null) }
    SectionTitle("Send to your wrist")
    Gap(8)
    OutlinedTextField(title, { title = it.take(32) }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Gap(6)
    OutlinedTextField(body, { body = it.take(240) }, label = { Text("Message") }, minLines = 2, modifier = Modifier.fillMaxWidth())
    Gap(10)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Segmented(listOf(NotifyKind.MESSAGE to "Message", NotifyKind.CALL to "Call", NotifyKind.GENERIC to "Alert"), kind, { kind = it })
        Spacer(Modifier.weight(1f))
        PillButton("Send", { controller.sendNotification(title, body, kind); sent = "Sent. Check your wrist." }, enabled = connected && body.isNotBlank())
    }
    sent?.let { Gap(6); MutedText(it) }
}

// ---------------- Sensors lab ----------------

@Composable
fun SensorsScreen(controller: HeartlineController, nav: Navigator) {
    val c = HL.colors
    val s by controller.sensors.collectAsStateWithLifecycle()
    val conn by controller.connection.collectAsStateWithLifecycle()
    val recordings by controller.recordings.collectAsStateWithLifecycle()
    val connected = conn.state == LinkState.CONNECTED
    val names = listOf(Sensor.PPG to "Pulse", Sensor.ACCEL to "Accelerometer", Sensor.GYRO to "Gyroscope", Sensor.MAG to "Compass", Sensor.GPS to "GPS", Sensor.MIC to "Microphone")

    Screen("Sensors lab", onBack = { nav.pop() }, subtitle = "Raw streams straight from the watch. Every sample is saved while streaming.") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            names.forEach { (sensor, label) ->
                val on = sensor in s.selected
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(if (on) c.ink else c.surface).border(1.dp, if (on) c.ink else c.line, RoundedCornerShape(50))
                        .clickable { controller.setRawSensors(if (on) s.selected - sensor else s.selected + sensor, s.streaming) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) { Text(label, style = HL.type.label, color = if (on) c.paper else c.inkSoft) }
            }
        }
        Gap(6)
        MutedText("GPS uses more battery. The microphone records sound around you; turn off the watch's voice wake-up for unbroken audio.")
        Gap(12)
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton(if (s.streaming) "Stop streaming" else "Start streaming", { controller.setRawSensors(s.selected, !s.streaming) }, style = ButtonStyle.PULSE, pressed = s.streaming, enabled = connected && s.selected.isNotEmpty())
            Spacer(Modifier.width(12.dp))
            if (s.streaming && Sensor.MIC in s.selected) Text("Microphone on", style = HL.type.label, color = c.pulse)
        }
        s.file?.takeIf { s.streaming }?.let { Gap(6); MutedText("Recording to $it") }
        Gap(18)

        if (Sensor.PPG in s.selected) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).paper().padding(12.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Optical pulse", style = HL.type.label, color = c.inkSoft, modifier = Modifier.weight(1f))
                    Text(s.ppgBpm?.let { "$it bpm" } ?: "–", style = HL.type.stat.copy(fontSize = 20.sp), color = c.pulse)
                }
                val sig = remember(s.ppg) { detrendPulse(s.ppg) }
                SignalCanvas(listOf(sig), listOf(c.ink), Modifier.fillMaxWidth().height(150.dp))
            }
            Gap(12)
        }
        if (Sensor.ACCEL in s.selected) {
            AxisPanel("Accelerometer, g", s.accel)
            Gap(12)
        }
        if (Sensor.GYRO in s.selected) {
            AxisPanel("Gyroscope, rotation", s.gyro, symmetric = true)
            Gap(12)
        }
        if (Sensor.MAG in s.selected) {
            Panel {
                Text("Compass", style = HL.type.label, color = c.inkSoft)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CompassDial(s.headingDeg, Modifier.width(150.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(s.headingDeg?.let { "${it.roundToInt() % 360}°" } ?: "–", style = HL.type.score, color = c.ink)
                        MutedText(if (s.compassCalibration < 1) "Turn the watch slowly in a figure-eight to calibrate (${(s.compassCalibration * 100).roundToInt()}%)." else "Calibrated for this session.")
                    }
                }
            }
            Gap(12)
        }
        if (Sensor.GPS in s.selected) {
            val g = s.gps
            Panel {
                Text("GPS satellites", style = HL.type.label, color = c.inkSoft)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SkyPlot(g?.sats.orEmpty(), Modifier.width(150.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Stat("Fix", if (g?.fix == true) "Yes" else "Searching")
                        Stat("Satellites", g?.let { "${it.satsUsed} used, ${it.sats.count { s -> s.snr != null }} heard" } ?: "–")
                        if (g?.lat != null) Stat("Position", "%.5f, %.5f".format(g.lat, g.lon))
                    }
                }
                MutedText("Centre is straight up, the edge is the horizon. Filled dots have a usable signal; indoors, expect no fix.")
            }
            Gap(12)
        }
        if (Sensor.MIC in s.selected) {
            Panel {
                Text("Sound", style = HL.type.label, color = c.inkSoft)
                val now = s.micDb.takeLast(10).maxOrNull()
                Text(now?.let { "${it.roundToInt()} dB" } ?: "–", style = HL.type.score, color = c.ink)
                Gap(6)
                LevelBar(((now ?: -70f) + 70f) / 70f, (now ?: -70f) > -12f)
                Gap(8)
                LevelHistory(s.micDb)
                MutedText("Relative to full scale, not calibrated decibels.")
            }
            Gap(12)
        }
        Gap(10)
        SectionTitle("Recordings")
        Gap(4)
        if (recordings.isEmpty()) MutedText("Nothing recorded yet.")
        recordings.forEach { r -> RecordingRow(controller, r.file, "${dateTimeOf(r.start)}, ${r.durationS.toInt()} s", r.hasAudio) }
    }
}

@Composable
private fun AxisPanel(title: String, data: Array<FloatArray>, symmetric: Boolean = false) {
    val c = HL.colors
    Panel {
        Text(title, style = HL.type.label, color = c.inkSoft)
        val axes = remember(data) { (0..2).map { k -> FloatArray(data.size) { data[it][k] } } }
        SignalCanvas(axes, listOf(c.ink, c.pulse, c.sleepLight), Modifier.fillMaxWidth().height(110.dp), symmetric = symmetric, minSpan = if (symmetric) 400f else 0.1f)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            listOf("x" to c.ink, "y" to c.pulse, "z" to c.sleepLight).forEach { (l, col) -> Text(l, style = HL.type.label, color = col) }
        }
    }
}

@Composable
private fun RecordingRow(controller: HeartlineController, file: String, detail: String, hasAudio: Boolean) {
    val ctx = LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(file, style = HL.type.bodyStrong, color = HL.colors.ink)
            MutedText(detail)
        }
        if (hasAudio) {
            PillButton(if (player != null) "Stop" else "Play", {
                if (player != null) { player?.release(); player = null }
                else controller.recordingAudio(file)?.let { uri ->
                    player = MediaPlayer.create(ctx, uri)?.apply { setOnCompletionListener { it.release(); player = null }; start() }
                }
            }, style = ButtonStyle.QUIET)
        }
    }
    HorizontalDivider(color = HL.colors.line)
}

// ---------------- Watch settings ----------------

@Composable
fun WatchSettingsScreen(controller: HeartlineController, nav: Navigator) {
    val settings by controller.watchSettings.collectAsStateWithLifecycle()
    val conn by controller.connection.collectAsStateWithLifecycle()
    val connected = conn.state == LinkState.CONNECTED
    Screen("Watch settings", onBack = { nav.pop() }, subtitle = "Changes go straight to the watch. It doesn't report settings back, so these show what Heartline last set.") {
        settings.forEach { s -> SettingRow(s, connected) { controller.applyWatchSetting(s.key, it) } }
    }
}

@Composable
private fun SettingRow(s: WatchSettingUi, enabled: Boolean, onChange: (Any) -> Unit) {
    val c = HL.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(s.label, style = HL.type.body, color = c.ink, modifier = Modifier.weight(1f))
        when (s.type) {
            "bool" -> Switch(
                checked = s.value == true, onCheckedChange = { onChange(it) }, enabled = enabled,
                colors = SwitchDefaults.colors(checkedTrackColor = c.ok, checkedThumbColor = c.surface, uncheckedTrackColor = c.gridMajor, uncheckedThumbColor = c.surface, uncheckedBorderColor = c.gridMajor),
            )
            "enum" -> {
                var open by remember { mutableStateOf(false) }
                Box {
                    PillButton(s.options.firstOrNull { it.first == s.value }?.second ?: "Not set", { open = true }, style = ButtonStyle.QUIET, enabled = enabled)
                    DropdownMenu(open, { open = false }) {
                        s.options.forEach { (v, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onChange(v) }) }
                    }
                }
            }
            else -> {
                var text by remember(s.value) { mutableStateOf(s.value?.toString() ?: "") }
                OutlinedTextField(
                    text, { text = it.filter(Char::isDigit).take(3); text.toIntOrNull()?.let { v -> if ((s.min == null || v >= s.min) && (s.max == null || v <= s.max)) onChange(v) } },
                    singleLine = true, enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(96.dp),
                )
            }
        }
    }
    HorizontalDivider(color = c.line)
}

// ---------------- Notification forwarding ----------------

@Composable
fun ForwardingScreen(controller: HeartlineController, nav: Navigator) {
    val c = HL.colors
    val ctx = LocalContext.current
    val prefs by controller.prefs.collectAsStateWithLifecycle()
    val f = prefs.forwarding
    val apps = remember {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }.filter { it.first != ctx.packageName }.sortedBy { it.second.lowercase() }
    }
    fun set(u: ForwardingUi) = controller.setForwarding(u)
    Screen("Phone notifications", onBack = { nav.pop() }, subtitle = "Heartline shows your phone's notifications on the watch, the way Zepp did.") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Forward notifications", style = HL.type.bodyStrong, color = c.ink, modifier = Modifier.weight(1f))
            Switch(f.enabled, { set(f.copy(enabled = it)) }, colors = SwitchDefaults.colors(checkedTrackColor = c.ok))
        }
        Gap(8)
        PillButton("Allow notification access", { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, style = ButtonStyle.QUIET)
        Gap(6)
        MutedText("Android asks you to switch Heartline on in its notification access list once.")
        Gap(16)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Incoming calls", style = HL.type.body, color = c.ink, modifier = Modifier.weight(1f))
            Switch(f.calls, { set(f.copy(calls = it)) }, colors = SwitchDefaults.colors(checkedTrackColor = c.ok))
        }
        Gap(8)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Quiet hours", style = HL.type.body, color = c.ink, modifier = Modifier.weight(1f))
            Segmented(listOf(false to "Off", true to "23:00 to 07:00"), f.quietStart != null, { on -> set(f.copy(quietStart = if (on) 23 else null, quietEnd = if (on) 7 else null)) })
        }
        Gap(20)
        SectionTitle("Apps")
        MutedText("Only ticked apps reach your wrist.")
        Gap(6)
        apps.forEach { (pkg, label) ->
            val on = pkg in f.allowed
            Row(Modifier.fillMaxWidth().clickable { set(f.copy(allowed = if (on) f.allowed - pkg else f.allowed + pkg)) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(on, { set(f.copy(allowed = if (on) f.allowed - pkg else f.allowed + pkg)) }, colors = CheckboxDefaults.colors(checkedColor = c.ink))
                Text(label, style = HL.type.body, color = c.ink)
            }
        }
    }
}

// ---------------- Data, profile, preferences, log ----------------

@Composable
fun DataScreen(controller: HeartlineController, nav: Navigator) {
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(controller::importPcData) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(controller::exportData) }
    Screen("Data", onBack = { nav.pop() }, subtitle = "Everything stays on this phone unless you export it.") {
        SectionTitle("Import from the PC dashboard")
        Gap(4)
        MutedText("Pick the dashboard's watch.db to bring over its history, including the 40 days of skin temperature that exist nowhere else. Importing twice adds nothing.")
        Gap(10)
        PillButton("Choose watch.db", { importer.launch(arrayOf("*/*")) })
        Gap(26)
        SectionTitle("Export")
        Gap(4)
        MutedText("A zip with one CSV per table: heart rate, activity minutes, health data, workouts and heart checks.")
        Gap(10)
        PillButton("Export all data", { exporter.launch("heartline-export.zip") }, style = ButtonStyle.QUIET)
    }
}

@Composable
fun ProfileScreen(controller: HeartlineController, nav: Navigator) {
    val prefs by controller.prefs.collectAsStateWithLifecycle()
    var p by remember(prefs.profile) { mutableStateOf(prefs.profile) }
    var saved by remember { mutableStateOf(false) }
    Screen("Your profile", onBack = { nav.pop() }, subtitle = "Used for heart rate zones, VO2max, fitness age and calories. Stays on this phone.") {
        NumberField("Age", p.age?.toString()) { p = p.copy(age = it?.toInt()) }
        Gap(10)
        Text("Sex", style = HL.type.label, color = HL.colors.inkSoft)
        Gap(4)
        Segmented(listOf<Pair<String?, String>>(null to "Not set", "male" to "Male", "female" to "Female"), p.sex, { p = p.copy(sex = it) })
        Gap(10)
        NumberField("Weight, kg", p.weightKg?.toString(), decimal = true) { p = p.copy(weightKg = it) }
        Gap(10)
        NumberField("Height, cm", p.heightCm?.toString(), decimal = true) { p = p.copy(heightCm = it) }
        Gap(10)
        NumberField("Max heart rate (blank: from age)", p.maxHr?.toString()) { p = p.copy(maxHr = it?.toInt()) }
        Gap(10)
        NumberField("Resting heart rate (blank: from the watch)", p.restingHr?.toString()) { p = p.copy(restingHr = it?.toInt()) }
        Gap(18)
        PillButton("Save profile", { controller.saveProfile(p); saved = true })
        if (saved) { Gap(6); MutedText("Saved. Zones, VO2max and calories now use it.") }
    }
}

@Composable
private fun NumberField(label: String, value: String?, decimal: Boolean = false, onChange: (Double?) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.removeSuffix(".0") ?: "") }
    OutlinedTextField(
        text, { t -> text = t.filter { it.isDigit() || (decimal && it == '.') }.take(6); onChange(text.toDoubleOrNull()) },
        label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
    )
}

@Composable
fun PreferencesScreen(controller: HeartlineController, nav: Navigator) {
    val prefs by controller.prefs.collectAsStateWithLifecycle()
    Screen("App settings", onBack = { nav.pop() }) {
        Text("Theme", style = HL.type.label, color = HL.colors.inkSoft)
        Gap(6)
        Segmented(listOf(ThemeMode.SYSTEM to "Follow phone", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark"), prefs.theme, controller::setTheme)
        Gap(20)
        Text("Units", style = HL.type.label, color = HL.colors.inkSoft)
        Gap(6)
        Segmented(listOf(true to "Metric", false to "Imperial"), prefs.metric, controller::setMetric)
        Gap(28)
        MutedText("Heartline 0.1.0. Talks to the watch directly over Bluetooth; no account, no cloud.")
    }
}

@Composable
fun EventsScreen(controller: HeartlineController, nav: Navigator) {
    val events by controller.events.collectAsStateWithLifecycle()
    Screen("Activity log", onBack = { nav.pop() }) {
        if (events.isEmpty()) MutedText("Nothing yet.")
        events.forEach { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(timeOf(e.ts), style = HL.type.small, color = HL.colors.inkSoft, modifier = Modifier.width(56.dp))
                Text(e.message, style = HL.type.small, color = when { e.error -> HL.colors.pulse; e.warn -> HL.colors.warn; else -> HL.colors.ink })
            }
            HorizontalDivider(color = HL.colors.line)
        }
    }
}
