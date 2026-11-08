package dev.heartline.ui.screens

import dev.heartline.ui.theme.raised

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heartline.app.HeartlineController
import dev.heartline.service.GeminiText
import dev.heartline.service.Insight
import dev.heartline.service.InsightAttempt
import dev.heartline.service.InsightReport
import dev.heartline.service.InsightsSettings
import dev.heartline.ui.Navigator
import dev.heartline.ui.Route
import dev.heartline.ui.components.AreaLabel
import dev.heartline.ui.components.ButtonStyle
import dev.heartline.ui.components.Card
import dev.heartline.ui.components.Delta
import dev.heartline.ui.components.Heading
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.Note
import dev.heartline.ui.components.PillButton
import dev.heartline.ui.components.Segmented
import dev.heartline.ui.theme.Area
import dev.heartline.ui.theme.HL
import dev.heartline.ui.theme.Palette
import dev.heartline.ui.theme.hue
import kotlinx.coroutines.launch

private fun areaOf(name: String): Area = when (name) {
    "sleep" -> Area.SLEEP; "heart" -> Area.HEART; "activity" -> Area.MOVE; "stress" -> Area.STRESS; "body" -> Area.BODY; else -> Area.RECOVERY
}
private fun areaTitle(name: String) = when (name) { "activity" -> "Activity"; "body" -> "Body"; else -> name.replaceFirstChar { it.uppercase() } }

/**
 * AI insights: a model reads two weeks of Heartline data and writes what stands out. Separate from the Alexa assistant.
 * Everything is visible: the model and its fallbacks, every attempt, the data sent, the instructions and the raw reply.
 */
@Composable
fun InsightsScreen(controller: HeartlineController, nav: Navigator) {
    val c = HL.colors
    val state by controller.insights.collectAsStateWithLifecycle()
    val aiSetup by controller.aiSetup.collectAsStateWithLifecycle()
    val settings by controller.insightsSettings.collectAsStateWithLifecycle()
    val report = state.report
    Screen("AI insights", onBack = { nav.pop() },
        subtitle = report?.let { "Written ${agoOf(it.at)}${if (it.auto) " (daily)" else ""} by ${it.model.substringAfter('/')} in ${"%.0f".format(it.tookMs / 1000.0)} s" }
            ?: "Patterns across your sleep, heart, stress and activity, in plain words.") {
        AiChainCard(controller, dev.heartline.service.ai.AiSection.INSIGHTS, nav)
        Gap(10)
        DailyInsights(controller, settings)
        Gap(14)
        if (!aiSetup.ready(dev.heartline.service.ai.AiSection.INSIGHTS)) {
            Card {
                Text("Choose a model with a key first", style = HL.type.bodyStrong, color = c.ink)
                MutedText("Insights can be written by any model you've picked under AI models, with fallbacks if it's busy or out of quota.")
                Gap(10)
                PillButton("Choose the models", { nav.push(Route.AiPage(AiPages.section(dev.heartline.service.ai.AiSection.INSIGHTS))) }, style = ButtonStyle.QUIET)
            }
            return@Screen
        }
        PillButton(when { state.running -> "Reading your data…"; report == null -> "Write my insights"; else -> "Write new insights" },
            { controller.refreshInsights() }, Modifier.fillMaxWidth(), enabled = !state.running)
        if (state.running) {
            Gap(10)
            LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)), color = c.pulse, trackColor = c.line)
            Gap(6)
            MutedText("Gathering two weeks of data and asking ${aiSetup.section(dev.heartline.service.ai.AiSection.INSIGHTS).main?.model?.substringAfterLast('/') ?: "the model"}. A busy model is asked again after a pause, then the next one is tried; this can take a minute or two.")
        }
        state.error?.let { err ->
            Gap(10)
            Note("Every model failed this time. The last one said: $err", color = c.pulse, title = "Couldn't write insights")
            if (state.failedAttempts.isNotEmpty()) { Gap(8); Attempts(state.failedAttempts) }
        }
        if (report == null) {
            Gap(16)
            Note("Heartline gathers readiness, two weeks of trends, the last two nights of sleep, heart rate, activity, stress, SpO2 and temperature for yesterday and today, gym captures and heart checks, and sends them with its instructions to the model. Nothing is sent until you ask, or until the daily run if it's on.",
                title = "What is sent")
            return@Screen
        }
        Gap(18)
        // ---- Headline ----
        if (report.headline.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.raised).padding(18.dp)) {
                Column {
                    AreaLabel("The main takeaway", c.pulse, dot = false)
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

        // ---- Transparency ----
        Heading("How this was written")
        HowItWasMade(report)
        Gap(18)
        MutedText("Written by an AI model from your Heartline data. It can be wrong and is not medical advice; for anything that worries you, talk to a doctor.")
    }
}

