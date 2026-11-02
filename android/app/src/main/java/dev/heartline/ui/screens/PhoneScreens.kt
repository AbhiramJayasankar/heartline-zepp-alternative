package dev.heartline.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heartline.app.AlarmUi
import dev.heartline.app.HeartlineController
import dev.heartline.app.PlaceUi
import dev.heartline.ui.Navigator
import dev.heartline.ui.components.ButtonStyle
import dev.heartline.ui.components.Dot
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.Panel
import dev.heartline.ui.components.PillButton
import dev.heartline.ui.components.SectionTitle
import dev.heartline.ui.components.Stat
import dev.heartline.ui.theme.HL
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** A title, optional detail and a switch on the right; the whole row toggles. */
@Composable
fun ToggleRow(title: String, detail: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val c = HL.colors
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = HL.type.bodyStrong, color = if (enabled) c.ink else c.inkSoft)
            if (detail != null) MutedText(detail)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked, onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = c.ok, checkedThumbColor = c.surface, uncheckedTrackColor = c.gridMajor, uncheckedThumbColor = c.surface, uncheckedBorderColor = c.gridMajor),
        )
    }
}

/** System screens the app can only send the user to; each falls back to the app's own settings page. */
object SystemScreens {
    private fun Context.open(vararg intents: Intent): Boolean = intents.any { i ->
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
    }
    private fun details(ctx: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
    fun batteryExemption(ctx: Context) = ctx.open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")), Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), details(ctx))
    fun miuiAutostart(ctx: Context) = ctx.open(
        Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        details(ctx),
    )
    fun miuiBatterySaver(ctx: Context) = ctx.open(
        Intent("miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY").putExtra("package_name", ctx.packageName).putExtra("package_label", "Heartline"),
        details(ctx),
    )
    fun notificationAccess(ctx: Context) = ctx.open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), details(ctx))
    fun appDetails(ctx: Context) = ctx.open(details(ctx))
}

@Composable
private fun CheckItem(ok: Boolean?, title: String, detail: String, action: String?, onAction: () -> Unit) {
    val c = HL.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp)) { Dot(when (ok) { true -> c.ok; false -> c.pulse; null -> c.warn }, 10.dp) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = HL.type.bodyStrong, color = c.ink)
            MutedText(detail)
            if (action != null) {
                Gap(10)
                PillButton(action, onAction, style = if (ok == false) ButtonStyle.PRIMARY else ButtonStyle.QUIET)
            }
        }
    }
    HorizontalDivider(color = c.line)
}

@Composable
fun KeepRunningScreen(controller: HeartlineController, nav: Navigator) {
    val ctx = LocalContext.current
    val bg by controller.background.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { controller.refreshBackground() }
    Screen("Keep running", onBack = { nav.pop() }, subtitle = "Heartline has to stay connected while the screen is off to sync, forward notifications and ring the phone. Phones shut background apps down unless you allow them.") {
        CheckItem(
            bg.batteryUnrestricted, "Battery: unrestricted",
            if (bg.batteryUnrestricted) "Android won't pause Heartline to save battery." else "Android pauses Heartline when the screen is off. Allow it to run unrestricted.",
            if (bg.batteryUnrestricted) null else "Allow unrestricted",
        ) { SystemScreens.batteryExemption(ctx) }
        if (bg.miui) {
            CheckItem(
                null, "MIUI: Autostart",
                "MIUI stops apps that aren't allowed to autostart, even with unrestricted battery. Find Heartline in the list and switch it on. The app can't check this for you.",
                "Open Autostart",
            ) { SystemScreens.miuiAutostart(ctx) }
            CheckItem(
                null, "MIUI: Battery saver, No restrictions",
                "Open Battery saver, pick No restrictions. On some MIUI versions it is under App info, Battery saver.",
                "Open battery saver",
            ) { SystemScreens.miuiBatterySaver(ctx) }
        }
        CheckItem(
            bg.notificationAccess, "Notification access",
            if (bg.notificationAccess) "Heartline can forward notifications and control music." else "Needed to forward notifications to the watch and to control music. Find Heartline and allow it.",
            if (bg.notificationAccess) null else "Allow notification access",
        ) { SystemScreens.notificationAccess(ctx) }
        CheckItem(bg.postNotifications, "Heartline's own notification", if (bg.postNotifications) "The small connection notification keeps the service alive." else "Without it Android may stop the connection service.", if (bg.postNotifications) null else "Open app settings") { SystemScreens.appDetails(ctx) }
        CheckItem(bg.bluetoothOn, "Bluetooth", if (bg.bluetoothOn) "On." else "Turn Bluetooth on so Heartline can reach the watch.", null) {}
    }
}

