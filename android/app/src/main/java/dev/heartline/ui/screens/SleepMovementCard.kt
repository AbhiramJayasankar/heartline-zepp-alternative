package dev.heartline.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heartline.analytics.MOVE_INTENSITY
import dev.heartline.analytics.SleepMovement
import dev.heartline.analytics.StillStretch
import dev.heartline.app.HeartlineController
import dev.heartline.app.SeriesPoint
import dev.heartline.app.SleepNightUi
import dev.heartline.ui.components.AreaLabel
import dev.heartline.ui.components.Card
import dev.heartline.ui.components.FactGrid
import dev.heartline.ui.components.FactItem
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.hm
import dev.heartline.ui.components.label
import dev.heartline.ui.theme.Area
import dev.heartline.ui.theme.HL
import dev.heartline.ui.theme.hue
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt

private val clock = DateTimeFormatter.ofPattern("HH:mm")
private fun hhmm(ts: Long) = Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()).format(clock)

/** The middle value of [v], or null when empty. */
private fun mid(v: List<Double>): Double? = v.sorted().takeIf { it.isNotEmpty() }?.let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

/**
 * Movement through the night: still stretches between movements, numbered by which way the watch faced (from the
 * overnight HRV recordings), the watch's movement minute by minute, and blood oxygen in each position.
 */
@Composable
internal fun SleepMovementCard(controller: HeartlineController, n: SleepNightUi, spo2: List<SeriesPoint>, spo2Aside: List<SeriesPoint>, refresh: Int) {
    val c = HL.colors
    val loaded by produceState<SleepMovement?>(null, n.start, n.end, refresh) {
        value = runCatching { controller.sleepMovement(n.start, n.end) }.getOrNull()
    }
    val ov by controller.overnight.collectAsStateWithLifecycle()
    val m = loaded ?: return
    if (m.intensity.isEmpty()) return
    val palette = listOf(c.oxygen, c.ok, c.warn, c.sleepRem, c.pulse, c.sleepDeep)
    fun color(p: Int?): Color = p?.let { palette[(it - 1) % palette.size] } ?: c.line
    fun inStretch(t: Double, s: StillStretch) = t >= s.start && t < s.end

    Gap(10)
    Card(padding = 14.dp) {
        AreaLabel("Movement and positions", c.hue(Area.SLEEP), dot = false)
        Gap(6)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Accurate positions", style = HL.type.bodyStrong, color = c.ink)
                Text(if (ov.positions) "On: the motion sensor is read for 20 s every 5 minutes while you sleep, so nearly every stretch gets a position."
                    else "Off: positions come from the overnight HRV recordings only.", style = HL.type.small, color = c.inkSoft)
            }
            Spacer(Modifier.width(10.dp))
            Switch(ov.positions, { controller.setAccuratePositions(it) },
                colors = SwitchDefaults.colors(checkedTrackColor = c.ok, checkedThumbColor = c.surface, uncheckedTrackColor = c.gridMajor, uncheckedThumbColor = c.surface, uncheckedBorderColor = c.gridMajor))
        }
        Gap(10)
        FactGrid(listOf(
            FactItem("Moves", "${m.moves.size}", null),
            FactItem("Position changes", if (m.measured) "${m.turns}" else "–", null),
            FactItem("Longest still", hm(m.longestStillMin), null),
            FactItem("Restless", "${m.restlessMin}", "min"),
            FactItem("Positions", if (m.measured) "${m.positions.size}" else "–", null),
            FactItem("Still stretches", "${m.stretches.size}", null),
        ))
        Gap(10)
        PositionChart(m, ::color, spo2)
        if (m.measured) {
            Gap(12)
            val unmeasured = m.stretches.filter { it.position == null }
            m.positions.sortedByDescending { it.minutes }.forEach { p ->
                val mine = m.stretches.filter { it.position == p.n }
                val kept = spo2.filter { pt -> mine.any { inStretch(pt.t, it) } }.map { it.v }
                val aside = spo2Aside.count { pt -> mine.any { inStretch(pt.t, it) } }
                PositionRow(p.n, color(p.n), "Position ${p.n}", p.words,
                    "${hm(p.minutes)} in ${p.times} stretch${if (p.times == 1) "" else "es"}" +
                        (mid(kept)?.let { " · SpO2 typically ${it.roundToInt()}%" } ?: "") +
                        (if (aside > 0) " · $aside low reading${if (aside == 1) "" else "s"} set aside (watch pressed on)" else ""))
            }
            if (unmeasured.isNotEmpty()) PositionRow(null, c.line, "Not measured", "No overnight HRV recording in these stretches",
                "${hm(unmeasured.sumOf { it.minutes })} in ${unmeasured.size} stretch${if (unmeasured.size == 1) "" else "es"}")
        }
        Gap(8)
        MutedText("A still stretch is the time between two movements the watch counted, 5 minutes or longer; small twitches don't " +
            "break one. " + if (m.measured) "Its number comes from which way the watch faced in the overnight HRV recordings taken " +
            "during it, so the same number again means you went back to that position. The watch measures your wrist, which usually " +
            "follows how you lie, but not always."
        else "Turn on Accurate positions above, or overnight HRV on this page, to number the positions: their recordings show which way the watch faced. " +
            "Accurate positions uses a little watch battery, and the phone needs to stay connected to the watch overnight.")
    }
}

