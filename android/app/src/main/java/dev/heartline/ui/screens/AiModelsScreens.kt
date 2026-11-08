package dev.heartline.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heartline.app.HeartlineController
import dev.heartline.service.ai.AiAttempt
import dev.heartline.service.ai.AiCatalog
import dev.heartline.service.ai.AiProvider
import dev.heartline.service.ai.AiSection
import dev.heartline.service.ai.AiSetup
import dev.heartline.service.ai.CatalogModel
import dev.heartline.service.ai.FailKind
import dev.heartline.service.ai.ModelRef
import dev.heartline.service.ai.Need
import dev.heartline.service.ai.ProviderKind
import dev.heartline.service.ai.RetryPolicy
import dev.heartline.service.ai.SectionSetup
import dev.heartline.ui.Icons
import dev.heartline.ui.Navigator
import dev.heartline.ui.Route
import dev.heartline.ui.components.ButtonStyle
import dev.heartline.ui.components.Card
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.PillButton
import dev.heartline.ui.components.SectionTitle
import dev.heartline.ui.components.Segmented
import dev.heartline.ui.theme.HL
import dev.heartline.ui.theme.Palette
import dev.heartline.ui.theme.raised
import kotlinx.coroutines.launch

/** The pages under AI models, as [Route.AiPage] names them. */
object AiPages {
    const val MODELS = "models"
    const val ACTIVITY = "activity"
    const val NEW_PROVIDER = "provider:+"
    fun section(s: AiSection) = "section:${s.key}"
    fun provider(id: String) = "provider:$id"
}

private val LEVELS = listOf("Main model", "Fallback 1", "Fallback 2")
private val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)

/** Each stop on a section's ladder has its own colour: the main model in Heartline's red, the fallbacks quieter. */
private fun levelColor(level: Int, c: Palette) = when (level) { 0 -> c.pulse; 1 -> c.sleepDeep; else -> c.inkSoft }

/** A model's short name for tight spaces: the part after the vendor, without :free. */
private fun short(ref: ModelRef) = ref.model.substringAfterLast('/').removeSuffix(":free").removePrefix("models/")

private fun display(ref: ModelRef, cat: AiCatalog) = cat.of(ref)?.name?.takeIf { it.isNotBlank() && it != ref.model } ?: short(ref)

private fun clock(ms: Long) = java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()))

/** What a section's chain lacks, in a few words, or null when it can answer. */
private fun problem(setup: AiSetup, s: AiSection): String? {
    val chain = setup.section(s).chain
    if (chain.isEmpty()) return "No model chosen"
    if (!setup.ready(s)) return "No key for " + chain.mapNotNull { setup.provider(it.provider)?.name }.distinct().joinToString(" or ")
    return null
}

// ---------------------------------------------------------------------------------------------------------------------
// Overview

@Composable
fun AiModelsScreen(controller: HeartlineController, nav: Navigator) {
    val c = HL.colors
    val setup by controller.aiSetup.collectAsStateWithLifecycle()
    val cat by controller.aiCatalog.collectAsStateWithLifecycle()
    val activity by controller.aiActivity.collectAsStateWithLifecycle()
    Screen("AI models", onBack = { nav.pop() }, icon = Icons.sparkle, tint = c.sleepDeep,
        subtitle = "Where Heartline's AI comes from, which model each feature uses, and what happens when one fails.") {
        SectionTitle("Where AI is used")
        Gap(4)
        MutedText("Each feature tries its main model, then its fallbacks. Tap one to choose models and how it retries.")
        Gap(8)
        AiSection.entries.forEach { s ->
            SectionCard(s, setup, cat, activity.firstOrNull { it.section == s.key }) { nav.push(Route.AiPage(AiPages.section(s))) }
            Gap(10)
        }
        Gap(12)
        SectionTitle("Providers and keys")
        NavGroup {
            setup.providers.forEach { p ->
                NavRow(p.name, "${p.kind.label}, " + when (p.keys.size) { 0 -> "no key yet"; 1 -> "1 key"; else -> "${p.keys.size} keys, used in turn" }) {
                    nav.push(Route.AiPage(AiPages.provider(p.id)))
                }
            }
            NavRow("Add a provider", "Any OpenAI-compatible endpoint (Groq, Together, a local server) or another Gemini API key set") { nav.push(Route.AiPage(AiPages.NEW_PROVIDER)) }
        }
        Gap(20)
        SectionTitle("Models to choose from")
        NavGroup {
            val listed = cat.models.size
            NavRow("${setup.picked.size} picked" + if (listed > 0) " of $listed" else "",
                if (listed == 0) "Fetch the providers' model lists, then pick the few you want offered in each feature"
                else "Only picked models are offered in the features. " + (cat.fetchedAt.values.maxOrNull()?.let { "Lists fetched ${agoOf(it)}." } ?: "")) {
                nav.push(Route.AiPage(AiPages.MODELS))
            }
        }
        Gap(20)
        SectionTitle("Recent requests")
        if (activity.isEmpty()) { Gap(4); MutedText("Nothing asked yet. Every request, retry and fallback shows up here.") }
        else NavGroup {
            activity.take(4).forEach { AttemptRow(it, setup, cat) }
            NavRow("Every request", "${activity.size} kept, newest first") { nav.push(Route.AiPage(AiPages.ACTIVITY)) }
        }
    }
}