/** A quiet banner for the Watch tab when something will stop Heartline in the background. */
@Composable
fun KeepRunningBanner(controller: HeartlineController, onFix: () -> Unit) {
    val bg by controller.background.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { controller.refreshBackground() }
    if (bg.batteryUnrestricted && bg.notificationAccess) return
    val c = HL.colors
    Panel(wash = true) {
        Text("Heartline may stop when the screen is off", style = HL.type.bodyStrong, color = c.ink)
        Gap(4)
        MutedText(if (!bg.batteryUnrestricted) "Battery optimisation is still on, so syncs and notifications will pause." else "Notification access is off, so notifications and music controls won't reach the watch.")
        Gap(10)
        PillButton("Fix this", onFix, style = ButtonStyle.PULSE)
    }
    Gap(16)
}

@Composable
fun WeatherScreen(controller: HeartlineController, nav: Navigator) {
    val c = HL.colors
    val w by controller.weather.collectAsStateWithLifecycle()
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PlaceUi>>(emptyList()) }
    LaunchedEffect(query) {
        if (query.trim().length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(350)
        results = runCatching { controller.searchPlaces(query.trim()) }.getOrDefault(emptyList())
    }
    Screen("Weather", onBack = { nav.pop() }, subtitle = "The watch's weather app and widgets, kept up to date every hour while connected.") {
        ToggleRow("Show weather on the watch", w.place?.let { "${it.name}${it.region?.let { r -> ", $r" } ?: ""}" } ?: "Pick a place first", w.enabled, enabled = w.place != null) { controller.setWeather(it, null) }
        Gap(8)
        if (w.place != null && !searching) {
            Panel {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(w.tempC?.let { "$it°" } ?: "–", style = HL.type.score, color = c.ink)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.padding(bottom = 8.dp)) {
                        Text(w.condition ?: "No forecast yet", style = HL.type.bodyStrong, color = c.ink)
                        if (w.highC != null && w.lowC != null) MutedText("High ${w.highC}°, low ${w.lowC}°")
                    }
                }
                Gap(8)
                MutedText(listOfNotNull(w.updatedAt?.let { "Forecast from ${agoOf(it)}" }, w.sentToWatchAt?.let { "sent to the watch ${agoOf(it)}" }).joinToString(", ").ifEmpty { "Not fetched yet." })
                w.error?.let { Gap(6); Text(it, style = HL.type.small, color = c.pulse) }
                Gap(12)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Refresh now", { controller.refreshWeather() }, style = ButtonStyle.QUIET, enabled = w.enabled)
                    PillButton("Change place", { searching = true }, style = ButtonStyle.QUIET)
                }
            }
        }
        if (w.place == null || searching) {
            OutlinedTextField(query, { query = it.take(60) }, label = { Text("Search a city or town") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Gap(6)
            results.forEach { p ->
                Column(Modifier.fillMaxWidth().clickable { controller.setWeather(true, p); searching = false; query = "" }.padding(vertical = 12.dp)) {
                    Text(p.name, style = HL.type.bodyStrong, color = c.ink)
                    p.region?.let { MutedText(it) }
                }
                HorizontalDivider(color = c.line)
            }
            if (query.trim().length >= 2 && results.isEmpty()) MutedText("No places match yet.")
            if (searching) { Gap(8); PillButton("Cancel", { searching = false; query = "" }, style = ButtonStyle.QUIET) }
        }
        Gap(20)
        MutedText("Forecasts come from Open-Meteo, a free weather service. Only the place you pick is sent to it: no account and no location tracking.")
    }
}

private val week = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
private fun daysLabel(days: Set<DayOfWeek>): String = when {
    days.isEmpty() -> "Once"
    days.size == 7 -> "Every day"
    days == week.take(5).toSet() -> "Weekdays"
    days == setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "Weekends"
    else -> week.filter { it in days }.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
}

const val ALARM_SLOTS = 10

