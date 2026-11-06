package dev.heartline.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heartline.app.HeartlineController
import dev.heartline.service.Insight
import dev.heartline.service.InsightsSettings
import dev.heartline.service.GeminiText
import androidx.compose.foundation.clickable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import dev.heartline.ui.components.Segmented
import dev.heartline.ui.Navigator
import dev.heartline.ui.Route
import dev.heartline.ui.components.AreaLabel
import dev.heartline.ui.components.ButtonStyle
import dev.heartline.ui.components.Card
import dev.heartline.ui.components.Delta
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.Note
import dev.heartline.ui.components.PillButton
import dev.heartline.ui.theme.Area
import dev.heartline.ui.theme.HL
import dev.heartline.ui.theme.Palette
import dev.heartline.ui.theme.hue

private fun areaOf(name: String): Area = when (name) {
    "sleep" -> Area.SLEEP; "heart" -> Area.HEART; "activity" -> Area.MOVE; "stress" -> Area.STRESS; "body" -> Area.BODY; else -> Area.RECOVERY
}
private fun areaTitle(name: String) = when (name) { "activity" -> "Activity"; "body" -> "Body"; else -> name.replaceFirstChar { it.uppercase() } }

/**
 * AI insights: the owner's OpenRouter model reads two weeks of Heartline data and writes what stands out. Separate from
 * the Alexa assistant; nothing is sent until they ask.
 */
@Composable
fun InsightsScreen(controller: HeartlineController, nav: Navigator) {
    val c = HL.colors
    val state by controller.insights.collectAsStateWithLifecycle()
    val ai by controller.assistantAi.collectAsStateWithLifecycle()
    val speech by controller.speech.collectAsStateWithLifecycle()
    val settings by controller.insightsSettings.collectAsStateWithLifecycle()
    val gemini = settings.provider == InsightsSettings.GEMINI
    val modelName = if (gemini) settings.geminiModel else settings.openRouterModel.ifBlank { ai.model }
    val report = state.report
    Screen("AI insights", onBack = { nav.pop() },
        subtitle = report?.let { "Written ${agoOf(it.at)} by ${it.model.substringAfter('/')}, in ${"%.0f".format(it.tookMs / 1000.0)} s" }
            ?: "Patterns across your sleep, heart, stress and activity, in plain words.") {
        ModelPicker(settings, ai.model) { controller.setInsightsSettings(it) }
        Gap(14)
        val missingKey = if (gemini) speech.geminiKeyEnd == null else ai.keyEnd == null && ai.key2End == null && ai.key3End == null
        if (missingKey) {
            Card {
                Text(if (gemini) "Add a Gemini key first" else "Add an OpenRouter key first", style = HL.type.bodyStrong, color = c.ink)
                MutedText(if (gemini) "Insights use your Google AI Studio key, the one the Alexa button uses to hear you." else "Insights use the same OpenRouter keys as the Alexa button.")
                Gap(10)
                PillButton("Open the key settings", { nav.push(Route.AlexaPage(if (gemini) AlexaPages.VOICE else AlexaPages.BRAIN)) }, style = ButtonStyle.QUIET)
            }
            return@Screen
        }
        PillButton(when { state.running -> "Reading your data…"; report == null -> "Write my insights"; else -> "Write new insights" },
            { controller.refreshInsights() }, Modifier.fillMaxWidth(), enabled = !state.running)
        if (state.running) {
            Gap(10)
            LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)), color = c.pulse, trackColor = c.line)
            Gap(6)
            MutedText("Gathering two weeks of data and asking ${modelName.substringAfter('/')}. This can take up to a minute.")
        }
        state.error?.let { Gap(10); Note(it, color = c.pulse, title = "Couldn't write insights") }
        if (report == null) {
            Gap(16)
            Note("Heartline sends a summary of your readiness, two weeks of trends, last night's sleep, heart rate, activity, stress, SpO2, temperature and gym captures to " +
                (if (gemini) "Google's Gemini API with your key." else "your OpenRouter model, which may look up other days with the same read-only tools the Alexa button uses."),
                title = "What is sent")
            return@Screen
        }
        Gap(18)
        // ---- Headline ----
        if (report.headline.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.wash).padding(18.dp)) {
                Column {
                    AreaLabel("The main takeaway", c.pulse)
                    Gap(6)
                    Text(report.headline, style = HL.type.section.copy(fontSize = 24.sp, lineHeight = 29.sp), color = c.ink, modifier = Modifier.semantics { heading() })
                }
            }
        }
        val watch = report.insights.count { it.tone == "watch" }; val good = report.insights.count { it.tone == "good" }
        Gap(12)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (good > 0) Delta("$good going well", true)
            if (good > 0 && watch > 0) Spacer(Modifier.width(8.dp))
            if (watch > 0) Delta("$watch to watch", false)
        }
        Gap(4)
        report.insights.forEach { i -> Gap(10); InsightCard(i, c, nav) }
        Gap(18)
        MutedText("Written by an AI model from your Heartline data. It can be wrong and is not medical advice; for anything that worries you, talk to a doctor.")
    }
}