/** [s]'s chain in short, as on the AI models page; tapping it opens the section's page there. For the features' own screens. */
@Composable
fun AiChainCard(controller: HeartlineController, s: AiSection, nav: Navigator) {
    val setup by controller.aiSetup.collectAsStateWithLifecycle()
    val cat by controller.aiCatalog.collectAsStateWithLifecycle()
    val activity by controller.aiActivity.collectAsStateWithLifecycle()
    SectionCard(s, setup, cat, activity.firstOrNull { it.section == s.key }) { nav.push(Route.AiPage(AiPages.section(s))) }
}

/** One feature on the overview: what it does, its chain in short, and how its last request went. */
@Composable
private fun SectionCard(s: AiSection, setup: AiSetup, cat: AiCatalog, last: AiAttempt?, onClick: () -> Unit) {
    val c = HL.colors
    val sec = setup.section(s)
    val issue = problem(setup, s)
    Card(onClick = onClick, onClickLabel = "Change ${s.title}", padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.title, style = HL.type.bodyStrong, color = c.ink, modifier = Modifier.weight(1f))
            if (issue != null) Text(issue, style = HL.type.label, color = c.warn)
        }
        Gap(8)
        // The chain as a small ladder: a dot per stop on a thin rail; after the last model, one hollow "no fallback" stop.
        val stops = (0..2).map { sec.at(it) }
        val shownStops = stops.indexOfLast { it != null }.let { lastSet -> if (lastSet < 2) lastSet + 1 else 2 }
        (0..shownStops).forEach { l ->
            val ref = stops[l]
            if (l > 0) Box(Modifier.padding(start = 4.dp).width(1.5.dp).height(6.dp).background(c.line))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).then(if (ref != null) Modifier.background(levelColor(l, c)) else Modifier.border(1.5.dp, c.line, CircleShape)))
                Spacer(Modifier.width(10.dp))
                Text(ref?.let { display(it, cat) } ?: if (l == 0) "No model chosen" else "No fallback ${if (l == 1) "yet" else "2"}",
                    style = if (l == 0 && ref != null) HL.type.label else HL.type.small, color = if (ref != null) (if (l == 0) c.ink else c.inkSoft) else c.inkSoft,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                ref?.let { Text(setup.provider(it.provider)?.name ?: it.provider, style = HL.type.small, color = c.inkSoft) }
            }
        }
        last?.let { a ->
            Gap(8)
            Text("Last request ${agoOf(a.at / 1000)}: " + when {
                a.ok -> "answered by ${short(ModelRef(a.provider, a.model))} in ${"%.1f".format(a.ms / 1000.0)} s" + (if (a.level > 0) " (fallback ${a.level})" else "")
                else -> (a.kind?.label ?: a.note ?: "failed").replaceFirstChar { it.lowercase() }.let { "$it on ${short(ModelRef(a.provider, a.model))}" }
            }, style = HL.type.small, color = if (a.ok) c.inkSoft else c.warn)
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Pages

@Composable
fun AiPageScreen(controller: HeartlineController, page: String, nav: Navigator) {
    when {
        page == AiPages.MODELS -> ModelsPage(controller, nav)
        page == AiPages.ACTIVITY -> ActivityPage(controller, nav)
        page == AiPages.NEW_PROVIDER -> NewProviderPage(controller, nav)
        page.startsWith("provider:") -> ProviderPage(controller, page.removePrefix("provider:"), nav)
        page.startsWith("section:") -> AiSection.of(page.removePrefix("section:"))?.let { SectionPage(controller, it, nav) }
    }
}

/** A feature's ladder (main model and two fallbacks), a test, and its retry rules. */
@Composable
private fun SectionPage(controller: HeartlineController, s: AiSection, nav: Navigator) {
    val c = HL.colors
    val setup by controller.aiSetup.collectAsStateWithLifecycle()
    val cat by controller.aiCatalog.collectAsStateWithLifecycle()
    val sec = setup.section(s)
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf<Int?>(null) }
    var trying by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf<String?>(null) }
    Screen(s.title, onBack = { nav.pop() }, subtitle = s.what) {
        SectionTitle("Models, in order")
        Gap(4)
        MutedText(when (s.need) {
            Need.TOOLS -> "Each model needs tool calling. When one fails the conversation carries on with the next, tool results and all."
            Need.AUDIO -> "Gemini Live models transcribe as you speak; other models that take audio are sent the recording."
            Need.TEXT -> "Any text model works."
        })
        Gap(10)
        Card(padding = 14.dp) {
            (0..2).forEach { l -> LadderStop(l, sec.at(l), s.need, setup, cat, last = l == 2, onPick = { picking = l },
                onClear = { controller.aiSetSection(s, sec.with(l, null)) }) }
        }
        Gap(10)
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton(if (trying) "Trying…" else "Try it", {
                trying = true; tried = null
                scope.launch { tried = runCatching { controller.aiTrySection(s) }.getOrElse { it.message ?: "failed" }; trying = false }
            }, enabled = !trying && sec.chain.isNotEmpty(), style = ButtonStyle.QUIET)
            Spacer(Modifier.width(10.dp))
            MutedText(if (s == AiSection.VOICE) "Transcribes the last Alexa recording." else "Sends a one-word test down the chain, with these rules.")
        }
        tried?.let { Gap(8); Code(it) }

        Gap(24)
        SectionTitle("When a request fails")
        Gap(4)
        MutedText("Every failure is sorted first. Out of quota means another key, then the next model; a busy model is asked again.")
        Gap(8)
        Rules(sec.policy, s) { controller.aiSetSection(s, sec.copy(policy = it)) }
        Gap(16)
        Card(tint = c.raised, padding = 14.dp) {
            Text("What happens now", style = HL.type.bodyStrong, color = c.ink)
            Gap(6)
            outcomes(sec.policy, setup.providers.any { it.keys.size > 1 }).forEach { (k, v) ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text(k, style = HL.type.label, color = c.ink, modifier = Modifier.width(118.dp))
                    Text(v, style = HL.type.small, color = c.inkSoft, modifier = Modifier.weight(1f))
                }
            }
        }
        Gap(12)
        PillButton("Use Heartline's rules", { controller.aiSetSection(s, sec.copy(policy = RetryPolicy.default(s))) }, style = ButtonStyle.QUIET,
            enabled = sec.policy != RetryPolicy.default(s))
    }
    picking?.let { level ->
        ModelPickerDialog(s, level, sec.at(level), setup, cat, onDismiss = { picking = null }, onMore = { picking = null; nav.push(Route.AiPage(AiPages.MODELS)) }) { ref ->
            controller.aiSetSection(s, sec.with(level, ref)); picking = null
        }
    }
}