@Composable
private fun InsightCard(i: Insight, c: Palette, nav: Navigator) {
    // Where the finding lives in the app: its tab, page or trend.
    val today = java.time.LocalDate.now().toEpochDay()
    val (linkLabel, open) = when (i.area) {
        "sleep" -> "Open Sleep" to { nav.toTab(dev.heartline.ui.Tab.SLEEP) }
        "heart" -> "Open Heart" to { nav.toTab(dev.heartline.ui.Tab.HEART) }
        "activity" -> "Open Activity" to { nav.toTab(dev.heartline.ui.Tab.ACTIVITY) }
        "stress" -> "Open today's stress" to { nav.push(dayRoute(DayMetric.STRESS, today)) }
        "body" -> "Open blood oxygen and temperature" to { nav.push(dayRoute(DayMetric.BODY, today)) }
        else -> "How readiness is worked out" to { nav.push(dev.heartline.ui.Route.Readiness()) }
    }
    val area = areaOf(i.area)
    val toneColor: Color = when (i.tone) { "good" -> c.ok; "watch" -> c.pulse; else -> c.inkSoft }
    Card(padding = 16.dp, onClick = open, onClickLabel = linkLabel) {
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
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(toneColor.copy(alpha = 0.10f)).padding(10.dp)) {
                Text("Try: ", style = HL.type.bodyStrong.copy(fontSize = 14.sp), color = toneColor)
                Text(a, style = HL.type.small.copy(fontSize = 14.sp, lineHeight = 20.sp), color = c.ink)
            }
        }
        if (i.basis.isNotEmpty()) {
            Gap(10)
            Text("Based on", style = HL.type.label.copy(fontSize = 12.sp), color = c.inkSoft)
            Gap(4)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                i.basis.forEach { b ->
                    Box(Modifier.clip(RoundedCornerShape(50)).background(c.raised).padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text(b, style = HL.type.small.copy(fontSize = 12.sp), color = c.ink)
                    }
                }
            }
        }
        Gap(10)
        Text("$linkLabel ›", style = HL.type.label, color = c.pulse)
    }
}

