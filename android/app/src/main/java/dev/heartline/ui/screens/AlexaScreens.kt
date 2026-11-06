package dev.heartline.ui.screens

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heartline.app.HeartlineController
import dev.heartline.service.AlexaSettings
import dev.heartline.service.AssistantAi
import dev.heartline.service.TurnRecord
import dev.heartline.ui.Navigator
import dev.heartline.ui.Route
import dev.heartline.ui.components.ButtonStyle
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.PillButton
import dev.heartline.ui.components.SectionTitle
import dev.heartline.ui.components.Segmented
import dev.heartline.ui.theme.HL
import java.util.Locale
import kotlinx.coroutines.launch

/** The Alexa pages a [Route.AlexaPage] can open. */
object AlexaPages {
    const val LOG = "log"; const val VOICE = "voice"; const val BRAIN = "brain"; const val PROMPT = "prompt"
    const val TOOLS = "tools"; const val WEB = "web"; const val WATCH = "watch"; const val ALERTS = "alerts"
}

private fun secs(ms: Long) = String.format(Locale.ROOT, "%.1f s", ms / 1000.0)

// ---------------- The hub ----------------

@Composable
fun AlexaScreen(controller: HeartlineController, nav: Navigator) {
    val c = HL.colors
    val s by controller.alexaSettings.collectAsStateWithLifecycle()
    val turns by controller.alexaTurns.collectAsStateWithLifecycle()
    val ai by controller.assistantAi.collectAsStateWithLifecycle()
    val speech by controller.speech.collectAsStateWithLifecycle()
    val web by controller.webSearch.collectAsStateWithLifecycle()
    val alerts by controller.alexaAlerts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }
    Screen("Alexa", onBack = { nav.pop() },
        subtitle = "Press the watch's Alexa button and speak. Gemini writes down what you said, an OpenRouter model answers using Heartline's tools, and the answer shows on the watch.") {
        val missing = listOfNotNull("a Gemini key".takeIf { speech.geminiKeyEnd == null }, "an OpenRouter key".takeIf { ai.keyEnd == null })
        if (missing.isNotEmpty()) {
            Text("Alexa can't answer until you add ${missing.joinToString(" and ")}.", style = HL.type.bodyStrong, color = c.warn)
            Gap(12)
        }
        SectionTitle("Last question")
        Gap(6)
        turns.firstOrNull()?.let { NavGroup { TurnRow(it) { nav.push(Route.AlexaTurn(it.at)) } } } ?: MutedText("Nothing asked yet. Questions and everything done to answer them show up here.")
        Gap(20)
        SectionTitle("Set up")
        NavGroup {
            NavRow("Voice", speech.geminiKeyEnd?.let { "Gemini key ending in $it, ${s.geminiModel.removePrefix("models/")}" } ?: "No Gemini key") { nav.push(Route.AlexaPage(AlexaPages.VOICE)) }
            NavRow("Brain", listOfNotNull(ai.model, ai.keyEnd?.let { "key ending in $it" } ?: "no key", ai.key2End?.let { "second key ending in $it" }, ai.key3End?.let { "third key ending in $it" }).joinToString(", ")) { nav.push(Route.AlexaPage(AlexaPages.BRAIN)) }
            NavRow("Web search", listOfNotNull("Tavily".takeIf { web.tavilyEnd != null }, "Exa".takeIf { web.exaEnd != null }).joinToString(" and ").ifEmpty { "Off: no key" }) { nav.push(Route.AlexaPage(AlexaPages.WEB)) }
        }
        Gap(20)
        SectionTitle("How it answers")
        NavGroup {
            NavRow("Instructions", if (s.prompt.isBlank()) "Heartline's own" else "Your own, ${s.prompt.length} characters") { nav.push(Route.AlexaPage(AlexaPages.PROMPT)) }
            val toolCount = controller.alexaTools.size
            NavRow("Tools", "${controller.alexaTools.count { s.enabled(it.name) }} of $toolCount on") { nav.push(Route.AlexaPage(AlexaPages.TOOLS)) }
            NavRow("On the watch", "Replies up to ${"%,d".format(s.replyBytes)} bytes, then up to ${s.maxNotifications} notifications") { nav.push(Route.AlexaPage(AlexaPages.WATCH)) }
        }
        Gap(20)
        SectionTitle("What it did")
        NavGroup {
            NavRow("Conversation log", if (turns.isEmpty()) "Empty" else "${turns.size} question" + (if (turns.size > 1) "s" else "") + ", with every step") { nav.push(Route.AlexaPage(AlexaPages.LOG)) }
            NavRow("Set by voice", if (alerts.isEmpty()) "No alarms, timers or reminders" else "${alerts.size} still to come") { nav.push(Route.AlexaPage(AlexaPages.ALERTS)) }
        }
        Gap(20)
        SectionTitle("Try a question")
        Gap(6)
        OutlinedTextField(question, { question = it }, label = { Text("Question") }, modifier = Modifier.fillMaxWidth())
        Gap(8)
        PillButton(if (asking) "Asking…" else "Ask", {
            asking = true; result = null
            scope.launch { result = controller.testAssistantAi(question.trim()); asking = false }
        }, enabled = !asking && question.isNotBlank())
        result?.let { Gap(10); Text(it, style = HL.type.small, color = c.ink) }
        Gap(6)
        MutedText("Ask works out what the watch would do without sending anything to it, and adds the question to the conversation log.")
        Gap(20)
        SectionTitle("What leaves the phone")
        Gap(6)
        MutedText("Gemini gets the recording of each question. OpenRouter gets the words heard, the instructions (with the time, your latest heart rate and steps, a short summary of today and what's set by voice), " +
            "the earlier questions in the conversation and what each tool returns. Web searches go to Tavily or Exa. Keys stay on the phone and are never logged.")
    }
}

