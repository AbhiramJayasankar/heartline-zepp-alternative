package dev.heartline.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heartline.app.HeartlineController
import dev.heartline.app.Impact
import dev.heartline.app.LinkState
import dev.heartline.app.RhythmStatus
import dev.heartline.app.SessionUi
import dev.heartline.ui.Navigator
import dev.heartline.ui.Route
import dev.heartline.ui.components.BatteryGlyph
import dev.heartline.ui.components.BeatsPlot
import dev.heartline.ui.components.ButtonStyle
import dev.heartline.ui.components.ConnectionChip
import dev.heartline.ui.components.Dot
import dev.heartline.ui.components.HrTraceChart
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.Panel
import dev.heartline.ui.components.PillButton
import dev.heartline.ui.components.PoincarePlot
import dev.heartline.ui.components.SectionTitle
import dev.heartline.ui.components.Segmented
import dev.heartline.ui.components.SignalCanvas
import dev.heartline.ui.components.StagesBar
import dev.heartline.ui.components.Stat
import dev.heartline.ui.components.Tachogram
import dev.heartline.ui.components.detrendPulse
import dev.heartline.ui.components.fmtHm
import dev.heartline.ui.components.fmtInt
import dev.heartline.ui.components.fmtOne
import dev.heartline.ui.components.paper
import dev.heartline.ui.theme.HL
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun rhythmColor(r: RhythmStatus): Color = when (r) {
    RhythmStatus.REGULAR -> HL.colors.ok
    RhythmStatus.IRREGULAR -> HL.colors.warn
    RhythmStatus.POSSIBLE_AF -> HL.colors.pulse
    RhythmStatus.INSUFFICIENT -> HL.colors.gridMajor
}

fun rhythmText(r: RhythmStatus) = when (r) {
    RhythmStatus.REGULAR -> "Regular rhythm"
    RhythmStatus.IRREGULAR -> "Irregular rhythm"
    RhythmStatus.POSSIBLE_AF -> "Signs of possible AFib"
    RhythmStatus.INSUFFICIENT -> "Not enough clean signal"
}

const val DISCLAIMER = "A wellness estimate from your wrist pulse, not a medical diagnosis. If you have symptoms, see a doctor."

@Composable
fun CheckRunScreen(controller: HeartlineController, nav: Navigator) {
    val c = HL.colors
    val run by controller.checkRun.collectAsStateWithLifecycle()
    val sensors by controller.sensors.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(250); now = System.currentTimeMillis() } }
    val r = run
    LaunchedEffect(r?.result) { r?.result?.let { nav.replace(Route.CheckResult(it.id)) } }
    // Milliseconds as Long: a Float of epoch seconds only keeps ~7 digits and would round away the countdown.
    val elapsed = if (r == null) 0f else ((now - r.startedAt * 1000) / 1000f).coerceAtLeast(0f)
    val left = if (r == null) 0 else (r.seconds - elapsed).toInt().coerceAtLeast(0)
    val progress by animateFloatAsState(if (r == null) 0f else (elapsed / r.seconds).coerceIn(0f, 1f), tween(250), label = "progress")

    Column(Modifier.fillMaxSize().paper().statusBarsPadding().padding(Gutter)) {
        Text("Heart check", style = HL.type.title, color = c.ink)
        Gap(6)
        MutedText(if (r?.analysing == true) "Finding every beat…" else "Rest your arm, breathe normally and keep still.")
        Gap(30)
        Text(if (r?.analysing == true) "…" else "$left", style = HL.type.hero, color = c.ink)
        Text(if (r?.analysing == true) "analysing" else "seconds left", style = HL.type.label, color = c.inkSoft)
        Gap(20)
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.gridMinor)) {
            Box(Modifier.fillMaxWidth(progress).height(6.dp).background(c.pulse))
        }
        Gap(24)
        Panel(padding = androidx.compose.foundation.layout.PaddingValues(10.dp)) {
            Text("Your pulse, live", style = HL.type.label, color = c.inkSoft)
            val sig = remember(sensors.ppg) { detrendPulse(sensors.ppg).let { it.copyOfRange(maxOf(0, it.size - 200), it.size) } }
            SignalCanvas(listOf(sig), listOf(c.ink), Modifier.fillMaxWidth().height(170.dp))
        }
        r?.error?.let { Gap(12); Text(it, style = HL.type.body, color = c.pulse) }
        Spacer(Modifier.weight(1f))
        PillButton("Cancel", { controller.cancelCheck(); nav.pop() }, Modifier.fillMaxWidth(), style = ButtonStyle.QUIET)
    }
}

