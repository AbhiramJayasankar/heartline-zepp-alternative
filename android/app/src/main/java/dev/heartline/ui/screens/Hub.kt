package dev.heartline.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heartline.app.HeartlineController
import dev.heartline.app.LinkState
import dev.heartline.ui.Icons
import dev.heartline.ui.Navigator
import dev.heartline.ui.Route
import dev.heartline.ui.components.ButtonStyle
import dev.heartline.ui.components.Card
import dev.heartline.ui.components.ConnectionChip
import dev.heartline.ui.components.Heading
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.PillButton
import dev.heartline.ui.theme.HL
import dev.heartline.ui.theme.Palette
import dev.heartline.ui.theme.THEMES
import dev.heartline.ui.theme.ThemeMode
import dev.heartline.ui.theme.paletteFor
import kotlinx.coroutines.delay
import java.time.LocalTime
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Watch tab as a hub: the watch itself (a live face with its battery around it, connection and quick actions),
 * then everything it can do grouped into tiles that say how each part is set right now.
 */
@Composable
fun WatchHubScreen(controller: HeartlineController, nav: Navigator) {
    val c = HL.colors
    val conn by controller.connection.collectAsStateWithLifecycle()
    val battery by controller.battery.collectAsStateWithLifecycle()
    val device by controller.device.collectAsStateWithLifecycle()
    val prefs by controller.prefs.collectAsStateWithLifecycle()
    val alarms by controller.alarms.collectAsStateWithLifecycle()
    val weather by controller.weather.collectAsStateWithLifecycle()
    val reminders by controller.reminders.collectAsStateWithLifecycle()
    val phone by controller.phoneLink.collectAsStateWithLifecycle()
    val hc by controller.healthConnect.collectAsStateWithLifecycle()
    val bg by controller.background.collectAsStateWithLifecycle()
    val ai by controller.assistantAi.collectAsStateWithLifecycle()
    val turns by controller.alexaTurns.collectAsStateWithLifecycle()
    val sync by controller.sync.collectAsStateWithLifecycle()
    val connected = conn.state == LinkState.CONNECTED
    val busy = conn.state in setOf(LinkState.SCANNING, LinkState.CONNECTING, LinkState.AUTHENTICATING)

    ScrollPage {
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Gutter).padding(top = 14.dp)) {
            Text("Watch", style = HL.type.title, color = c.ink)
            Gap(10)
            KeepRunningBanner(controller) { nav.push(Route.KeepRunning) }
            // ---- The watch ----
            Card(padding = 18.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WatchFace(battery?.level, connected, Modifier.size(118.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(device?.model ?: "Amazfit GTR 2e", style = HL.type.section, color = c.ink, maxLines = 2)
                        Gap(4)
                        ConnectionChip(conn)
                        Gap(8)
                        Text(battery?.let { "${it.level}% battery" + if (it.charging) ", charging" else "" } ?: "Battery unknown", style = HL.type.bodyStrong, color = c.ink)
                        battery?.lastCharged?.let { Text("Charged $it", style = HL.type.small, color = c.inkSoft) }
                        device?.firmware?.let { Text("Firmware $it", style = HL.type.small, color = c.inkSoft) }
                    }
                }
                conn.error?.let { Gap(10); Text(it, style = HL.type.small, color = c.pulse) }
                Gap(14)
                PillButton(when { connected -> "Disconnect"; busy -> "Connecting…"; else -> "Connect" },
                    { if (connected) controller.disconnect() else controller.connect() }, Modifier.fillMaxWidth(),
                    style = if (connected) ButtonStyle.QUIET else ButtonStyle.PRIMARY, enabled = !busy)
            }
            Gap(10)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickAction(Icons.find, "Find watch", connected, Modifier.weight(1f)) { controller.vibrate() }
                QuickAction(Icons.sync, if (sync.running) "Syncing" else "Sync now", connected && !sync.running, Modifier.weight(1f)) { controller.syncNow() }
                QuickAction(Icons.clock, "Set clock", connected, Modifier.weight(1f)) { controller.setWatchClock() }
                QuickAction(Icons.send, "Send", connected, Modifier.weight(1f)) { nav.push(Route.SendNotification) }
            }
            sync.lastActivity?.let { Gap(8); MutedText("Synced ${agoOf(it)}" + (prefs.watchMac?.let { m -> " · $m" } ?: "")) }

            // ---- Assistant ----
            Heading("Assistant")
            HubTile(Icons.mic, "Alexa button", if (ai.keyEnd == null) "Set up voice and the AI brain" else "Answers with ${ai.model.substringAfter('/')} · ${turns.size} questions in the log",
                c.pulse, Modifier.fillMaxWidth(), wide = true) { nav.push(Route.Alexa) }
            Gap(10)
            HubTile(Icons.sparkle, "AI insights", "What two weeks of your data say", c.sleepDeep, Modifier.fillMaxWidth(), wide = true) { nav.push(Route.Insights) }

            // ---- On the watch ----
            Heading("On your wrist")
            TileRow(
                { HubTile(Icons.gear, "Watch settings", "Heart rate, stress, display, alerts", c.ink, it) { nav.push(Route.WatchSettings) } },
                { HubTile(Icons.grid, "Watch apps", "Which apps show, in what order", c.ink, it) { nav.push(Route.WatchMenu) } },
            )
            TileRow(
                { HubTile(Icons.alarm, "Alarms", alarms.count { a -> a.enabled }.let { n -> if (n == 0) "None on" else "$n on" }, c.warn, it) { nav.push(Route.Alarms) } },
                { HubTile(Icons.cloud, "Weather", if (!weather.enabled) "Off" else listOfNotNull(weather.place?.name ?: "Where the phone is", weather.tempC?.let { t -> "$t°" }).joinToString(" · "), c.oxygen, it) { nav.push(Route.Weather) } },
            )
            TileRow(
                { HubTile(Icons.calendar, "Calendar", if (!reminders.enabled) "Off" else "${reminders.upcoming.size} upcoming on the watch", c.sleepDeep, it) { nav.push(Route.Reminders) } },
                { HubTile(Icons.vibrate, "Vibration", "How it buzzes for each alert", c.ink, it) { nav.push(Route.Vibration) } },
            )

            // ---- Phone ----
            Heading("Phone link")
            TileRow(
                { HubTile(Icons.bell, "Notifications", if (prefs.forwarding.enabled) "Forwarding to your wrist" else "Off", c.pulse, it) { nav.push(Route.Forwarding) } },
                { HubTile(Icons.music, "Music and find phone", if (phone.musicEnabled) phone.nowPlaying ?: "Music control on" else "Music control off", c.ok, it) { nav.push(Route.PhoneLink) } },
            )

            // ---- Data ----
            Heading("Your data")
            TileRow(
                { HubTile(Icons.share, "Health Connect", when { hc.enabled -> "Sharing" + (hc.lastExport?.let { t -> ", ${agoOf(t)}" } ?: ""); else -> "Off" }, c.ok, it) { nav.push(Route.HealthConnect) } },
                { HubTile(Icons.data, "Export and backup", "Import, export, back up", c.ink, it) { nav.push(Route.Data) } },
            )
            TileRow(
                { HubTile(Icons.person, "Your profile", prefs.profile.age?.let { a -> "$a years" + (prefs.profile.weightKg?.let { w -> " · ${w.toInt()} kg" } ?: "") } ?: "Age, weight, max heart rate", c.ink, it) { nav.push(Route.Profile) } },
                { HubTile(Icons.wave, "Sensors lab", "Raw pulse, motion, GPS, mic", c.pulse, it) { nav.push(Route.Sensors) } },
            )

            // ---- App ----
            Heading("App")
            TileRow(
                { HubTile(Icons.palette, "Look and units", THEMES.first { t -> t.mode == prefs.theme }.name + if (prefs.metric) " · metric" else " · imperial", c.pulse, it) { nav.push(Route.Preferences) } },
                { HubTile(Icons.battery, "Keep running", if (bg.batteryUnrestricted && bg.notificationAccess) "All set" else "Needs attention", if (bg.batteryUnrestricted && bg.notificationAccess) c.ok else c.warn, it) { nav.push(Route.KeepRunning) } },
            )
            TileRow(
                { HubTile(Icons.list, "Activity log", "Connections, syncs, errors", c.ink, it) { nav.push(Route.Events) } },
                null,
            )
            Gap(28)
        }
    }
}

