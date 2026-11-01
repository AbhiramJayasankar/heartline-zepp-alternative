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

// ---------------- Today ----------------

@Composable
fun TodayScreen(controller: HeartlineController, onOpenHeart: () -> Unit, onOpenActivity: () -> Unit) {
    val c = HL.colors
    val conn by controller.connection.collectAsStateWithLifecycle()
    val battery by controller.battery.collectAsStateWithLifecycle()
    val today by controller.today.collectAsStateWithLifecycle()
    val hr by controller.liveHr.collectAsStateWithLifecycle()
    val steps by controller.steps.collectAsStateWithLifecycle()
    val sync by controller.sync.collectAsStateWithLifecycle()
    val t = today

    ScrollPage {
        // Readiness hero on the wash band
        Column(Modifier.fillMaxWidth().background(c.wash).statusBarsPadding().padding(horizontal = Gutter).padding(top = 14.dp, bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ConnectionChip(conn, Modifier.weight(1f))
                battery?.let { BatteryGlyph(it.level, it.charging) }
            }
            Gap(18)
            Text(t?.readinessLabel ?: "Readiness", style = HL.type.label, color = c.inkSoft)
            val ready = t?.readiness
            Text(
                ready?.toString() ?: "–",
                style = HL.type.hero,
                color = when {
                    ready == null -> c.inkSoft
                    ready >= 70 -> c.ok
                    ready < 45 -> c.pulse
                    else -> c.ink
                },
            )
            Text("of 100", style = HL.type.small, color = c.inkSoft)
            Gap(12)
            Text(t?.recommendation ?: "Wear the watch tonight and take a heart check in the morning; readiness learns your baseline from both.", style = HL.type.body.copy(fontSize = 17.sp, lineHeight = 25.sp), color = c.ink)
            Gap(20)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Strain", fmtOne(t?.strain), unit = "/21")
                Stat("Sleep", fmtInt(t?.sleepScore), unit = "/100")
                Stat("Battery", fmtInt(t?.bodyBattery), unit = "/100")
                Stat("Resting", fmtInt(t?.restingHr), unit = "bpm")
            }
        }

        Column(Modifier.padding(horizontal = Gutter)) {
            Gap(22)
            // Live heart rate strip, a window onto the Heart tab
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).paper().clickable(onClick = onOpenHeart).padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Heart rate", style = HL.type.label, color = c.inkSoft)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(hr.bpm?.toString() ?: "–", style = HL.type.score.copy(fontSize = 56.sp, lineHeight = 52.sp), color = c.ink)
                            Text(" bpm", style = HL.type.section.copy(fontSize = 18.sp), color = c.pulse, modifier = Modifier.padding(bottom = 6.dp))
                        }
                        MutedText(if (hr.streaming) "Live" else hr.ts?.let { "Last reading ${agoOf(it)}" } ?: "No reading yet")
                    }
                    Text("Open", style = HL.type.label, color = c.pulse)
                }
            }
            Gap(22)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Steps today", fmtInt(steps?.steps ?: t?.stepsToday))
                Stat("Active", fmtHm(t?.activeMinutes))
                Stat("Distance", steps?.distanceM?.let { "%.1f".format(it / 1000.0) } ?: "–", unit = "km")
            }

            if (t != null && t.factors.isNotEmpty()) {
                Gap(28)
                SectionTitle("What shaped today")
                Gap(10)
                t.factors.forEach { f ->
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                        Dot(when (f.impact) { Impact.POSITIVE -> c.ok; Impact.NEGATIVE -> c.pulse; Impact.NEUTRAL -> c.gridMajor }, 10.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = HL.type.bodyStrong.fontWeight)) { append(f.name) }
                            append(" ${f.detail}")
                        }, style = HL.type.body, color = c.ink)
                    }
                }
            }

            t?.alerts?.takeIf { it.isNotEmpty() }?.let { alerts ->
                Gap(22)
                SectionTitle("Worth a look")
                Gap(10)
                alerts.forEach { a ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(c.surface)) {
                        Box(Modifier.width(3.dp).height(64.dp).background(if (a.warn) c.pulse else c.inkSoft))
                        Column(Modifier.padding(12.dp)) {
                            Text(a.title, style = HL.type.bodyStrong, color = c.ink)
                            Text(a.detail, style = HL.type.small, color = c.inkSoft)
                        }
                    }
                }
            }

            t?.lastNight?.let { n ->
                Gap(22)
                Panel(Modifier.clickable(onClick = onOpenActivity)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Last night", style = HL.type.section, color = c.ink, modifier = Modifier.weight(1f))
                        Text(fmtHm(n.deepMin + n.lightMin + n.remMin) + " asleep", style = HL.type.label, color = c.inkSoft)
                    }
                    Gap(12)
                    StagesBar(n)
                    Gap(10)
                    MutedText("Deep ${n.deepMin} min, light ${n.lightMin} min, REM ${n.remMin} min, awake ${n.awakeMin} min")
                }
            }

            Gap(22)
            Row(verticalAlignment = Alignment.CenterVertically) {
                MutedText(
                    if (sync.running) sync.message ?: "Syncing…"
                    else sync.lastActivity?.let { "Synced ${agoOf(it)}" } ?: "Not synced yet",
                    Modifier.weight(1f),
                )
                PillButton(if (sync.running) "Syncing…" else "Sync now", { controller.syncNow() }, style = ButtonStyle.QUIET, enabled = !sync.running && conn.state == LinkState.CONNECTED)
            }
            Gap(28)
        }
    }
}