/** One question in a list: what was heard, the start of the answer, and how it went. */
@Composable
private fun TurnRow(t: TurnRecord, onClick: () -> Unit) {
    val c = HL.colors
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Text(dateTimeOf(t.at / 1000) + if (t.source != "Watch") ", ${t.source.lowercase()}" else "", style = HL.type.label, color = c.inkSoft)
        Text(t.heard?.let { "“$it”" } ?: "Nothing heard", style = HL.type.bodyStrong, color = c.ink)
        t.reply?.let { Text(it.take(160) + if (it.length > 160) "…" else "", style = HL.type.small, color = c.ink) }
        val tools = t.rounds.flatMap { r -> r.calls.map { it.name } }.distinct()
        MutedText(listOfNotNull(secs(t.totalMs), "${t.rounds.size} request" + if (t.rounds.size != 1) "s" else "",
            tools.takeIf { it.isNotEmpty() }?.joinToString(", ")).joinToString(", "))
        t.error?.let { Text(it, style = HL.type.small, color = c.warn) }
    }
    HorizontalDivider(color = c.line)
}

// ---------------- Sub-pages ----------------

@Composable
fun AlexaPageScreen(controller: HeartlineController, page: String, nav: Navigator) {
    when (page) {
        AlexaPages.LOG -> LogPage(controller, nav)
        AlexaPages.VOICE -> VoicePage(controller, nav)
        AlexaPages.BRAIN -> BrainPage(controller, nav)
        AlexaPages.PROMPT -> PromptPage(controller, nav)
        AlexaPages.TOOLS -> ToolsPage(controller, nav)
        AlexaPages.WEB -> WebPage(controller, nav)
        AlexaPages.WATCH -> WatchPage(controller, nav)
        AlexaPages.ALERTS -> AlertsPage(controller, nav)
    }
}