/** Two tiles side by side, the same height. */
@Composable
private fun TileRow(left: @Composable (Modifier) -> Unit, right: (@Composable (Modifier) -> Unit)?) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        left(Modifier.weight(1f).fillMaxHeight())
        if (right != null) right(Modifier.weight(1f).fillMaxHeight()) else Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun HubTile(icon: ImageVector, title: String, status: String, tint: Color, modifier: Modifier, wide: Boolean = false, onClick: () -> Unit) {
    val c = HL.colors
    Card(modifier, onClick = onClick, onClickLabel = "Open $title", padding = 14.dp) {
        if (wide) Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, tint)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = HL.type.bodyStrong, color = c.ink)
                Text(status, style = HL.type.small, color = c.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.chevron, null, tint = c.inkSoft, modifier = Modifier.size(18.dp))
        } else {
            IconBadge(icon, tint)
            Gap(10)
            Text(title, style = HL.type.bodyStrong, color = c.ink)
            Text(status, style = HL.type.small, color = c.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun IconBadge(icon: ImageVector, tint: Color) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (tint == HL.colors.ink) HL.colors.ink else tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = HL.colors
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick).heightIn(min = 72.dp).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = if (enabled) c.ink else c.line, modifier = Modifier.size(22.dp))
        Gap(6)
        Text(label, style = HL.type.label.copy(fontSize = 12.sp), color = if (enabled) c.ink else c.inkSoft, maxLines = 1)
    }
}