// ---------------- Heart ----------------

@Composable
fun HeartScreen(controller: HeartlineController, nav: Navigator) {
    val c = HL.colors
    val conn by controller.connection.collectAsStateWithLifecycle()
    val hr by controller.liveHr.collectAsStateWithLifecycle()
    val checks by controller.checks.collectAsStateWithLifecycle()
    var window by rememberSaveable { mutableLongStateOf(600L) }
    var length by rememberSaveable { mutableStateOf(120) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { delay(5000); now = System.currentTimeMillis() / 1000 } }
    val connected = conn.state == LinkState.CONNECTED

    ScrollPage {
        Column(Modifier.fillMaxWidth().paper().statusBarsPadding().padding(horizontal = Gutter).padding(top = 14.dp, bottom = 22.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(hr.bpm?.toString() ?: "–", style = HL.type.hero, color = if (hr.streaming) c.ink else c.inkSoft)
                Text(" bpm", style = HL.type.section, color = c.pulse, modifier = Modifier.padding(bottom = 16.dp))
            }
            MutedText(
                when {
                    !connected -> "Connect to the watch to see your heart rate."
                    hr.streaming && hr.bpm == null -> "Measuring. The watch takes a minute or two to settle on the first reading."
                    hr.streaming -> "Live, updated ${hr.ts?.let { agoOf(it) } ?: ""}."
                    else -> "Start live heart rate to measure now."
                },
            )
            Gap(14)
            PillButton(if (hr.streaming) "Stop live heart rate" else "Start live heart rate", { controller.setLiveHr(!hr.streaming) }, style = ButtonStyle.PULSE, pressed = hr.streaming, enabled = connected)
            Gap(18)
            Segmented(listOf(600L to "10 min", 3600L to "1 hour", 86400L to "24 hours"), window, { window = it })
            Gap(8)
            HrTraceChart(hr.recent, window, now)
        }

        Column(Modifier.padding(horizontal = Gutter)) {
            Gap(24)
            Text("Heart check", style = HL.type.title, color = c.ink)
            Gap(6)
            Text("Sit still with the watch snug. Heartline records your raw pulse, finds every beat and measures heart rate variability, rhythm regularity and breathing.", style = HL.type.body, color = c.inkSoft)
            Gap(14)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Segmented(listOf(60 to "1 min", 120 to "2 min", 300 to "5 min"), length, { length = it })
                Spacer(Modifier.weight(1f))
            }
            Gap(12)
            PillButton("Start heart check", { controller.startCheck(length); nav.push(Route.CheckRun) }, Modifier.fillMaxWidth(), enabled = connected)
            Gap(26)
            SectionTitle("Past checks")
            Gap(4)
            if (checks.isEmpty()) MutedText("No checks yet. A 2-minute check each morning builds your HRV baseline.")
            checks.forEach { ch ->
                Row(Modifier.fillMaxWidth().clickable { nav.push(Route.CheckResult(ch.id)) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dot(rhythmColor(ch.rhythm), 10.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(dateTimeOf(ch.start), style = HL.type.bodyStrong, color = c.ink)
                        MutedText(rhythmText(ch.rhythm))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${fmtInt(ch.rmssd?.roundToInt())} ms", style = HL.type.stat.copy(fontSize = 22.sp), color = c.ink)
                        MutedText("${fmtInt(ch.hr?.roundToInt())} bpm")
                    }
                }
                HorizontalDivider(color = c.line)
            }
            Gap(28)
        }
    }
}

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
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.wash)) {
            Box(Modifier.width(4.dp).height(120.dp).background(rhythmColor(s.rhythm)))
            Column(Modifier.padding(16.dp)) {
                Text("Rhythm", style = HL.type.label, color = c.inkSoft)
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