@Composable
private fun LogPage(controller: HeartlineController, nav: Navigator) {
    val turns by controller.alexaTurns.collectAsStateWithLifecycle()
    val s by controller.alexaSettings.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    Screen("Conversation log", onBack = { nav.pop() }, subtitle = "The last ${s.keepTurns} questions, newest first. Open one to see every request, tool call and result.") {
        if (turns.isEmpty()) MutedText("Nothing asked yet.")
        turns.forEach { t -> TurnRow(t) { nav.push(Route.AlexaTurn(t.at)) } }
        if (turns.isNotEmpty()) {
            Gap(16)
            PillButton(if (confirm) "Tap again to clear the log" else "Clear the log", {
                if (confirm) { controller.clearAlexaTurns(); confirm = false } else confirm = true
            }, style = ButtonStyle.QUIET)
            Gap(6)
            MutedText("Clearing removes the log only; recordings are kept until newer questions replace them.")
        }
    }
}

@Composable
fun AlexaTurnScreen(controller: HeartlineController, at: Long, nav: Navigator) {
    val c = HL.colors
    val turns by controller.alexaTurns.collectAsStateWithLifecycle()
    val t = turns.firstOrNull { it.at == at }
    Screen("Question", onBack = { nav.pop() }, subtitle = t?.let { dateTimeOf(it.at / 1000) + ", " + it.source.lowercase() }) {
        if (t == null) { MutedText("This question is no longer in the log."); return@Screen }
        SectionTitle(if (t.audioS == null) "Asked" else "Heard")
        Gap(6)
        Text(t.heard?.let { "“$it”" } ?: "Nothing heard", style = HL.type.body, color = c.ink)
        MutedText(listOfNotNull(t.audioS?.let { String.format(Locale.ROOT, "%.1f s of audio", it) }, t.speechMs?.let { "Gemini took ${secs(it)}" }).joinToString(", ").ifEmpty { "Typed in the app" })
        t.recording?.let { name -> controller.alexaRecording(name)?.let { Gap(8); PlayButton(it) } }
        Gap(20)
        SectionTitle("Answer")
        Gap(6)
        t.reply?.let { SelectionContainer { Text(it, style = HL.type.body, color = c.ink) } }
        MutedText("${t.delivery}. ${secs(t.totalMs)} in all" + if (t.source == "Watch") " from the end of speech." else ".")
        t.error?.let { Gap(4); Text("What went wrong: $it", style = HL.type.small, color = c.warn) }
        Gap(20)
        SectionTitle("Steps")
        Gap(6)
        if (t.rounds.isEmpty()) MutedText("No request reached OpenRouter.")
        t.rounds.forEachIndexed { i, r ->
            Text("Request ${i + 1}: ${r.model}", style = HL.type.bodyStrong, color = c.ink)
            MutedText(listOfNotNull(secs(r.ms), "key ${r.key}", "reasoning ${r.reasoning}",
                r.tokensIn?.let { "$it tokens in" }, r.tokensOut?.let { "$it out" }).joinToString(", "))
            r.calls.forEach { call ->
                Gap(8)
                Text("Called ${call.name} (${secs(call.ms)})", style = HL.type.label, color = c.ink)
                Expandable("Arguments") { Code(call.args) }
                Expandable("What Heartline returned") { Code(call.result) }
            }
            r.text?.let { if (r.calls.isNotEmpty()) { Gap(6); MutedText("Also said: $it") } }
            Gap(12); HorizontalDivider(color = c.line); Gap(12)
        }
        SectionTitle("Instructions sent")
        Gap(6)
        MutedText(if (t.memory == 0) "No earlier questions were sent along." else "${t.memory} earlier question" + (if (t.memory > 1) "s and answers were" else " and answer was") + " sent along.")
        if (t.prompt.isNotBlank()) Expandable("Show the instructions") { Code(t.prompt) }
    }
}

@Composable
private fun PlayButton(uri: android.net.Uri) {
    val ctx = LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(uri) { onDispose { player?.release() } }
    PillButton(if (player != null) "Stop" else "Play the recording", {
        if (player != null) { player?.release(); player = null }
        else player = MediaPlayer.create(ctx, uri)?.apply { setOnCompletionListener { it.release(); player = null }; start() }
    }, style = ButtonStyle.QUIET)
}