/** The GTR 2e's round face showing the phone's time, with the battery as a ring around the bezel. */
@Composable
private fun WatchFace(level: Int?, connected: Boolean, modifier: Modifier) {
    val c = HL.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val battColor = when { level == null -> c.line; level <= 20 -> c.pulse; level <= 40 -> c.warn; else -> c.ok }
    Canvas(modifier.semantics { contentDescription = "Watch, battery ${level ?: "unknown"} percent, ${if (connected) "connected" else "not connected"}" }) {
        val t = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
        val r = size.minDimension / 2; val center = Offset(size.width / 2, size.height / 2)
        val ring = 7.dp.toPx()
        drawArc(c.line, -90f, 360f, false, Offset(ring / 2, ring / 2), Size(size.width - ring, size.height - ring), style = Stroke(ring, cap = StrokeCap.Round))
        if (level != null) drawArc(battColor, -90f, 360f * level / 100f, false, Offset(ring / 2, ring / 2), Size(size.width - ring, size.height - ring), style = Stroke(ring, cap = StrokeCap.Round))
        val face = r - ring - 5.dp.toPx()
        drawCircle(if (c.isDark) Color.Black else Color(0xFF15151A), face, center)
        // Hour marks.
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0 - 90)
            val o = face * 0.88f; val inner = face * (if (i % 3 == 0) 0.72f else 0.8f)
            drawLine(Color.White.copy(alpha = if (i % 3 == 0) 0.9f else 0.45f), Offset(center.x + (inner * cos(a)).toFloat(), center.y + (inner * sin(a)).toFloat()),
                Offset(center.x + (o * cos(a)).toFloat(), center.y + (o * sin(a)).toFloat()), (if (i % 3 == 0) 2.2f else 1.4f).dp.toPx(), cap = StrokeCap.Round)
        }
        fun hand(deg: Double, len: Float, w: Float, col: Color) {
            val a = Math.toRadians(deg - 90)
            drawLine(col, center, Offset(center.x + (len * cos(a)).toFloat(), center.y + (len * sin(a)).toFloat()), w, cap = StrokeCap.Round)
        }
        hand((t.hour % 12 + t.minute / 60.0) * 30, face * 0.48f, 3.2.dp.toPx(), Color.White)
        hand((t.minute + t.second / 60.0) * 6, face * 0.68f, 2.4.dp.toPx(), Color.White)
        hand(t.second * 6.0, face * 0.74f, 1.2.dp.toPx(), c.pulse)
        drawCircle(c.pulse, 2.6.dp.toPx(), center)
        if (!connected) drawCircle(Color.Black.copy(alpha = 0.45f), face, center)
    }
}