/** Step by step: what was gathered, what the model was told, each attempt, tokens, and the raw reply. */
@Composable
private fun HowItWasMade(r: InsightReport) {
    val c = HL.colors
    if (r.digest.isEmpty() && r.attempts.isEmpty()) {
        Note("This set was written before Heartline kept a record of how insights are made. Write new insights to see the exact data the model saw, its instructions, every request and its raw reply.")
        return
    }
    Card(padding = 16.dp) {
        Step("1", "Your data was gathered", "${r.digest.lines().count { it.startsWith("## ") }} sections, ${"%,d".format(r.digest.length)} characters, read from the same lookups the Alexa button uses.")
        r.digest.takeIf { it.isNotEmpty() }?.let { d ->
            val sections = d.split("\n## ").map { it.removePrefix("## ") }
            FlowRow(Modifier.padding(start = 34.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                sections.forEach { s -> Box(Modifier.clip(RoundedCornerShape(50)).background(c.raised).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(s.lineSequence().first(), style = HL.type.small.copy(fontSize = 12.sp), color = c.ink)
                } }
            }
            Expandable("See the exact data sent", d)
        }
        Gap(12)
        Step("2", "With these instructions", "Heartline's instructions ask for 4 to 7 findings with your numbers, the data each rests on, and no diagnosis.")
        r.system.takeIf { it.isNotEmpty() }?.let { Expandable("See the instructions", it) }
        Gap(12)
        Step("3", "Sent to the model", "${r.attempts.size} request" + (if (r.attempts.size == 1) "" else "s") + "; the first that answered wrote these.")
        if (r.attempts.isNotEmpty()) { Gap(6); Box(Modifier.padding(start = 34.dp)) { Attempts(r.attempts) } }
        Gap(12)
        Step("4", "The reply came back", listOfNotNull(
            r.promptTokens?.let { "%,d tokens in".format(it) }, r.outputTokens?.let { "%,d out".format(it) },
            "${r.insights.size} insights parsed from its JSON",
        ).joinToString(", ") + ".")
        r.raw.takeIf { it.isNotEmpty() }?.let { Expandable("See the raw reply", it) }
    }
}

@Composable
private fun Step(n: String, title: String, detail: String) {
    val c = HL.colors
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.width(24.dp).height(24.dp).clip(RoundedCornerShape(50)).background(c.raised), contentAlignment = Alignment.Center) {
            Text(n, style = HL.type.label.copy(fontSize = 12.sp), color = c.ink)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = HL.type.bodyStrong, color = c.ink)
            Text(detail, style = HL.type.small, color = c.inkSoft)
        }
    }
}

/** A list of requests: model, time and the error each failed one gave. */
@Composable
private fun Attempts(list: List<InsightAttempt>) {
    val c = HL.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.raised).padding(10.dp)) {
        list.forEachIndexed { i, a ->
            if (i > 0) Gap(8)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (a.error == null) "✓" else "✕", style = HL.type.bodyStrong, color = if (a.error == null) c.ok else c.pulse, modifier = Modifier.width(18.dp))
                Text("${if (a.provider == InsightsSettings.GEMINI) "Gemini" else "OpenRouter"} · ${a.model.substringAfter('/')}", style = HL.type.small.copy(fontSize = 13.sp),
                    color = c.ink, modifier = Modifier.weight(1f))
                Text("%.1f s".format(a.ms / 1000.0), style = HL.type.small, color = c.inkSoft)
            }
            a.error?.let { Text(it.take(220), style = HL.type.small.copy(fontSize = 12.sp), color = c.inkSoft, modifier = Modifier.padding(start = 18.dp)) }
        }
    }
}

/** A titled block of text that opens on tap, in a monospace face, selectable. */
@Composable
private fun Expandable(title: String, text: String) {
    val c = HL.colors
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(start = 34.dp, top = 6.dp)) {
        Box(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClickLabel = if (open) "Hide" else "Show") { open = !open }
            .padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
            Text((if (open) "Hide: " else "") + title, style = HL.type.label, color = c.pulse)
        }
        if (open) Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.raised).padding(10.dp).horizontalScroll(rememberScrollState())) {
            SelectionContainer { Text(text, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, color = c.ink)) }
        }
    }
}

/** The daily run: saved as soon as it changes. The models and their fallbacks are under AI models. */
@Composable
private fun DailyInsights(controller: HeartlineController, s: InsightsSettings) {
    val c = HL.colors
    fun set(n: InsightsSettings) = controller.setInsightsSettings(n)
    Card(padding = 14.dp) {
        ToggleRow("Write new insights every day", "After the hour below, once a day; if every model is busy it tries again every 30 minutes.", s.auto) { set(s.copy(auto = it)) }
        if (s.auto) {
            Gap(4)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("From", style = HL.type.body, color = c.ink, modifier = Modifier.weight(1f))
                Segmented(listOf(7 to "07:00", 9 to "09:00", 12 to "12:00", 18 to "18:00"), s.autoHour, { set(s.copy(autoHour = it)) })
            }
        }
    }
}