/** One stop on the ladder: a numbered dot on a rail, the model, and what might stop it working here. */
@Composable
private fun LadderStop(level: Int, ref: ModelRef?, need: Need, setup: AiSetup, cat: AiCatalog, last: Boolean, onPick: () -> Unit, onClear: () -> Unit) {
    val c = HL.colors
    val col = levelColor(level, c)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.width(30.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(24.dp).clip(CircleShape).then(if (ref != null) Modifier.background(col) else Modifier.border(1.5.dp, c.line, CircleShape)),
                contentAlignment = Alignment.Center) {
                Text("${level + 1}", style = HL.type.label, color = if (ref != null) c.surface else c.inkSoft)
            }
            if (!last) Box(Modifier.padding(vertical = 4.dp).width(2.dp).weight(1f).background(c.line))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 16.dp)) {
            Text(LEVELS[level] + if (level > 0) ", if the one above can't answer" else "", style = HL.type.small, color = c.inkSoft)
            if (ref == null) {
                Text("Not set", style = HL.type.bodyStrong, color = c.inkSoft)
            } else {
                val p = setup.provider(ref.provider)
                val m = cat.of(ref)
                Text(display(ref, cat), style = HL.type.bodyStrong, color = c.ink)
                Text(p?.name ?: ref.provider, style = HL.type.small, color = c.inkSoft)
                if (ref.model != display(ref, cat)) Text(ref.model, style = mono, color = c.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val warn = when {
                    p == null -> "This provider was removed."
                    p.keys.isEmpty() -> "${p.name} has no key yet."
                    m != null && !m.suits(need) -> when (need) { Need.TOOLS -> "Its listing says it can't call tools."; Need.AUDIO -> "Its listing says it doesn't take audio."; Need.TEXT -> "It only streams audio." }
                    else -> null
                }
                warn?.let { Text(it, style = HL.type.small, color = c.warn) }
            }
            Row(Modifier.padding(top = 2.dp)) {
                Text(if (ref == null) "Choose" else "Change", style = HL.type.label, color = c.pulse,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onPick).padding(end = 16.dp, top = 8.dp, bottom = 8.dp))
                if (ref != null && level > 0) Text("Clear", style = HL.type.label, color = c.inkSoft,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClear).padding(horizontal = 4.dp, vertical = 8.dp))
            }
        }
    }
}

