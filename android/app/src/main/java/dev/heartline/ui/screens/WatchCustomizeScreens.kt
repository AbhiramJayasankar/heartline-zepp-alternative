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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.launch
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
        subtitle = "Pick the apps your watch lists, and their order, then send the list. Apps below More open from the watch's More screen. Apps your watch doesn't have are skipped.") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton("Send to watch", { controller.sendWatchMenu() }, enabled = connected)
            Spacer(Modifier.width(12.dp))
            MutedText(if (connected) "${apps.count { it.shown }} apps shown" else "Connect the watch to send")
        }
        Gap(12)
        val moreAt = apps.indexOfFirst { it.key == "more" && it.shown }
        if (moreAt != -1 && apps.drop(moreAt + 1).none { it.shown }) {
            Text("Nothing is below More, so the watch hides its More screen. Move apps below it, or use Put the rest under More.", style = HL.type.small, color = c.warn)
            Gap(8)
        }
        apps.forEachIndexed { i, a ->
            if (a.key == "more" && a.shown) {
                Gap(10); Text("Inside More", style = HL.type.label, color = c.inkSoft); Gap(2)
            }
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
        PillButton("Put the rest under More", {
            // Shown apps stay on the main list in their order; More follows, then every other app inside it.
            val main = apps.filter { it.shown && it.key != "more" }
            val rest = apps.filter { !it.shown && it.key != "more" }.map { it.copy(shown = true) }
            controller.setWatchMenu(main + apps.first { it.key == "more" }.copy(shown = true) + rest)
        }, style = ButtonStyle.QUIET)
        Gap(8)
        PillButton("Show every app", { controller.setWatchMenu(apps.map { it.copy(shown = true) }) }, style = ButtonStyle.QUIET)
        Gap(8)
        MutedText("The watch can't report its current list, so this starts from Zepp's usual set. More than 16 apps before More puts the rest under it. Some entries (notifications, flashlight, barometer) are built into the watch and don't change its list.")
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
        subtitle = "How the watch buzzes for each kind of alert. Picking a pattern or count sets it on the watch; Try buzzes it once. On the GTR 2e the count is what you feel; the patterns themselves buzz alike.") {
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

// ---------------- Alexa answers ----------------

@Composable
fun AssistantScreen(controller: HeartlineController, nav: Navigator) {
    val c = HL.colors
    val ai by controller.assistantAi.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var model by remember(ai.model) { mutableStateOf(ai.model) }
    var question by remember { mutableStateOf("Set an alarm for 9:30 am") }
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    Screen("Alexa answers", onBack = { nav.pop() },
        subtitle = "The watch's Alexa button: Gemini turns what you say into text, and an OpenRouter model answers. If either isn't working, the watch says so.") {
        SpeechSection(controller)
        Gap(24)
        Text("Answers (OpenRouter)", style = HL.type.bodyStrong, color = c.ink)
        Gap(4)
        MutedText(ai.keyEnd?.let { "OpenRouter key saved, ending in $it." } ?: "Paste an OpenRouter API key. Free models work.")
        Gap(8)
        OutlinedTextField(key, { key = it.trim() }, label = { Text(if (ai.keyEnd == null) "OpenRouter API key" else "Replace the key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Gap(8)
        OutlinedTextField(model, { model = it.trim() }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Gap(10)
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton("Save", { controller.setAssistantAi(key.ifEmpty { null }, model); key = "" })
            if (ai.keyEnd != null) {
                Spacer(Modifier.width(10.dp))
                PillButton("Remove key", { controller.setAssistantAi("", model) }, style = ButtonStyle.QUIET)
            }
        }
        Gap(16)
        OutlinedTextField(question, { question = it }, label = { Text("Try a question") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Gap(8)
        PillButton(if (testing) "Asking…" else "Ask", {
            testing = true; result = null
            scope.launch { result = controller.testAssistantAi(question.trim()); testing = false }
        }, style = ButtonStyle.QUIET, enabled = !testing && question.isNotBlank())
        result?.let { Gap(10); Text(it, style = HL.type.small, color = c.ink) }
        Gap(16)
        MutedText("Ask shows what the watch would do, without sending anything to it. If the model fails, OpenRouter tries its free router. " +
            "Each question sends the words heard, the time, the weather, a short summary of today and the alarms, timers and reminders set by voice to OpenRouter. " +
            "The model can also ask Heartline for detail (heart rate, stress, sleep, blood oxygen, temperature, activity, readiness, trends, workouts), which is sent when it asks.")
    }
}

/** The Gemini key that turns what's said to the Alexa button into text, and a test with the last recording. */
@Composable
private fun SpeechSection(controller: HeartlineController) {
    val c = HL.colors
    val speech by controller.speech.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    Text("Speech recognition (Gemini)", style = HL.type.bodyStrong, color = c.ink)
    Gap(4)
    MutedText(speech.geminiKeyEnd?.let { "Gemini key saved, ending in $it. Uses Gemini 3.5 Transcribe Live (free tier)." }
        ?: "Paste a Gemini API key from Google AI Studio. Uses Gemini 3.5 Transcribe Live (free tier).")
    Gap(8)
    OutlinedTextField(key, { key = it.trim() }, label = { Text(if (speech.geminiKeyEnd == null) "Gemini API key" else "Replace the Gemini key") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    Gap(8)
    Row(verticalAlignment = Alignment.CenterVertically) {
        PillButton("Save key", { controller.setGeminiKey(key); key = "" }, enabled = key.isNotEmpty())
        if (speech.geminiKeyEnd != null) { Spacer(Modifier.width(10.dp)); PillButton("Remove key", { controller.setGeminiKey("") }, style = ButtonStyle.QUIET) }
    }
    Gap(10)
    PillButton(if (testing) "Listening…" else "Test with the last question", {
        testing = true; result = null
        scope.launch { result = controller.testSpeech(); testing = false }
    }, style = ButtonStyle.QUIET, enabled = !testing && speech.geminiKeyEnd != null)
    result?.let { Gap(8); Text(it, style = HL.type.small, color = c.ink) }
    Gap(6)
    MutedText("The test runs your last Alexa recording through Gemini and the answers model, without sending anything to the watch. Gemini receives the recording of each question.")
}
