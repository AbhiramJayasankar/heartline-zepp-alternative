package dev.heartline.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.heartline.app.MinuteState
import dev.heartline.app.MinuteUi
import dev.heartline.app.SeriesPoint
import dev.heartline.ui.theme.HL
import dev.heartline.ui.theme.Palette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The stages chart's rows, top to bottom: awake, REM, light, deep. */
val STAGE_NAMES = listOf("Awake", "REM", "Light", "Deep")

/** A minute's row on the stages chart, or null for a minute that isn't part of the night (not worn, charging). */
fun stageLane(s: MinuteState): Int? = when (s) {
    MinuteState.ACTIVE, MinuteState.IDLE -> 0; MinuteState.SLEEP_REM -> 1; MinuteState.SLEEP_LIGHT -> 2; MinuteState.SLEEP_DEEP -> 3; else -> null
}

/** A stage's colour by row ([stageLane]). */
fun Palette.stageColor(lane: Int): Color = when (lane) { 0 -> stageAwake; 1 -> stageRem; 2 -> stageLight; else -> stageDeep }

/** One unbroken stretch of a stage, epoch seconds, [end] exclusive. */
data class StageStretch(val lane: Int, val start: Long, val end: Long) {
    val minutes: Int get() = ((end - start + 30) / 60).toInt()
}

/**
 * The night's minutes as stretches of one stage each. A missing minute or two inside a stretch is bridged; a longer
 * gap ends it, so the chart doesn't join across time the watch didn't record.
 */
fun stageStretches(minutes: List<MinuteUi>, start: Long, end: Long): List<StageStretch> {
    val out = ArrayList<StageStretch>()
    for (m in minutes.filter { it.ts in start until end }.sortedBy { it.ts }) {
        val lane = stageLane(m.state) ?: continue
        val last = out.lastOrNull()
        if (last != null && last.lane == lane && m.ts - last.end <= 120) out[out.lastIndex] = last.copy(end = min(end, m.ts + 60))
        else out += StageStretch(lane, m.ts, min(end, m.ts + 60))
    }
    return out
}

/** Stretches shorter than this (minutes) between two of the same stage are folded into them when smoothed. */
const val STAGE_FOLD_MIN = 4

/**
 * The stretches as a watch shows a night: a sleep stretch under [STAGE_FOLD_MIN] minutes with the same stage on both
 * sides is folded into them, shortest first, so a minute of light inside REM doesn't break it into a comb. Awake
 * stretches are never folded: a waking, however short, stays on the chart.
 */
fun smoothStretches(stretches: List<StageStretch>): List<StageStretch> {
    val s = stretches.toMutableList()
    while (true) {
        val i = (1 until s.size - 1).filter { k ->
            val m = s[k]; val a = s[k - 1]; val b = s[k + 1]
            m.lane != 0 && m.minutes < STAGE_FOLD_MIN && a.lane == b.lane && m.start - a.end <= 120 && b.start - m.end <= 120
        }.minByOrNull { s[it].minutes } ?: break
        s[i - 1] = s[i - 1].copy(end = s[i + 1].end)
        s.removeAt(i + 1); s.removeAt(i)
    }
    return s
}

private val hhmm = DateTimeFormatter.ofPattern("HH:mm")
private fun clockAt(ts: Long) = Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()).format(hhmm)
private fun span(min: Int) = if (min >= 60) "${min / 60}h ${"%02d".format(min % 60)}m" else "${min}m"

/** A colour a little lighter, for the top of a block, so it reads as lit from above. */
private fun Color.lit(dark: Boolean) = lerp(this, Color.White, if (dark) 0.16f else 0.12f)

/**
 * Draws the stretches in rows [top]..[top] + 4 * [rowH] between [x0] and [x1]: thin connectors from each stretch to the
 * next with a gradient between their colours, then the stretches as rounded blocks over them. Stretches other than
 * [focus] fade when one is picked.
 */
private fun DrawScope.stages(stretches: List<StageStretch>, start: Long, end: Long, x0: Float, x1: Float, top: Float, rowH: Float,
                             c: Palette, blockFrac: Float, connector: Dp, focus: StageStretch?) {
    fun x(ts: Long) = x0 + (ts - start).toFloat() / max(1L, end - start) * (x1 - x0)
    fun cy(lane: Int) = top + rowH * (lane + 0.5f)
    val bh = rowH * blockFrac
    val fade = { s: StageStretch -> if (focus == null || focus == s) 1f else 0.28f }
    // Connectors first, so the blocks sit over their ends.
    val cw = connector.toPx()
    stretches.zipWithNext().forEach { (a, b) ->
        if (b.start - a.end > 300 || a.lane == b.lane) return@forEach
        val xx = x(b.start) - cw / 2
        val upper = if (a.lane < b.lane) a else b; val lower = if (upper === a) b else a
        val y0 = cy(upper.lane); val y1 = cy(lower.lane)
        val alpha = 0.85f * min(fade(a), fade(b))
        drawRect(Brush.verticalGradient(listOf(c.stageColor(upper.lane).copy(alpha = alpha), c.stageColor(lower.lane).copy(alpha = alpha)), y0, y1),
            Offset(xx, y0), Size(cw, y1 - y0))
    }
    val minW = cw * 1.4f
    stretches.forEach { s ->
        val left = x(s.start); val w = max(minW, x(s.end) - left)
        val col = c.stageColor(s.lane)
        val y = cy(s.lane) - bh / 2
        val r = min(min(6.dp.toPx(), w / 2), bh / 2)
        val a = fade(s)
        drawRoundRect(Brush.verticalGradient(listOf(col.lit(c.isDark).copy(alpha = a), col.copy(alpha = a)), y, y + bh), Offset(left, y), Size(w, bh), CornerRadius(r))
    }
}