/** The picked models to choose from for one stop, those that suit the feature first. */
@Composable
private fun ModelPickerDialog(s: AiSection, level: Int, current: ModelRef?, setup: AiSetup, cat: AiCatalog, onDismiss: () -> Unit, onMore: () -> Unit,
                              onPick: (ModelRef) -> Unit) {
    val c = HL.colors
    val options = setup.picked.sortedBy { r -> if (cat.of(r)?.suits(s.need) == false) 1 else 0 }
    AlertDialog(onDismissRequest = onDismiss, containerColor = c.surface,
        title = { Text("${LEVELS[level]} for ${s.title.lowercase()}", style = HL.type.section, color = c.ink) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (options.isEmpty()) MutedText("No models picked yet. Pick a few from the providers' lists first.")
                options.forEach { r ->
                    val m = cat.of(r)
                    val suits = m?.suits(s.need) != false
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(r) }
                        .background(if (r == current) c.raised else Color.Transparent).padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (suits) levelColor(level, c) else c.line))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(display(r, cat), style = HL.type.bodyStrong, color = if (suits) c.ink else c.inkSoft)
                            Text("${setup.provider(r.provider)?.name ?: r.provider}  ${r.model}", style = mono, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!suits) Text(if (s.need == Need.TOOLS) "Can't call tools" else if (s.need == Need.AUDIO) "Doesn't take audio" else "Audio only", style = HL.type.small, color = c.warn)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onMore) { Text("Pick more models", style = HL.type.label, color = c.pulse) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = HL.type.label, color = c.inkSoft) } })
}

/** The section's retry rules, each saved as it changes. */
@Composable
private fun Rules(p: RetryPolicy, s: AiSection, onChange: (RetryPolicy) -> Unit) {
    val c = HL.colors
    Card(padding = 14.dp) {
        ToggleRow("Try the provider's other keys first", "When a key is out of quota, refused or rate limited, before moving to the next model.", p.otherKeys) {
            onChange(p.copy(otherKeys = it)) }
        HorizontalDivider(color = c.line)
        Stepper("Busy, timed out or offline", "Ask the same model again this many times, then the next model.", p.busyRetries, 0..6, "times") { onChange(p.copy(busyRetries = it)) }
        if (p.busyRetries > 0) {
            var text by remember(p.retryWaitsS) { mutableStateOf(p.retryWaitsS.joinToString(", ")) }
            Text("Waits before those tries", style = HL.type.label, color = c.ink)
            MutedText("Seconds, one per try, such as 5, 20. The last one repeats.")
            Gap(4)
            OutlinedTextField(text, { v ->
                text = v.filter { it.isDigit() || it == ',' || it == ' ' }
                text.split(',').mapNotNull { it.trim().toIntOrNull() }.takeIf { it.isNotEmpty() }?.let { onChange(p.copy(retryWaitsS = it)) }
            }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(200.dp))
            Gap(8)
        }
        HorizontalDivider(color = c.line)
        Stepper("Rate limited", "When it says when to come back, wait up to this long on the same model; longer, the next key or model.", p.waitForLimitS, 0..300, "s", step = 5) {
            onChange(p.copy(waitForLimitS = it)) }
        HorizontalDivider(color = c.line)
        Stepper("Unusable answer", "Empty, cut off or not in the asked shape: ask the same model again this many times.", p.badAnswerRetries, 0..4, "times") {
            onChange(p.copy(badAnswerRetries = it)) }
        HorizontalDivider(color = c.line)
        Stepper("Time limit per request", if (s == AiSection.ASSISTANT || s == AiSection.VOICE) "The watch waits about 40 s for the whole answer." else "Longer suits slow, thinking models.",
            p.timeoutS, 5..600, "s", step = if (p.timeoutS >= 60) 15 else 5) { onChange(p.copy(timeoutS = it)) }
    }
}