@Composable
private fun InsightCard(i: Insight, c: Palette, nav: Navigator) {
    val area = areaOf(i.area)
    val toneColor: Color = when (i.tone) { "good" -> c.ok; "watch" -> c.pulse; else -> c.inkSoft }
    Card(padding = 0.dp) {
        Row {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AreaLabel(areaTitle(i.area), c.hue(area), Modifier.weight(1f))
                    Delta(when (i.tone) { "good" -> "Going well"; "watch" -> "Watch"; else -> "Note" }, when (i.tone) { "good" -> true; "watch" -> false; else -> null })
                }
                Gap(6)
                Text(i.title, style = HL.type.bodyStrong.copy(fontSize = 18.sp, lineHeight = 24.sp), color = c.ink)
                Gap(4)
                Text(i.detail, style = HL.type.body, color = c.ink)
                i.action?.let { a ->
                    Gap(10)
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(toneColor.copy(alpha = 0.10f)).padding(10.dp)) {
                        Text("Try: ", style = HL.type.bodyStrong.copy(fontSize = 14.sp), color = toneColor)
                        Text(a, style = HL.type.small.copy(fontSize = 14.sp, lineHeight = 20.sp), color = c.ink)
                    }
                }
            }
        }
    }
}

/** Gemini or OpenRouter, and the model; saved as soon as it changes. */
@Composable
private fun ModelPicker(s: InsightsSettings, brainModel: String, onChange: (InsightsSettings) -> Unit) {
    val c = HL.colors
    var open by remember { mutableStateOf(false) }
    val gemini = s.provider == InsightsSettings.GEMINI
    Card(padding = 14.dp) {
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = if (open) "Hide model settings" else "Change the model", role = Role.Button) { open = !open },
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Model", style = HL.type.label, color = c.inkSoft)
                Text((if (gemini) "Gemini · " + s.geminiModel else "OpenRouter · " + s.openRouterModel.ifBlank { brainModel }).substringAfter('/'),
                    style = HL.type.bodyStrong, color = c.ink)
            }
            Text(if (open) "Done" else "Change", style = HL.type.label, color = c.pulse)
        }
        if (open) {
            Gap(12)
            Segmented(listOf(InsightsSettings.GEMINI to "Gemini", InsightsSettings.OPENROUTER to "OpenRouter"), s.provider, { onChange(s.copy(provider = it)) })
            Gap(12)
            var model by remember(s.provider) { mutableStateOf(if (gemini) s.geminiModel else s.openRouterModel) }
            OutlinedTextField(model, { model = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(if (gemini) "Gemini model" else "OpenRouter model (blank: the Alexa brain's)") })
            Gap(8)
            PillButton("Save model", { onChange(if (gemini) s.copy(geminiModel = model.ifBlank { GeminiText.DEFAULT_MODEL }) else s.copy(openRouterModel = model)) },
                style = ButtonStyle.QUIET)
            Gap(8)
            MutedText(if (gemini) "Gemini uses your AI Studio key. Gemini 3.8 Flash is the default: the smartest free model there (20 requests a day on the free tier)."
                else "OpenRouter uses the Alexa brain's keys and can look up extra days with the health tools while it writes.")
        }
    }
}