@Composable
fun AlarmsScreen(controller: HeartlineController, nav: Navigator) {
    val c = HL.colors
    val alarms by controller.alarms.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<AlarmUi?>(null) }
    val free = (0 until ALARM_SLOTS).firstOrNull { s -> alarms.none { it.slot == s } }
    Screen("Alarms", onBack = { nav.pop() }, subtitle = "The watch wakes you with a vibration. Heartline writes alarms to the watch whenever it's connected.") {
        if (alarms.isEmpty()) MutedText("No alarms yet.")
        alarms.forEach { a ->
            Row(Modifier.fillMaxWidth().clickable { editing = a }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("%02d:%02d".format(a.hour, a.minute), style = HL.type.stat.copy(fontSize = 34.sp), color = if (a.enabled) c.ink else c.inkSoft)
                    MutedText(daysLabel(a.days) + if (a.smart) ", smart wake-up" else "")
                }
                Switch(
                    a.enabled, { controller.saveAlarm(a.copy(enabled = it)) },
                    colors = SwitchDefaults.colors(checkedTrackColor = c.ok, checkedThumbColor = c.surface, uncheckedTrackColor = c.gridMajor, uncheckedThumbColor = c.surface, uncheckedBorderColor = c.gridMajor),
                )
            }
            HorizontalDivider(color = c.line)
        }
        Gap(16)
        PillButton(if (free == null) "All $ALARM_SLOTS alarm slots are used" else "Add alarm", { free?.let { editing = AlarmUi(it, true, 7, 0, week.take(5).toSet()) } }, enabled = free != null)
    }
    editing?.let { a -> AlarmEditor(a, isNew = alarms.none { it.slot == a.slot }, onDismiss = { editing = null }, onSave = { controller.saveAlarm(it); editing = null }, onDelete = { controller.deleteAlarm(a.slot); editing = null }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmEditor(alarm: AlarmUi, isNew: Boolean, onDismiss: () -> Unit, onSave: (AlarmUi) -> Unit, onDelete: () -> Unit) {
    val c = HL.colors
    val time = rememberTimePickerState(alarm.hour, alarm.minute, is24Hour = true)
    var days by remember { mutableStateOf(alarm.days) }
    var smart by remember { mutableStateOf(alarm.smart) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.surface,
        title = { Text(if (isNew) "New alarm" else "Edit alarm", style = HL.type.section, color = c.ink) },
        text = {
            Column {
                TimePicker(
                    time,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = c.wash, selectorColor = c.pulse, timeSelectorSelectedContainerColor = c.wash,
                        timeSelectorSelectedContentColor = c.ink, timeSelectorUnselectedContainerColor = c.paper, timeSelectorUnselectedContentColor = c.inkSoft,
                        clockDialSelectedContentColor = c.surface, clockDialUnselectedContentColor = c.ink,
                    ),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    week.forEach { d ->
                        val on = d in days
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(if (on) c.ink else Color.Transparent).border(1.dp, if (on) c.ink else c.line, CircleShape)
                                .clickable { days = if (on) days - d else days + d },
                            contentAlignment = Alignment.Center,
                        ) { Text(d.getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = HL.type.label, color = if (on) c.paper else c.inkSoft) }
                    }
                }
                Gap(6)
                MutedText(daysLabel(days))
                ToggleRow("Smart wake-up", "Wakes you in light sleep up to 30 minutes early, if the watch supports it.", smart) { smart = it }
            }
        },
        confirmButton = { TextButton({ onSave(alarm.copy(hour = time.hour, minute = time.minute, days = days, smart = smart, enabled = true)) }) { Text("Save", color = c.pulse) } },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onDelete) { Text("Delete", color = c.inkSoft) }
                TextButton(onDismiss) { Text("Cancel", color = c.inkSoft) }
            }
        },
    )
}

@Composable
fun RemindersScreen(controller: HeartlineController, nav: Navigator) {
    val c = HL.colors
    val r by controller.reminders.collectAsStateWithLifecycle()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        controller.setReminderSync(granted, r.selected)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { controller.refreshBackground() }
    Screen("Calendar", onBack = { nav.pop() }, subtitle = "Your next events appear on the watch with a reminder vibration.") {
        if (!r.permission) {
            Panel {
                Text("Heartline needs to read your calendar", style = HL.type.bodyStrong, color = c.ink)
                MutedText("Only event titles and start times from the calendars you pick are read, and they only go to the watch.")
                Gap(12)
                PillButton("Allow calendar access", { ask.launch(Manifest.permission.READ_CALENDAR) })
            }
            return@Screen
        }
        ToggleRow("Send events to the watch", "Up to ${r.maxSlots} events from the next 14 days", r.enabled) { controller.setReminderSync(it, r.selected) }
        Gap(12)
        SectionTitle("Calendars")
        Gap(4)
        if (r.calendars.isEmpty()) MutedText("No calendars on this phone.")
        r.calendars.forEach { cal ->
            val on = cal.id in r.selected
            Row(Modifier.fillMaxWidth().clickable { controller.setReminderSync(r.enabled, if (on) r.selected - cal.id else r.selected + cal.id) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(on, { controller.setReminderSync(r.enabled, if (on) r.selected - cal.id else r.selected + cal.id) }, colors = CheckboxDefaults.colors(checkedColor = c.ink))
                Column {
                    Text(cal.name, style = HL.type.body, color = c.ink)
                    cal.account?.let { MutedText(it) }
                }
            }
        }
        Gap(20)
        SectionTitle("Coming up on the watch")
        Gap(4)
        if (!r.enabled || r.upcoming.isEmpty()) MutedText(if (r.enabled) "Nothing in the next 14 days." else "Turn on sending to see what goes to the watch.")
        else r.upcoming.forEach { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Text(e.title, style = HL.type.body, color = c.ink, modifier = Modifier.weight(1f))
                MutedText(dateTimeOf(e.start))
            }
            HorizontalDivider(color = c.line)
        }
        r.lastSync?.let { Gap(10); MutedText("Last sent to the watch ${agoOf(it)}.") }
    }
}