@Composable
private fun VoicePage(controller: HeartlineController, nav: Navigator) {
    val s by controller.alexaSettings.collectAsStateWithLifecycle()
    Screen("Voice", onBack = { nav.pop() }, subtitle = "Gemini turns each question's recording into words. Only the final words are used.") {
        SpeechSection(controller)
        Gap(24)
        SectionTitle("Recognition")
        Gap(6)
        TextSetting("Model", "Gemini's Live API model that transcribes. Heartline's: ${dev.heartline.service.GeminiSpeech.MODEL.removePrefix("models/")}",
            s.geminiModel.removePrefix("models/")) { v -> controller.updateAlexaSettings { it.copy(geminiModel = v) } }
        ListSetting("Languages", "Language codes Gemini listens for, one per line, such as en-IN.", s.languages) { v -> controller.updateAlexaSettings { it.copy(languages = v) } }
        ListSetting("Words to expect", "Names and terms Gemini should recognise, one per line.", s.vocabulary) { v -> controller.updateAlexaSettings { it.copy(vocabulary = v) } }
        NumberSetting("Time limit", "Seconds to wait for Gemini's words (5 to 30). Longer leaves less of the watch's ~40 s for the answer.", s.geminiTimeoutS, 5..30) { v ->
            controller.updateAlexaSettings { it.copy(geminiTimeoutS = v) } }
    }
}

@Composable
private fun BrainPage(controller: HeartlineController, nav: Navigator) {
    val s by controller.alexaSettings.collectAsStateWithLifecycle()
    Screen("Brain", onBack = { nav.pop() }, subtitle = "The OpenRouter model reads the instructions and your question, calls tools as it needs, and writes the answer.") {
        OpenRouterKeys(controller)
        Gap(24)
        SectionTitle("Models")
        Gap(6)
        ListSetting("Fallback models", "Tried in order when the model fails, such as when it's busy. OpenRouter uses the first two.", s.fallbackModels) { v ->
            controller.updateAlexaSettings { it.copy(fallbackModels = v) } }
        Gap(8)
        Text("Reasoning", style = HL.type.bodyStrong, color = HL.colors.ink)
        MutedText("Auto asks for none and allows a little when a model insists. Thinking takes longer; the watch waits about 40 s.")
        Gap(8)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Segmented(AlexaSettings.REASONING.map { it to it.replaceFirstChar { c -> c.uppercase() } }, s.reasoning, { v -> controller.updateAlexaSettings { it.copy(reasoning = v) } })
        }
        Gap(12)
        DecimalSetting("Temperature", "0 is the most predictable, 2 the most varied (0 to 2).", s.temperature, 0.0..2.0) { v -> controller.updateAlexaSettings { it.copy(temperature = v) } }
        NumberSetting("Longest reply in tokens", "Without reasoning (200 to 32,000). A 1,000-word story is about 1,400.", s.maxTokens, 200..32_000) { v ->
            controller.updateAlexaSettings { it.copy(maxTokens = v) } }
        NumberSetting("Longest reply with reasoning", "Tokens, thinking included (500 to 64,000).", s.reasoningMaxTokens, 500..64_000) { v ->
            controller.updateAlexaSettings { it.copy(reasoningMaxTokens = v) } }
        NumberSetting("Requests per question", "Each round of tool calls is one request (1 to 12).", s.maxRounds, 1..12) { v -> controller.updateAlexaSettings { it.copy(maxRounds = v) } }
        NumberSetting("Time limit", "Seconds for the whole answer (15 to 300). After the watch stops waiting, it comes as a notification.", s.answerTimeoutS, 15..300) { v ->
            controller.updateAlexaSettings { it.copy(answerTimeoutS = v) } }
        NumberSetting("Longest answer kept", "Characters (500 to 50,000); longer answers are cut.", s.maxAnswerChars, 500..50_000) { v ->
            controller.updateAlexaSettings { it.copy(maxAnswerChars = v) } }
        Gap(16)
        SectionTitle("Memory")
        Gap(6)
        NumberSetting("Earlier questions sent along", "So follow-ups make sense (0 to 20; 0 forgets each question).", s.memoryTurns, 0..20) { v ->
            controller.updateAlexaSettings { it.copy(memoryTurns = v) } }
        NumberSetting("New conversation after", "Minutes without a question (0 to 120).", s.memoryMinutes, 0..120) { v ->
            controller.updateAlexaSettings { it.copy(memoryMinutes = v) } }
    }
}