/**
 * The night's sleep stages, after the way a watch shows them: a row per stage (awake, REM, light, deep) with its total
 * on the left, each stretch a rounded block in its stage's colour, a thin gradient line joining one stretch to the
 * next, and the time along the bottom. Touch or drag for the stretch at that moment, with heart rate and blood oxygen;
 * the rest of the night fades back while one is picked. [totals] are the night's minutes per row (awake, REM, light,
 * deep) as the rest of the page counts them; without them the rows are summed from [minutes].
 */
@Composable
fun SleepStagesChart(minutes: List<MinuteUi>, start: Long, end: Long, modifier: Modifier = Modifier,
                     spo2: List<SeriesPoint> = emptyList(), totals: List<Int>? = null) {
    val c = HL.colors
    val tm = rememberTextMeasurer()
    val night = remember(minutes, start, end) { minutes.filter { it.ts in start until end }.sortedBy { it.ts } }
    val raw = remember(night, start, end) { stageStretches(night, start, end) }
    val smooth = remember(raw) { smoothStretches(raw) }
    // Smoothed by default, as a watch shows it; every minute on request.
    var everyMinute by remember { mutableStateOf(false) }
    val stretches = if (everyMinute) raw else smooth
    val rows = totals ?: (0..3).map { l -> raw.filter { it.lane == l }.sumOf { it.minutes } }
    var sel by remember(start, end) { mutableStateOf<Long?>(null) }
    var widthPx by remember { mutableStateOf(1f) }
    val padL = 64.dp; val padR = 6.dp
    val focus = sel?.let { t -> stretches.firstOrNull { t >= it.start && t < it.end } }

    // What the touched moment was: its stretch, then heart rate and blood oxygen at that minute.
    Box(Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
        val t = sel
        if (t == null) MutedText("Touch or drag along the night for each stretch, with your heart rate and blood oxygen.")
        else {
            val m = night.minByOrNull { abs(it.ts - t) }?.takeIf { abs(it.ts - t) <= 120 }
            val o = spo2.minByOrNull { abs(it.t - t) }?.takeIf { abs(it.t - t) <= 600 }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (focus != null) { Dot(c.stageColor(focus.lane), 10.dp); Spacer(Modifier.width(6.dp)) }
                    Text(focus?.let { STAGE_NAMES[it.lane] } ?: "No data", style = HL.type.bodyStrong, color = c.ink)
                    focus?.let {
                        Spacer(Modifier.width(8.dp))
                        Text("${clockAt(it.start)}–${clockAt(it.end)}, ${span(it.minutes)}", style = HL.type.label, color = c.inkSoft)
                    }
                    // A folded-in minute: say what the watch had for it.
                    val own = m?.state?.let { stageLane(it) }
                    if (focus != null && own != null && own != focus.lane) {
                        Spacer(Modifier.width(8.dp))
                        Text("(this minute ${STAGE_NAMES[own]})", style = HL.type.label, color = c.inkSoft)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(clockAt(t), style = HL.type.label, color = c.inkSoft)
                    m?.hr?.let { Text("$it bpm", style = HL.type.label, color = c.pulse) }
                    o?.let { Text("SpO2 %.0f%%".format(it.v), style = HL.type.label, color = c.oxygen) }
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    val nameStyle = HL.type.label.copy(fontSize = 13.sp, color = c.ink)
    val totalStyle = HL.type.small.copy(fontSize = 12.sp, color = c.inkSoft)
    val clockStyle = HL.type.label.copy(fontSize = 12.sp, color = c.inkSoft)
    val describe = STAGE_NAMES.indices.joinToString(", ") { "${STAGE_NAMES[it]} ${span(rows.getOrElse(it) { 0 })}" }
    Canvas(modifier.fillMaxWidth().height(236.dp)
        .semantics { contentDescription = "Sleep stages from ${clockAt(start)} to ${clockAt(end)}: $describe" }
        .pointerInput(start, end) {
            fun at(px: Float): Long { val l = padL.toPx(); val r = widthPx - padR.toPx(); return start + (((px - l) / (r - l)).coerceIn(0f, 1f) * (end - start)).toLong() }
            detectTapGestures { o -> sel = at(o.x).takeIf { sel == null || abs(it - sel!!) > 300 } }
        }
        .pointerInput(start, end) {
            fun at(px: Float): Long { val l = padL.toPx(); val r = widthPx - padR.toPx(); return start + (((px - l) / (r - l)).coerceIn(0f, 1f) * (end - start)).toLong() }
            detectHorizontalDragGestures(onDragStart = { sel = at(it.x) }) { ch, _ -> sel = at(ch.position.x); ch.consume() }
        }) {
        widthPx = size.width
        val x0 = padL.toPx(); val x1 = size.width - padR.toPx()
        val axisH = 34.dp.toPx()
        val rowH = (size.height - axisH) / 4
        fun x(ts: Long) = x0 + (ts - start).toFloat() / max(1L, end - start) * (x1 - x0)
        // Dotted lines between the rows.
        val dots = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx()))
        for (i in 1..3) drawLine(c.line, Offset(x0, rowH * i), Offset(x1, rowH * i), 1.dp.toPx(), pathEffect = dots)
        // Each row's name and its total for the night.
        STAGE_NAMES.forEachIndexed { i, name ->
            val nameText = tm.measure(name, nameStyle)
            val total = tm.measure(span(rows.getOrElse(i) { 0 }), totalStyle)
            val y = rowH * (i + 0.5f) - (nameText.size.height + total.size.height) / 2f
            drawText(nameText, topLeft = Offset(0f, y))
            drawText(total, topLeft = Offset(0f, y + nameText.size.height))
        }
        stages(stretches, start, end, x0, x1, 0f, rowH, c, blockFrac = 0.62f, connector = 2.5.dp, focus = focus)
        // The time along the bottom: a dot every half hour, a bar at each end with its clock time under it.
        val ay = rowH * 4 + 9.dp.toPx()
        val firstHalf = (start / 1800 + 1) * 1800
        var h = firstHalf
        while (h < end) {
            if (x(h) - x0 > 6.dp.toPx() && x1 - x(h) > 6.dp.toPx()) drawCircle(c.inkSoft.copy(alpha = if (h % 3600 == 0L) 0.8f else 0.45f), if (h % 3600 == 0L) 1.7.dp.toPx() else 1.2.dp.toPx(), Offset(x(h), ay))
            h += 1800
        }
        listOf(x0, x1).forEach { xx -> drawLine(c.inkSoft, Offset(xx, ay - 4.dp.toPx()), Offset(xx, ay + 4.dp.toPx()), 1.5.dp.toPx()) }
        val st = tm.measure(clockAt(start), clockStyle)
        val en = tm.measure(clockAt(end), clockStyle)
        drawText(st, topLeft = Offset(x0, ay + 7.dp.toPx()))
        drawText(en, topLeft = Offset(x1 - en.size.width, ay + 7.dp.toPx()))
        // The moment picked.
        sel?.let { t -> drawLine(c.ink.copy(alpha = 0.55f), Offset(x(t), 0f), Offset(x(t), rowH * 4), 1.2.dp.toPx()) }
    }
    // Smoothed or every minute: what the watch scored minute by minute is a click away.
    if (raw.size != smooth.size) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (everyMinute) "Every minute as the watch scored it." else "Smoothed: a stage under $STAGE_FOLD_MIN minutes inside another is folded in.",
                style = HL.type.small.copy(fontSize = 12.sp), color = c.inkSoft, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(if (everyMinute) "Smooth" else "Every minute", style = HL.type.label, color = c.ink,
                modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .border(1.dp, c.line, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) { everyMinute = !everyMinute }
                    .padding(horizontal = 12.dp, vertical = 7.dp))
        }
    }
}