@Composable
fun PhoneLinkScreen(controller: HeartlineController, nav: Navigator) {
    val c = HL.colors
    val ctx = LocalContext.current
    val p by controller.phoneLink.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { controller.refreshBackground() }
    Screen("Music and find phone", onBack = { nav.pop() }, subtitle = "Control what's playing from your wrist, and make a lost phone ring from the watch.") {
        SectionTitle("Music")
        ToggleRow("Control music from the watch", p.nowPlaying?.let { "Playing: $it" } ?: "Play, pause, skip and volume for whatever is playing on this phone.", p.musicEnabled) { controller.setMusicControl(it) }
        if (p.musicEnabled && !p.musicAccess) {
            Gap(4)
            MutedText("Music control needs notification access, the same permission notification forwarding uses.")
            Gap(8)
            PillButton("Allow notification access", { SystemScreens.notificationAccess(ctx) })
        }
        Gap(24)
        SectionTitle("Find phone")
        Gap(4)
        MutedText("Start Find phone on the watch and this phone rings at alarm volume, even when it's on silent, for up to a minute.")
        Gap(12)
        if (p.findPhoneRinging) PillButton("Stop ringing", { controller.stopFindPhone() }, Modifier.fillMaxWidth(), style = ButtonStyle.PULSE)
    }
}

/** Shown over every screen while the watch is making the phone ring. */
@Composable
fun FindPhoneBanner(controller: HeartlineController) {
    val p by controller.phoneLink.collectAsStateWithLifecycle()
    if (!p.findPhoneRinging) return
    val c = HL.colors
    Row(
        Modifier.fillMaxWidth().background(c.pulse).padding(horizontal = Gutter, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Your watch is looking for this phone", style = HL.type.bodyStrong, color = c.surface, modifier = Modifier.weight(1f))
        Box(Modifier.clip(RoundedCornerShape(50)).background(c.surface).clickable { controller.stopFindPhone() }.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Stop", style = HL.type.button, color = c.pulse)
        }
    }
}

@Composable
fun HealthConnectScreen(controller: HeartlineController, nav: Navigator) {
    val ctx = LocalContext.current
    val hc by controller.healthConnect.collectAsStateWithLifecycle()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { controller.refreshBackground(); controller.setHealthConnect(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { controller.refreshBackground() }
    Screen("Health Connect", onBack = { nav.pop() }, subtitle = "Share your heart rate, sleep, steps, SpO2, workouts and HRV with apps that read from Health Connect. Heartline only writes; it never reads other apps' data.") {
        when (hc.status) {
            dev.heartline.app.HealthConnectStatus.UNAVAILABLE -> MutedText("Health Connect isn't available on this phone.")
            dev.heartline.app.HealthConnectStatus.NEEDS_INSTALL -> {
                MutedText("Health Connect isn't installed. It's a free app from Google.")
                Gap(10)
                PillButton("Get Health Connect", {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                })
            }
            dev.heartline.app.HealthConnectStatus.NEEDS_PERMISSION -> {
                MutedText("Allow Heartline to write each kind of data. You can change this any time in Health Connect.")
                Gap(10)
                PillButton("Allow in Health Connect", {
                    ask.launch(
                        Intent("androidx.health.ACTION_REQUEST_PERMISSIONS").setPackage("com.google.android.apps.healthdata")
                            .putExtra("androidx.health.EXTRA_PERMISSIONS", controller.healthConnectPermissions.toTypedArray()),
                    )
                })
            }
            dev.heartline.app.HealthConnectStatus.READY -> {
                ToggleRow("Write to Health Connect", "After every sync", hc.enabled) { controller.setHealthConnect(it) }
                Gap(10)
                PillButton("Export now", { controller.exportHealthConnectNow() }, style = ButtonStyle.QUIET, enabled = hc.enabled)
            }
        }
        hc.lastExport?.let { Gap(16); MutedText("Last export ${agoOf(it)}.") }
        hc.lastResult?.let { Gap(4); MutedText(it) }
    }
}