@Composable
private fun PositionRow(n: Int?, color: Color, title: String, words: String, detail: String) {
    val c = HL.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            if (n != null) Text("$n", style = HL.type.label.copy(fontWeight = FontWeight.Bold), color = Color.White)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("$title · $words", style = HL.type.bodyStrong.copy(fontSize = 14.sp), color = c.ink)
            Text(detail, style = HL.type.small.copy(fontSize = 12.sp), color = c.inkSoft)
        }
    }
}

/** Two lanes on the night's time axis: still stretches coloured by position (tap one for its details), and movement per minute. */
@Composable
private fun PositionChart(m: SleepMovement, color: (Int?) -> Color, spo2: List<SeriesPoint>) {
    val c = HL.colors
    val tm = rememberTextMeasurer()
    var sel by remember(m.start, m.end) { mutableStateOf<StillStretch?>(null) }
    var widthPx by remember { mutableStateOf(1f) }
    val padLdp = 48.dp
    Box(Modifier.fillMaxWidth().heightIn(min = 40.dp)) {
        val s = sel
        if (s == null) MutedText("Tap a stretch for its times, position and blood oxygen.")
        else Column {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${hhmm(s.start)} – ${hhmm(s.end)} · ${hm(s.minutes)}", style = HL.type.label, color = c.inkSoft)
                mid(spo2.filter { it.t >= s.start && it.t < s.end }.map { it.v })?.let { Text("SpO2 ${it.roundToInt()}%", style = HL.type.label, color = c.oxygen) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(color(s.position)))
                    Spacer(Modifier.width(5.dp))
                    Text(s.position?.let { "Position $it" } ?: "Not measured", style = HL.type.bodyStrong, color = c.ink)
                }
                s.z?.let { Text(dev.heartline.analytics.poseWords(it), style = HL.type.body, color = c.ink, maxLines = 1) }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    Canvas(Modifier.fillMaxWidth().height(104.dp)
        .semantics { contentDescription = "Still stretches and movement from ${hhmm(m.start)} to ${hhmm(m.end)}: ${m.moves.size} moves, ${m.stretches.size} still stretches" }
        .pointerInput(m) {
            detectTapGestures { o ->
                val padL = padLdp.toPx()
                val t = m.start + (((o.x - padL) / (widthPx - padL)).coerceIn(0f, 1f) * (m.end - m.start)).toLong()
                val hit = m.stretches.firstOrNull { t >= it.start && t < it.end }
                sel = if (hit == sel) null else hit
            }
        }) {
        widthPx = size.width
        val padL = padLdp.toPx(); val padB = 20.dp.toPx()
        val w = size.width - padL
        val laneH = (size.height - padB) / 2
        fun x(ts: Long) = padL + ((ts - m.start).toFloat() / max(1L, m.end - m.start)) * w
        label(tm, "Position", 0f, laneH / 2, c.inkSoft)
        label(tm, "Moves", 0f, laneH * 1.5f, c.inkSoft)
        // Still stretches as blocks, the position number inside when it fits.
        val numberStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
        m.stretches.forEach { s ->
            val x0 = x(s.start) + 1f; val bw = max(2f, x(s.end) - x(s.start) - 2f)
            // A tapped stretch lights up every stretch in the same position.
            val dim = sel?.let { t -> if (t.position == null) t != s else s.position != t.position } ?: false
            drawRoundRect(color(s.position).copy(alpha = if (dim) 0.35f else 1f), Offset(x0, laneH * 0.14f), Size(bw, laneH * 0.72f), CornerRadius(4.dp.toPx()))
            if (s.position != null && bw >= 14.dp.toPx()) {
                val r = tm.measure("${s.position}", numberStyle)
                drawText(r, topLeft = Offset(x0 + bw / 2 - r.size.width / 2f, laneH / 2 - r.size.height / 2f))
            }
        }
        // Movement per minute: twitches faint, movements that break a stretch solid.
        val mw = max(1.5f, w / max(1f, (m.end - m.start) / 60f))
        val base = laneH * 2 - 2.dp.toPx()
        drawRect(c.line.copy(alpha = 0.6f), Offset(padL, base), Size(w, 1f))
        m.intensity.forEach { (ts, i) -> if (i > 0) {
            val h = (i.coerceAtMost(60) / 60f) * laneH * 0.8f
            drawRect(if (i >= MOVE_INTENSITY) c.ink.copy(alpha = 0.75f) else c.inkSoft.copy(alpha = 0.35f), Offset(x(ts), base - h), Size(mw, h))
        } }
        val hours = (m.start / 3600 + 1..m.end / 3600).map { it * 3600 }
        val step = max(1, hours.size / 6)
        hours.forEachIndexed { i, h -> if (i % step == 0) {
            drawLine(c.line.copy(alpha = 0.5f), Offset(x(h), size.height - padB), Offset(x(h), size.height - padB + 3.dp.toPx()), 1f)
            label(tm, hhmm(h), x(h), size.height - padB / 2 + 2.dp.toPx(), c.inkSoft, center = true)
        } }
    }
}
