package dev.heartline.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heartline.app.HeartlineController
import dev.heartline.app.LinkState
import dev.heartline.ui.Navigator
import dev.heartline.ui.Route
import dev.heartline.ui.components.AreaLabel
import dev.heartline.ui.components.ButtonStyle
import dev.heartline.ui.components.Card
import dev.heartline.ui.components.DateStepper
import dev.heartline.ui.components.DayBars
import dev.heartline.ui.components.Delta
import dev.heartline.ui.components.Dot
import dev.heartline.ui.components.Heading
import dev.heartline.ui.components.HrTraceChart
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.Note
import dev.heartline.ui.components.PillButton
import dev.heartline.ui.components.Segmented
import dev.heartline.ui.components.fmtInt
import dev.heartline.ui.components.paper
import dev.heartline.ui.theme.HL
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Heart: the live reading on ECG paper, the picked day in full (resting, range, chart, zones, hour by hour), resting
 * heart rate and HRV over two weeks against your usual level, and the heart check with its history.
 */
@Composable
fun HeartScreen(controller: HeartlineController, nav: Navigator) {
    val c = HL.colors
    val conn by controller.connection.collectAsStateWithLifecycle()
    val hr by controller.liveHr.collectAsStateWithLifecycle()
    val checks by controller.checks.collectAsStateWithLifecycle()
    val trends by controller.trends.collectAsStateWithLifecycle()
    var window by rememberSaveable { mutableLongStateOf(3600L) }
    var length by rememberSaveable { mutableStateOf(120) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { delay(5000); now = System.currentTimeMillis() / 1000 } }
    val connected = conn.state == LinkState.CONNECTED
    val today = remember { LocalDate.now() }
    var epochDay by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    val date = LocalDate.ofEpochDay(epochDay)
    val day by remember(epochDay) { controller.healthDay(date) }.collectAsState(initial = null)

    ScrollPage {
        // ---- Live ----
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Gutter).padding(top = 14.dp, bottom = 18.dp)) {
            Text("Heart", style = HL.type.title, color = c.ink)
            Gap(6)
            val todayDay by remember { controller.healthDay(LocalDate.now()) }.collectAsState(initial = null)
            val synced = todayDay?.hr?.lastOrNull()
            val shown = hr.bpm ?: synced?.v?.roundToInt()
            Row(Modifier.semantics(mergeDescendants = true) { contentDescription = shown?.let { "Heart rate $it beats per minute" + if (hr.streaming) ", live" else "" } ?: "No heart rate reading" },
                verticalAlignment = Alignment.Bottom) {
                Text(shown?.toString() ?: "–", style = HL.type.hero.copy(fontSize = 96.sp, lineHeight = 90.sp), color = if (hr.streaming) c.ink else c.inkSoft)
                Text(" bpm", style = HL.type.section, color = c.pulse, modifier = Modifier.padding(bottom = 12.dp))
            }
            MutedText(when {
                !connected -> "Connect to the watch to see your heart rate."
                hr.streaming && hr.bpm == null -> "Measuring. The watch takes a minute or two to settle on the first reading."
                hr.streaming -> "Live, updated ${hr.ts?.let { agoOf(it) } ?: ""}."
                hr.ts != null -> "Last reading ${agoOf(hr.ts!!)}. Start live heart rate to measure now."
                synced != null -> "Last synced reading, at ${timeOf(synced.t.toLong())}. Start live heart rate to measure now."
                else -> "Start live heart rate to measure now."
            })
            Gap(12)
            PillButton(if (hr.streaming) "Stop live heart rate" else "Start live heart rate", { controller.setLiveHr(!hr.streaming) }, style = ButtonStyle.PULSE, pressed = hr.streaming, enabled = connected)
            // Live readings joined with what the watch synced, so the trace is never empty while there is data.
            val yDay by remember { controller.healthDay(LocalDate.now().minusDays(1)) }.collectAsState(initial = null)
            val trace = remember(hr.recent, todayDay, yDay) {
                val synced = (yDay?.hr.orEmpty() + todayDay?.hr.orEmpty()).map { dev.heartline.app.HrPoint(it.t.toLong(), it.v.roundToInt()) }
                // Minutes that have live readings keep those (finer); the rest come from the synced record.
                val liveMinutes = hr.recent.mapTo(HashSet()) { it.ts / 60 }
                (synced.filter { it.ts / 60 !in liveMinutes } + hr.recent).sortedBy { it.ts }
            }
            if (trace.isNotEmpty()) {
                Gap(14)
                Segmented(listOf(600L to "10 min", 3600L to "1 hour", 86400L to "24 hours"), window, { window = it })
                Gap(6)
                HrTraceChart(trace, window, now)
            }
        }

        Column(Modifier.padding(horizontal = Gutter)) {
            // ---- The day ----
            Heading("Through the day")
            DateStepper(if (date == today) "Today" else if (date == today.minusDays(1)) "Yesterday" else date.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)),
                date.format(DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH)), onPrev = { epochDay-- }, onNext = { epochDay++ }, nextEnabled = date < today)
            Gap(14)
            day?.let { HeartDay(it, nav) } ?: MutedText("Loading…")
            // ---- Two weeks ----
            val rhr = trends.seriesOf("rhr")
            val hrv = trends.seriesOf("hrv")
            if (rhr != null) {
                Heading("Resting heart rate, 2 weeks", action = "Trend", onAction = { nav.push(Route.Trend("rhr")) })
                Card(padding = 14.dp) {
                    val latest = rhr.points.lastOrNull { it.value != null }?.value
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        dev.heartline.ui.components.BigValue(latest?.roundToInt()?.toString() ?: "–", "bpm", size = 30, modifier = Modifier.weight(1f))
                        if (latest != null && rhr.baseline != null) {
                            val diff = (latest - rhr.baseline!!).roundToInt()
                            Delta(if (diff == 0) "at your usual" else "${if (diff > 0) "▲" else "▼"} ${abs(diff)} vs usual ${rhr.baseline!!.roundToInt()}", if (diff == 0) null else diff < 0)
                        }
                    }
                    Gap(6)
                    DayBars(rhr.points, c.pulse, line = true, height = 120.dp, reference = rhr.baseline, referenceLabel = rhr.baseline?.let { "your usual, ${it.roundToInt()} bpm" }, unit = "bpm")
                    Gap(6)
                    MutedText("Lower than usual often means you're well recovered; a rise of 5 or more for a few days can come with illness, stress, alcohol or hard training.")
                }
            }
            if (hrv != null && hrv.points.any { it.value != null }) {
                Gap(10)
                Card(padding = 14.dp) {
                    AreaLabel("Heart rate variability (from heart checks)", c.ok, dot = false)
                    Gap(4)
                    DayBars(hrv.points, c.ok, line = true, height = 110.dp, reference = hrv.baseline, referenceLabel = hrv.baseline?.let { "your usual, ${it.roundToInt()} ms" }, unit = "ms")
                    Gap(6)
                    MutedText("Higher than your usual is a good sign of recovery. Compare with yourself, not others: HRV varies widely between people.")
                }
            }


            // ---- Heart check ----
            Heading("Heart check")
            Card {
                Text("Sit still with the watch snug. Heartline records your raw pulse, finds every beat and measures heart rate variability, rhythm regularity and breathing.",
                    style = HL.type.body, color = c.inkSoft)
                Gap(12)
                Segmented(listOf(60 to "1 min", 120 to "2 min", 300 to "5 min"), length, { length = it })
                Gap(12)
                PillButton("Start heart check", { controller.startCheck(length); nav.push(Route.CheckRun) }, Modifier.fillMaxWidth(), enabled = connected)
                if (!connected) { Gap(6); MutedText("Connect to the watch first.") }
            }
            Gap(8)
            if (checks.isEmpty()) Note("No checks yet. A 2-minute check each morning, before coffee, builds your HRV baseline and feeds readiness.")
            checks.forEach { ch ->
                Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Open heart check", role = Role.Button) { nav.push(Route.CheckResult(ch.id)) }
                    .semantics(mergeDescendants = true) {}.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dot(rhythmColor(ch.rhythm), 10.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(dateTimeOf(ch.start), style = HL.type.bodyStrong, color = c.ink)
                        MutedText(rhythmText(ch.rhythm))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${fmtInt(ch.rmssd?.roundToInt())} ms", style = HL.type.stat.copy(fontSize = 22.sp), color = c.ink)
                        MutedText("HRV · ${fmtInt(ch.hr?.roundToInt())} bpm")
                    }
                }
                HorizontalDivider(color = c.line)
            }
            Gap(28)
        }
    }
}