@Composable
fun CheckResultScreen(controller: HeartlineController, id: String, nav: Navigator) {
    val c = HL.colors
    val session by produceState<SessionUi?>(null, id) {
        value = controller.checkRun.value?.result?.takeIf { it.id == id } ?: controller.checkDetail(id)
    }
    Screen("Heart check", onBack = { nav.pop() }, subtitle = session?.let { "${dateTimeOf(it.start)}, ${it.durationS.toInt()} s" }) {
        val s = session ?: run { MutedText("Loading…"); return@Screen }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.wash)) {
            Column(Modifier.padding(16.dp)) {
                dev.heartline.ui.components.AreaLabel("Rhythm", rhythmColor(s.rhythm))
                Text(rhythmText(s.rhythm), style = HL.type.section.copy(fontSize = 28.sp), color = if (s.rhythm == RhythmStatus.POSSIBLE_AF) c.pulse else c.ink)
                Gap(4)
                MutedText((s.notes.filterNot { it.contains("diagnosis") } + DISCLAIMER).joinToString(" "))
            }
        }
        Gap(20)
        val grid = listOf(
            Triple("Heart rate", fmtInt(s.hr?.roundToInt()), "bpm"), Triple("HRV (RMSSD)", fmtInt(s.rmssd?.roundToInt()), "ms"),
            Triple("SDNN", fmtInt(s.sdnn?.roundToInt()), "ms"), Triple("pNN50", fmtInt(s.pnn50?.let { (if (it <= 1) it * 100 else it).roundToInt() }), "%"),
            Triple("Stress index", fmtInt(s.stressIndex?.roundToInt()), null), Triple("Breathing", fmtOne(s.respiratoryRate), "/min"),
            Triple("LF/HF", s.lfHf?.let { "%.2f".format(it) } ?: "–", null), Triple("Extra beats", s.ectopic.toString(), null),
            Triple("Signal", fmtInt((s.signalQuality * 100).roundToInt()), "%"),
        )
        grid.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) { row.forEach { (l, v, u) -> Stat(l, v, Modifier.weight(1f), unit = u) } }
        }
        Gap(20)
        Panel {
            Text("Beat-to-beat intervals", style = HL.type.label, color = c.inkSoft)
            Gap(6)
            Tachogram(s.rr)
        }
        Gap(14)
        Panel {
            Text("Poincaré plot", style = HL.type.label, color = c.inkSoft)
            Gap(6)
            PoincarePlot(s.rr)
            Gap(6)
            MutedText("Each dot pairs one beat interval with the next. A tight cigar along the diagonal is a steady rhythm; a scattered cloud means the rhythm is irregular.")
        }
        Gap(14)
        Panel {
            Text("Detected beats", style = HL.type.label, color = c.inkSoft)
            Gap(6)
            BeatsPlot(s.ppgPreview, s.previewFs, s.beatTimes)
        }
        Gap(14)
        MutedText("Typical for a short resting reading: RMSSD 19 to 75 ms and SDNN 32 to 93 ms (Nunan et al. 2010). Your own baseline matters more than these ranges.")
    }
}

/** Vertical scroll for the tab screens that draw their own full-bleed header instead of using [Screen]. */
@Composable
fun Modifier.scrollY(): Modifier = verticalScroll(rememberScrollState())