/** A labelled number with minus and plus buttons. */
@Composable
private fun Stepper(title: String, detail: String, value: Int, range: IntRange, unit: String, step: Int = 1, onChange: (Int) -> Unit) {
    val c = HL.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = HL.type.bodyStrong, color = c.ink)
            MutedText(detail)
        }
        Spacer(Modifier.width(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, c.line, RoundedCornerShape(50))) {
            Text("−", style = HL.type.section, color = if (value > range.first) c.ink else c.line,
                modifier = Modifier.clickable(enabled = value > range.first, onClickLabel = "Less") { onChange((value - step).coerceAtLeast(range.first)) }.padding(horizontal = 12.dp, vertical = 2.dp))
            Text("$value " + if (unit == "times" && value == 1) "time" else unit, style = HL.type.label, color = c.ink, modifier = Modifier.widthIn(min = 54.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("+", style = HL.type.section, color = if (value < range.last) c.ink else c.line,
                modifier = Modifier.clickable(enabled = value < range.last, onClickLabel = "More") { onChange((value + step).coerceAtMost(range.last)) }.padding(horizontal = 12.dp, vertical = 2.dp))
        }
    }
}

/** The rules in plain words, failure by failure. */
private fun outcomes(p: RetryPolicy, manyKeys: Boolean): List<Pair<String, String>> {
    val keys = if (p.otherKeys) "the provider's next key, then the next model" else "the next model"
    val waits = p.retryWaitsS.joinToString(", ") { "$it s" }
    return listOf(
        FailKind.QUOTA.label to "That key rests for 30 minutes for this model; $keys.",
        FailKind.AUTH.label to "The same: $keys.",
        FailKind.RATE_LIMIT.label to (if (p.waitForLimitS > 0) "Waits up to ${p.waitForLimitS} s when told when to come back; otherwise $keys." else "$keys.".replaceFirstChar { it.uppercase() }),
        "Busy or offline" to (if (p.busyRetries == 0) "Straight to the next model." else "Asks the same model ${p.busyRetries} more time${if (p.busyRetries > 1) "s" else ""} (waiting $waits), then the next model."),
        FailKind.MODEL.label to "Straight to the next model, as for a request too long for it.",
        FailKind.BAD_ANSWER.label to (if (p.badAnswerRetries == 0) "Straight to the next model." else "Asks again ${p.badAnswerRetries} time${if (p.badAnswerRetries > 1) "s" else ""}, then the next model."),
    ) + if (!manyKeys && p.otherKeys) listOf("Tip" to "Add more keys to a provider and they take turns when one runs out.") else emptyList()
}

// ---------------------------------------------------------------------------------------------------------------------
// Providers