/** Every ready-made theme as a small preview of the app in it; tap to switch. */
@Composable
fun ThemeGallery(selected: ThemeMode, onPick: (ThemeMode) -> Unit) {
    val c = HL.colors
    Text("Theme", style = HL.type.label, color = c.inkSoft)
    Gap(8)
    THEMES.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { info ->
                val on = info.mode == selected
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(c.surface)
                        .border(if (on) 2.dp else 1.dp, if (on) c.pulse else c.line, RoundedCornerShape(16.dp))
                        .selectable(on, role = Role.RadioButton) { onPick(info.mode) }.padding(10.dp),
                ) {
                    if (info.mode == ThemeMode.SYSTEM) Row(Modifier.fillMaxWidth().aspectRatio(1.35f).clip(RoundedCornerShape(10.dp))) {
                        ThemePreview(paletteFor(ThemeMode.LIGHT, false), Modifier.weight(1f).fillMaxHeight())
                        ThemePreview(paletteFor(ThemeMode.DARK, true), Modifier.weight(1f).fillMaxHeight())
                    } else ThemePreview(paletteFor(info.mode, false), Modifier.fillMaxWidth().aspectRatio(1.35f).clip(RoundedCornerShape(10.dp)))
                    Gap(8)
                    Text(info.name, style = HL.type.bodyStrong, color = c.ink)
                    Text(info.about, style = HL.type.small.copy(fontSize = 12.sp), color = c.inkSoft, maxLines = 2)
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** A tiny sketch of the Today page in palette [p]: header band, a big number, two cards and a tab bar. */
@Composable
private fun ThemePreview(p: Palette, modifier: Modifier) {
    Canvas(modifier.background(p.paper)) {
        val w = size.width; val h = size.height; val u = w / 20
        drawRect(p.wash, size = Size(w, h * 0.42f))
        drawRoundRect(p.ink, Offset(u * 1.5f, u * 2), Size(u * 5, u * 4.2f), androidx.compose.ui.geometry.CornerRadius(u / 2))
        drawRoundRect(p.inkSoft.copy(alpha = 0.6f), Offset(u * 7.5f, u * 4.6f), Size(u * 6, u * 0.9f), androidx.compose.ui.geometry.CornerRadius(u))
        listOf(p.sleepDeep, p.pulse, p.ok).forEachIndexed { i, col ->
            drawRoundRect(col, Offset(u * 1.5f + i * u * 5.8f, h * 0.36f), Size(u * 4.8f, u * 0.7f), androidx.compose.ui.geometry.CornerRadius(u))
        }
        repeat(2) { i ->
            val x = u * 1.2f + i * u * 9f; val y = h * 0.5f
            drawRoundRect(p.surface, Offset(x, y), Size(u * 8.4f, h * 0.3f), androidx.compose.ui.geometry.CornerRadius(u))
            drawRoundRect(p.line, Offset(x, y), Size(u * 8.4f, h * 0.3f), androidx.compose.ui.geometry.CornerRadius(u), style = Stroke(1f))
            drawCircle(if (i == 0) p.sleepDeep else p.pulse, u * 0.6f, Offset(x + u * 1.3f, y + u * 1.3f))
            drawRoundRect(p.ink, Offset(x + u * 0.9f, y + u * 2.4f), Size(u * 4f, u * 1.4f), androidx.compose.ui.geometry.CornerRadius(u / 3))
        }
        drawRect(p.paper, Offset(0f, h * 0.86f), Size(w, h * 0.14f))
        repeat(5) { i -> drawCircle(if (i == 0) p.pulse else p.inkSoft.copy(alpha = 0.6f), u * 0.5f, Offset(w * (i + 0.5f) / 5, h * 0.93f)) }
    }
}

/** Send a test notification, shown as any app. */
@Composable
fun SendNotificationScreen(controller: HeartlineController, nav: Navigator) {
    val conn by controller.connection.collectAsStateWithLifecycle()
    Screen("Send to your wrist", onBack = { nav.pop() }, subtitle = "A test notification, shown as the app you pick.") {
        NotifyComposer(controller, conn.state == LinkState.CONNECTED)
    }
}