@Composable
private fun PromptPage(controller: HeartlineController, nav: Navigator) {
    val c = HL.colors
    val s by controller.alexaSettings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var text by remember(s.prompt) { mutableStateOf(s.promptTemplate) }
    var preview by remember { mutableStateOf<String?>(null) }
    val changed = text != s.promptTemplate
    Screen("Instructions", onBack = { nav.pop() }, subtitle = "What the model is told before every question. Placeholders in braces are filled in each time.") {
        MutedText(if (s.prompt.isBlank()) "Using Heartline's instructions." else "Using your own instructions.")
        Gap(8)
        OutlinedTextField(text, { text = it; preview = null }, textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = c.ink),
            modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp))
        Gap(10)
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton("Save", { controller.updateAlexaSettings { it.copy(prompt = if (text.trim() == AssistantAi.DEFAULT_PROMPT.trim()) "" else text) } }, enabled = changed)
            Spacer(Modifier.width(10.dp))
            PillButton("Use Heartline's", { text = AssistantAi.DEFAULT_PROMPT; controller.updateAlexaSettings { it.copy(prompt = "") } },
                style = ButtonStyle.QUIET, enabled = s.prompt.isNotBlank() || changed)
        }
        Gap(10)
        PillButton("Show what the model gets now", { scope.launch { preview = controller.alexaPromptPreview(text) } }, style = ButtonStyle.QUIET)
        preview?.let { Gap(8); Code(it) }
        Gap(20)
        SectionTitle("Placeholders")
        Gap(6)
        AssistantAi.PLACEHOLDERS.forEach { (k, v) ->
            Row(Modifier.padding(vertical = 4.dp)) {
                Text(k, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = c.ink), modifier = Modifier.width(130.dp))
                Text(v, style = HL.type.small, color = c.inkSoft)
            }
        }
        Gap(12)
        MutedText("The tools' own descriptions are sent separately; change them on the Tools page.")
    }
}

@Composable
private fun ToolsPage(controller: HeartlineController, nav: Navigator) {
    val c = HL.colors
    val s by controller.alexaSettings.collectAsStateWithLifecycle()
    Screen("Tools", onBack = { nav.pop() }, subtitle = "What the model can look up and do. A tool that's off isn't offered to it. Tap one to change how it's described.") {
        controller.alexaTools.groupBy { it.group }.forEach { (group, tools) ->
            Gap(12); SectionTitle(group); Gap(4)
            tools.forEach { t ->
                Row(Modifier.fillMaxWidth().clickable { nav.push(Route.AlexaTool(t.name)) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = if (s.enabled(t.name)) c.ink else c.inkSoft))
                        Text((s.toolDescriptions[t.name] ?: t.description), style = HL.type.small, color = c.inkSoft, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        if (s.toolDescriptions.containsKey(t.name)) Text("Your description", style = HL.type.label, color = c.pulse)
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(s.enabled(t.name), { on -> controller.updateAlexaSettings { it.copy(disabledTools = if (on) it.disabledTools - t.name else it.disabledTools + t.name) } },
                        modifier = Modifier.semantics { contentDescription = "Let Alexa use ${t.name}" },
                        colors = SwitchDefaults.colors(checkedTrackColor = c.ok, checkedThumbColor = c.surface, uncheckedTrackColor = c.gridMajor, uncheckedThumbColor = c.surface, uncheckedBorderColor = c.gridMajor))
                }
                HorizontalDivider(color = c.line)
            }
        }
    }
}