@Composable
private fun ProviderPage(controller: HeartlineController, id: String, nav: Navigator) {
    val c = HL.colors
    val setup by controller.aiSetup.collectAsStateWithLifecycle()
    val activity by controller.aiActivity.collectAsStateWithLifecycle()
    val cat by controller.aiCatalog.collectAsStateWithLifecycle()
    val p = setup.provider(id) ?: run { Screen("Provider", onBack = { nav.pop() }) { MutedText("This provider was removed.") }; return }
    val scope = rememberCoroutineScope()
    var newKey by remember { mutableStateOf("") }
    var tests by remember { mutableStateOf<List<String?>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var fetched by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    Screen(p.name, onBack = { nav.pop() }, subtitle = "${p.kind.label} at ${p.baseUrl.removePrefix("https://")}") {
        if (!p.builtIn) {
            var name by remember(p.name) { mutableStateOf(p.name) }
            var url by remember(p.baseUrl) { mutableStateOf(p.baseUrl) }
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Gap(8)
            OutlinedTextField(url, { url = it.trim() }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Gap(8)
            PillButton("Save", { controller.aiSaveProvider(p.id, p.kind, name, url) }, style = ButtonStyle.QUIET, enabled = name != p.name || url != p.baseUrl)
            Gap(20)
        }
        SectionTitle("Keys")
        Gap(4)
        MutedText("Used in order, starting with the one that worked last. A key that runs out of quota or is refused rests for 30 minutes and the next one takes over.")
        Gap(8)
        if (p.keys.isEmpty()) MutedText("No key yet.") else Card(padding = 12.dp) {
            p.keys.forEachIndexed { i, masked ->
                val end = masked.removePrefix("…")
                val last = activity.firstOrNull { it.provider == p.id && it.keyEnd == end && it.keyIndex >= 0 }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(when { last == null -> c.line; last.ok -> c.ok; last.kind?.keyBound == true -> c.warn; else -> c.inkSoft }))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Key ${i + 1}, ending in $end", style = HL.type.bodyStrong, color = c.ink)
                        val test = tests?.getOrNull(i)
                        Text(when {
                            tests != null -> test ?: "Works"
                            last == null -> "Not used yet"
                            last.ok -> "Worked ${agoOf(last.at / 1000)}"
                            else -> "${last.kind?.label ?: "Failed"} ${agoOf(last.at / 1000)}"
                        }, style = HL.type.small, color = if (tests != null && test != null) c.warn else c.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (i > 0) TextButton(onClick = { controller.aiMoveKey(p.id, i, true) }) { Text("Up", style = HL.type.label, color = c.inkSoft) }
                    TextButton(onClick = { controller.aiRemoveKey(p.id, i); tests = null }) { Text("Remove", style = HL.type.label, color = c.pulse) }
                }
                if (i < p.keys.lastIndex) HorizontalDivider(color = c.line)
            }
        }
        Gap(10)
        OutlinedTextField(newKey, { newKey = it.trim() }, label = { Text(if (p.keys.isEmpty()) "Paste a key" else "Add another key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Gap(8)
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton("Add key", { controller.aiAddKey(p.id, newKey); newKey = ""; tests = null }, enabled = newKey.length >= 8)
            Spacer(Modifier.width(10.dp))
            PillButton(if (busy == "test") "Testing…" else "Test every key", {
                busy = "test"; scope.launch { tests = runCatching { controller.aiTestKeys(p.id) }.getOrNull(); busy = null }
            }, style = ButtonStyle.QUIET, enabled = busy == null && p.keys.isNotEmpty())
        }
        MutedText(when (p.id) {
            AiProvider.OPENROUTER -> "Keys from openrouter.ai/keys. Each account has its own free daily limit, so keys from several accounts take turns."
            AiProvider.GEMINI -> "Keys from aistudio.google.com. Each Google project has its own free quota per model."
            else -> "Keys never leave the phone except to this provider."
        }, Modifier.padding(top = 8.dp))
        Gap(20)
        SectionTitle("Models")
        Gap(4)
        val count = cat.models.count { it.ref.provider == p.id }
        MutedText((if (count == 0) "Its model list hasn't been fetched." else "$count models listed" + (cat.fetchedAt[p.id]?.let { ", fetched ${agoOf(it)}" } ?: "") + ".") +
            (cat.errors[p.id]?.let { " Last fetch failed: $it" } ?: ""))
        Gap(8)
        Row {
            PillButton(if (busy == "fetch") "Fetching…" else "Fetch its models", {
                busy = "fetch"; scope.launch {
                    fetched = runCatching { controller.aiRefreshModels(p.id) }.fold({ cat2 -> cat2.errors[p.id]?.let { "Failed: $it" } ?: "Fetched ${cat2.models.count { it.ref.provider == p.id }} models." }, { it.message })
                    busy = null
                }
            }, style = ButtonStyle.QUIET, enabled = busy == null && p.keys.isNotEmpty())
            Spacer(Modifier.width(10.dp))
            PillButton("Pick models", { nav.push(Route.AiPage(AiPages.MODELS)) }, style = ButtonStyle.QUIET)
        }
        fetched?.let { Gap(6); MutedText(it) }
        if (!p.builtIn) {
            Gap(28)
            PillButton("Remove ${p.name}", { confirmDelete = true }, style = ButtonStyle.PULSE)
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, containerColor = c.surface,
        title = { Text("Remove ${p.name}?", style = HL.type.section, color = c.ink) },
        text = { MutedText("Its ${p.keys.size} key${if (p.keys.size == 1) "" else "s"} are deleted from Heartline, and its models leave every feature that used them.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; controller.aiDeleteProvider(p.id); nav.pop() }) { Text("Remove", color = c.pulse) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep", color = c.inkSoft) } })
}

@Composable
private fun NewProviderPage(controller: HeartlineController, nav: Navigator) {
    var kind by remember { mutableStateOf(ProviderKind.OPENAI) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    Screen("Add a provider", onBack = { nav.pop() }, subtitle = "Another place to get models from. Its models can then be picked for any feature.") {
        Segmented(ProviderKind.entries.map { it to it.label }, kind, { kind = it }, Modifier.fillMaxWidth())
        Gap(12)
        OutlinedTextField(name, { name = it }, label = { Text("Name, such as Groq") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Gap(8)
        OutlinedTextField(url, { url = it.trim() }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(if (kind == ProviderKind.GEMINI) AiProvider.GEMINI_URL else "https://api.groq.com/openai/v1") })
        MutedText(if (kind == ProviderKind.GEMINI) "Leave it empty for Google's Gemini API." else "The address before /chat/completions and /models.", Modifier.padding(top = 4.dp))
        Gap(8)
        OutlinedTextField(key, { key = it.trim() }, label = { Text("Key (more can be added after)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Gap(14)
        PillButton("Add provider", {
            val id = controller.aiSaveProvider(null, kind, name, url.ifBlank { if (kind == ProviderKind.GEMINI) AiProvider.GEMINI_URL else "" })
            if (key.isNotBlank()) controller.aiAddKey(id, key)
            nav.replace(Route.AiPage(AiPages.provider(id)))
        }, enabled = name.isNotBlank() && (kind == ProviderKind.GEMINI || url.startsWith("http")))
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Picking models

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelsPage(controller: HeartlineController, nav: Navigator) {
    val c = HL.colors
    val setup by controller.aiSetup.collectAsStateWithLifecycle()
    val cat by controller.aiCatalog.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var pickedOnly by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf<String?>(null) }
    var free by remember { mutableStateOf(false) }
    var tools by remember { mutableStateOf(false) }
    var audio by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf(false) }
    // Picked models that aren't in any list (typed in before, or a list not fetched yet) still show, so they can be unpicked.
    val all = cat.models + setup.picked.filter { cat.of(it) == null }.map { CatalogModel(it, short(it)) }
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    val shown = all.filter { m ->
        (!pickedOnly || m.ref in setup.picked) && (provider == null || m.ref.provider == provider) && (!free || m.free == true) &&
            (!tools || m.tools == true) && (!audio || m.audio == true || m.live) &&
            words.all { w -> w in m.ref.model.lowercase() || w in m.name.lowercase() }
    }.sortedWith(compareBy({ it.ref !in setup.picked }, { it.ref.provider }, { it.name.lowercase() }))
    Screen("Models to choose from", onBack = { nav.pop() }, subtitle = "Pick the ones you want offered in each feature's model list. ${setup.picked.size} picked.") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton(if (fetching) "Fetching…" else "Fetch every list", { fetching = true; scope.launch { runCatching { controller.aiRefreshModels(null) }; fetching = false } },
                style = ButtonStyle.QUIET, enabled = !fetching)
            Spacer(Modifier.width(10.dp))
            MutedText(cat.fetchedAt.values.maxOrNull()?.let { "Fetched ${agoOf(it)}" } ?: "Not fetched yet")
        }
        cat.errors.forEach { (pid, e) -> Gap(4); Text("${setup.provider(pid)?.name ?: pid}: $e", style = HL.type.small, color = c.warn) }
        Gap(12)
        OutlinedTextField(query, { query = it }, label = { Text("Search, such as flash or nemotron") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Gap(10)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Picked", pickedOnly) { pickedOnly = !pickedOnly }
            setup.providers.forEach { p -> Chip(p.name, provider == p.id) { provider = if (provider == p.id) null else p.id } }
            Chip("Free", free) { free = !free }
            Chip("Tools", tools) { tools = !tools }
            Chip("Audio", audio) { audio = !audio }
        }
        Gap(12)
        if (all.isEmpty()) MutedText("Add a key to a provider, then fetch the lists.")
        else if (shown.isEmpty()) MutedText("No model matches.")
        else Card(padding = 6.dp) {
            shown.take(LIMIT).forEachIndexed { i, m ->
                val on = m.ref in setup.picked
                Row(Modifier.fillMaxWidth().clickable { controller.aiSetPicked(m.ref, !on) }.padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(on, { controller.aiSetPicked(m.ref, it) }, colors = CheckboxDefaults.colors(checkedColor = c.ink, checkmarkColor = c.surface, uncheckedColor = c.inkSoft))
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = HL.type.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${setup.provider(m.ref.provider)?.name ?: m.ref.provider}  ${m.ref.model}", style = mono, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        tags(m).takeIf { it.isNotEmpty() }?.let { Text(it, style = HL.type.small, color = c.inkSoft) }
                    }
                }
                if (i < minOf(shown.size, LIMIT) - 1) HorizontalDivider(color = c.line, modifier = Modifier.padding(start = 48.dp))
            }
        }
        if (shown.size > LIMIT) { Gap(8); MutedText("Showing $LIMIT of ${shown.size}. Search to find the rest.") }
    }
}

private const val LIMIT = 120

private fun tags(m: CatalogModel): String = listOfNotNull(
    "free".takeIf { m.free == true }, "tools".takeIf { m.tools == true }, "audio".takeIf { m.audio == true && !m.live }, "live audio".takeIf { m.live },
    m.context?.let { if (it >= 1_000_000) "${it / 1_000_000}M context" else "${it / 1000}k context" },
).joinToString(", ").replaceFirstChar { it.uppercase() }

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    val c = HL.colors
    Text(label, style = HL.type.label, color = if (on) c.surface else c.ink,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) c.ink else Color.Transparent).border(1.dp, if (on) c.ink else c.line, RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp))
}

// ---------------------------------------------------------------------------------------------------------------------
// Activity

@Composable
private fun ActivityPage(controller: HeartlineController, nav: Navigator) {
    val setup by controller.aiSetup.collectAsStateWithLifecycle()
    val cat by controller.aiCatalog.collectAsStateWithLifecycle()
    val activity by controller.aiActivity.collectAsStateWithLifecycle()
    Screen("Every request", onBack = { nav.pop() }, subtitle = "Each request to a model, newest first: which key, how long, and what went wrong if it did.") {
        if (activity.isEmpty()) MutedText("Nothing yet.") else NavGroup { activity.forEach { AttemptRow(it, setup, cat) } }
    }
}

@Composable
private fun AttemptRow(a: AiAttempt, setup: AiSetup, cat: AiCatalog) {
    val c = HL.colors
    val ref = ModelRef(a.provider, a.model)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(when { a.ok -> c.ok; a.kind?.keyBound == true -> c.warn; else -> c.pulse }))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text("${clock(a.at)}  ${AiSection.of(a.section)?.title ?: a.section}", style = HL.type.label, color = c.ink, modifier = Modifier.weight(1f))
                if (a.ms > 0) Text("%.1f s".format(a.ms / 1000.0), style = HL.type.small, color = c.inkSoft)
            }
            Text(display(ref, cat) + "  ${setup.provider(a.provider)?.name ?: a.provider}" + (if (a.keyIndex >= 0) ", key ${a.keyIndex + 1}" else "") +
                (if (a.level > 0) ", fallback ${a.level}" else ""), style = HL.type.small, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!a.ok) Text(listOfNotNull(a.kind?.label, a.error ?: a.note).joinToString(": "), style = HL.type.small, color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (a.waitedMs > 0) Text("Waited %.0f s before this try".format(a.waitedMs / 1000.0), style = HL.type.small, color = c.inkSoft)
        }
    }
    HorizontalDivider(color = c.line)
}

@Composable
private fun Code(text: String) {
    SelectionContainer {
        Text(text, style = mono.copy(color = HL.colors.ink, lineHeight = 17.sp), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(HL.colors.raised).padding(10.dp))
    }
}
