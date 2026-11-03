package dev.heartline.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heartline.app.HeartlineController
import dev.heartline.app.LinkState
import dev.heartline.protocol.VibrationPatterns
import dev.heartline.ui.Icons
import dev.heartline.ui.Navigator
import dev.heartline.ui.components.ButtonStyle
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.PillButton
import dev.heartline.ui.components.Segmented
import dev.heartline.ui.theme.HL

// ---------------- Watch apps ----------------

@Composable
fun WatchMenuScreen(controller: HeartlineController, nav: Navigator) {
    val c = HL.colors
    val apps by controller.watchMenu.collectAsStateWithLifecycle()
    val conn by controller.connection.collectAsStateWithLifecycle()
    val connected = conn.state == LinkState.CONNECTED
    fun move(i: Int, by: Int) {
        val j = i + by
        if (j !in apps.indices) return
        controller.setWatchMenu(apps.toMutableList().also { it[i] = apps[j]; it[j] = apps[i] })
    }
    Screen("Watch apps", onBack = { nav.pop() },
        subtitle = "Pick the apps your watch lists, and their order, then send the list. Apps your watch doesn't have are skipped.") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton("Send to watch", { controller.sendWatchMenu() }, enabled = connected)
            Spacer(Modifier.width(12.dp))
            MutedText(if (connected) "${apps.count { it.shown }} apps shown" else "Connect the watch to send")
        }
        Gap(12)
        apps.forEachIndexed { i, a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Switch(a.shown, { on -> controller.setWatchMenu(apps.map { if (it.key == a.key) it.copy(shown = on) else it }) },
                    colors = SwitchDefaults.colors(checkedTrackColor = c.ok, checkedThumbColor = c.surface, uncheckedTrackColor = c.gridMajor, uncheckedThumbColor = c.surface, uncheckedBorderColor = c.gridMajor))
                Text(a.label, style = HL.type.body, color = if (a.shown) c.ink else c.inkSoft, modifier = Modifier.weight(1f).padding(start = 12.dp))
                IconButton({ move(i, -1) }, enabled = i > 0) { Icon(Icons.chevron, "Move ${a.label} up", Modifier.size(18.dp).rotate(-90f), tint = c.inkSoft) }
                IconButton({ move(i, 1) }, enabled = i < apps.lastIndex) { Icon(Icons.chevron, "Move ${a.label} down", Modifier.size(18.dp).rotate(90f), tint = c.inkSoft) }
            }
            HorizontalDivider(color = c.line)
        }
        Gap(16)
        PillButton("Show every app", { controller.setWatchMenu(apps.map { it.copy(shown = true) }) }, style = ButtonStyle.QUIET)
        Gap(8)
        MutedText("The watch can't report its current list, so this starts from Zepp's usual set. More than 16 apps puts the rest under More.")
    }
}

// ---------------- Vibration patterns ----------------

@Composable
fun VibrationScreen(controller: HeartlineController, nav: Navigator) {
    val c = HL.colors
    val list by controller.vibrations.collectAsStateWithLifecycle()
    val conn by controller.connection.collectAsStateWithLifecycle()
    val connected = conn.state == LinkState.CONNECTED
    Screen("Vibration patterns", onBack = { nav.pop() },
        subtitle = "How the watch buzzes for each kind of alert. Picking a pattern sets it on the watch; Try buzzes it once.") {
        if (!connected) { MutedText("Connect the watch to try or set patterns."); Gap(8) }
        list.forEach { v ->
            val profile = v.profile ?: "short"
            var open by remember { mutableStateOf(false) }
            // Choosing a pattern or a count sets it on the watch straight away; Try only buzzes.
            fun set(p: String, r: Int) = controller.setVibration(v.kind, p, r)
            Gap(14)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(v.label, style = HL.type.bodyStrong, color = c.ink)
                    MutedText(if (v.profile == null) "Watch default" else "Set to " + VibrationPatterns.PROFILES.first { it.key == v.profile }.label.lowercase() + ", ×${v.repeat}")
                }
                PillButton("Try", { controller.tryVibration(v.kind, profile, v.repeat) }, style = ButtonStyle.QUIET, enabled = connected)
            }
            Gap(8)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    PillButton(VibrationPatterns.PROFILES.first { it.key == profile }.label, { open = true }, style = ButtonStyle.QUIET, enabled = connected)
                    DropdownMenu(open, { open = false }) {
                        VibrationPatterns.PROFILES.forEach { p -> DropdownMenuItem(text = { Text(p.label) }, onClick = { open = false; set(p.key, v.repeat) }) }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Segmented(listOf(1 to "×1", 2 to "×2", 3 to "×3", 5 to "×5"), v.repeat, { if (connected) set(profile, it) })
            }
            Gap(14); HorizontalDivider(color = c.line)
        }
    }
}