@Composable
fun AlexaToolScreen(controller: HeartlineController, name: String, nav: Navigator) {
    val s by controller.alexaSettings.collectAsStateWithLifecycle()
    val t = controller.alexaTools.firstOrNull { it.name == name }
    Screen(name, onBack = { nav.pop() }, subtitle = t?.group) {
        if (t == null) { MutedText("No such tool."); return@Screen }
        ToggleRow("Offered to the model", if (s.enabled(name)) "The model can call it." else "Off: the model isn't told about it.", s.enabled(name)) { on ->
            controller.updateAlexaSettings { it.copy(disabledTools = if (on) it.disabledTools - name else it.disabledTools + name) }
        }
        Gap(16)
        var text by remember(s.toolDescriptions[name]) { mutableStateOf(s.toolDescriptions[name] ?: t.description) }
        Text("Description", style = HL.type.bodyStrong, color = HL.colors.ink)
        MutedText("The model decides when to call the tool from this.")
        Gap(8)
        OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth())
        Gap(10)
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton("Save", { controller.updateAlexaSettings {
                it.copy(toolDescriptions = if (text.trim() == t.description || text.isBlank()) it.toolDescriptions - name else it.toolDescriptions + (name to text.trim()))
            } }, enabled = text.trim() != (s.toolDescriptions[name] ?: t.description))
            if (s.toolDescriptions.containsKey(name)) {
                Spacer(Modifier.width(10.dp))
                PillButton("Use Heartline's", { text = t.description; controller.updateAlexaSettings { it.copy(toolDescriptions = it.toolDescriptions - name) } }, style = ButtonStyle.QUIET)
            }
        }
        Gap(20)
        SectionTitle("What it takes")
        Gap(6)
        MutedText("The arguments the model fills in, as JSON Schema.")
        Gap(6)
        Code(t.parameters)
    }
}

@Composable
private fun WebPage(controller: HeartlineController, nav: Navigator) {
    val s by controller.alexaSettings.collectAsStateWithLifecycle()
    Screen("Web search", onBack = { nav.pop() }, subtitle = "The web_search tool, for news, scores, prices and anything recent.") {
        WebSearchSection(controller)
        Gap(20)
        NumberSetting("Pages per search", "How many results the model reads (1 to 10). Exa charges more for more.", s.webResults, 1..10) { v ->
            controller.updateAlexaSettings { it.copy(webResults = v) } }
    }
}

@Composable
private fun WatchPage(controller: HeartlineController, nav: Navigator) {
    val s by controller.alexaSettings.collectAsStateWithLifecycle()
    Screen("On the watch", onBack = { nav.pop() }, subtitle = "How answers reach the watch. Measured on the GTR 2e: a reply shows about 2,045 bytes, and a notification takes 1,024 bytes (1,025 restarts the watch).") {
        NumberSetting("Reply length", "Bytes on the reply screen (100 to 2,000); the rest goes out as notifications of up to 960 bytes.", s.replyBytes, 100..2000) { v ->
            controller.updateAlexaSettings { it.copy(replyBytes = v) } }
        NumberSetting("Most notifications", "Per answer (0 to 60); a longer answer ends \"(cut short)\".", s.maxNotifications, 0..60) { v ->
            controller.updateAlexaSettings { it.copy(maxNotifications = v) } }
        NumberSetting("Wait before \"Still thinking\"", "Seconds (5 to 38). The watch gives up at about 40 s, so a slower answer comes as a notification.", s.watchWaitS, 5..38) { v ->
            controller.updateAlexaSettings { it.copy(watchWaitS = v) } }
        ToggleRow("Listen again after a question", "When an answer ends with a question, the watch opens the mic for your reply.", s.listenAgain) { v ->
            controller.updateAlexaSettings { it.copy(listenAgain = v) } }
        NumberSetting("Questions kept", "Recordings and log entries (1 to 200).", s.keepTurns, 1..200) { v -> controller.updateAlexaSettings { it.copy(keepTurns = v) } }
    }
}