/** The stages chart in small, for a tile: the same rows and colours, no labels or axis. */
@Composable
fun SleepStagesMini(minutes: List<MinuteUi>, start: Long, end: Long, modifier: Modifier = Modifier, height: Dp = 54.dp) {
    val c = HL.colors
    val stretches = remember(minutes, start, end) { smoothStretches(stageStretches(minutes, start, end)) }
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = "Sleep stages from ${clockAt(start)} to ${clockAt(end)}" }) {
        stages(stretches, start, end, 0f, size.width, 0f, size.height / 4, c, blockFrac = 0.7f, connector = 1.5.dp, focus = null)
    }
}

/** A sleep length in the large type of a headline, hours and minutes big and their units small, in [color]. */
@Composable
fun SleepLength(minutes: Int?, color: Color, modifier: Modifier = Modifier, size: Int = 34) {
    val unit = SpanStyle(fontSize = (size * 0.5).sp)
    Text(
        buildAnnotatedString {
            if (minutes == null) append("–")
            else {
                append("${minutes / 60}"); withStyle(unit) { append("h ") }
                append("%02d".format(minutes % 60)); withStyle(unit) { append("m") }
            }
        },
        style = HL.type.stat.copy(fontSize = size.sp, lineHeight = (size * 1.02).sp), color = color, modifier = modifier, maxLines = 1,
    )
}