@Composable
private fun AlertsPage(controller: HeartlineController, nav: Navigator) {
    val alerts by controller.alexaAlerts.collectAsStateWithLifecycle()
    Screen("Set by voice", onBack = { nav.pop() }, subtitle = "Alarms, timers and reminders set with the Alexa button. Reminders use the watch's reminder slots, shared with the calendar.") {
        if (alerts.isEmpty()) MutedText("Nothing set by voice is still to come.")
        alerts.forEach { a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.kind, style = HL.type.bodyStrong, color = HL.colors.ink)
                    MutedText(a.text.replaceFirstChar { it.uppercase() })
                }
                PillButton("Cancel", { controller.cancelAlexaAlert(a.id) }, style = ButtonStyle.QUIET)
            }
            HorizontalDivider(color = HL.colors.line)
        }
    }
}

// ---------------- Building blocks ----------------

/** Monospaced, selectable text on a tinted panel: JSON, prompts, tool results. */
@Composable
private fun Code(text: String) {
    SelectionContainer {
        Text(text, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = HL.colors.ink),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(HL.colors.wash).padding(10.dp))
    }
}

@Composable
private fun Expandable(title: String, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text((if (open) "Hide " else "Show ") + title.replaceFirstChar { it.lowercase() }.removePrefix("show "), style = HL.type.label, color = HL.colors.pulse,
        modifier = Modifier.clickable { open = !open }.padding(vertical = 6.dp))
    if (open) content()
}

@Composable
private fun SettingLabel(title: String, detail: String) {
    Text(title, style = HL.type.bodyStrong, color = HL.colors.ink)
    MutedText(detail)
    Gap(6)
}

/** A whole number saved as soon as it's in [range]. */
@Composable
private fun NumberSetting(title: String, detail: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val valid = text.toIntOrNull()?.let { it in range } == true
    Column(Modifier.padding(vertical = 8.dp)) {
        SettingLabel(title, detail)
        OutlinedTextField(text, { v -> text = v.filter { it.isDigit() }.take(6); text.toIntOrNull()?.takeIf { it in range && it != value }?.let(onChange) },
            singleLine = true, isError = !valid, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(160.dp))
    }
}

@Composable
private fun DecimalSetting(title: String, detail: String, value: Double, range: ClosedFloatingPointRange<Double>, onChange: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val valid = text.toDoubleOrNull()?.let { it in range } == true
    Column(Modifier.padding(vertical = 8.dp)) {
        SettingLabel(title, detail)
        OutlinedTextField(text, { v -> text = v.filter { it.isDigit() || it == '.' }.take(5); text.toDoubleOrNull()?.takeIf { it in range && it != value }?.let(onChange) },
            singleLine = true, isError = !valid, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(160.dp))
    }
}

@Composable
private fun TextSetting(title: String, detail: String, value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(vertical = 8.dp)) {
        SettingLabel(title, detail)
        OutlinedTextField(text, { v -> text = v; if (v.isNotBlank()) onChange(v.trim()) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}

/** One value per line. */
@Composable
private fun ListSetting(title: String, detail: String, values: List<String>, onChange: (List<String>) -> Unit) {
    var text by remember { mutableStateOf(values.joinToString("\n")) }
    Column(Modifier.padding(vertical = 8.dp)) {
        SettingLabel(title, detail)
        OutlinedTextField(text, { v -> text = v; onChange(v.lines().map { it.trim() }.filter { it.isNotEmpty() }) }, modifier = Modifier.fillMaxWidth())
    }
}
