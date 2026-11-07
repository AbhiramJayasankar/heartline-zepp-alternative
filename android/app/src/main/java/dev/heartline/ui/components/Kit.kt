package dev.heartline.ui.components

import dev.heartline.ui.theme.raised

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.heartline.app.DayPoint
import dev.heartline.app.MinuteState
import dev.heartline.app.MinuteUi
import dev.heartline.ui.Icons
import dev.heartline.ui.theme.HL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.abs
import kotlin.math.min

/** Rounded surface for a block of content; tappable cards read as one button with [onClickLabel] to screen readers. */
@Composable
fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, onClickLabel: String? = null, tint: Color? = null,
         padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    val c = HL.colors
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(tint ?: c.surface).border(1.dp, c.line, shape)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = onClick != null) {}
            .padding(padding),
        content = content,
    )
}

/** Section heading (a screen-reader heading) with an optional text action on the right. */
@Composable
fun Heading(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    val c = HL.colors
    Row(modifier.fillMaxWidth().padding(top = 26.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = HL.type.section, color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
        if (action != null && onAction != null) {
            Box(Modifier.sizeIn(minHeight = 48.dp).clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center) {
                Text(action, style = HL.type.label, color = c.pulse)
            }
        }
    }
}

/** A small coloured label before a tile's title. */
@Composable
fun AreaLabel(text: String, color: Color, modifier: Modifier = Modifier, dot: Boolean = true) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (dot) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(7.dp))
        }
        Text(text, style = HL.type.label, color = HL.colors.inkSoft)
    }
}

/** A big number with a smaller unit, in the display face. */
@Composable
fun BigValue(value: String, unit: String? = null, modifier: Modifier = Modifier, size: Int = 34, color: Color = HL.colors.ink) {
    Text(
        buildAnnotatedString {
            append(value)
            if (unit != null && value != "–") withStyle(SpanStyle(fontSize = (size * 0.42).sp, color = HL.colors.inkSoft, fontFamily = HL.type.label.fontFamily, fontWeight = HL.type.label.fontWeight)) { append(" $unit") }
        },
        style = HL.type.stat.copy(fontSize = size.sp, lineHeight = (size * 1.02).sp), color = color, modifier = modifier, maxLines = 1,
    )
}

/** A compact change against a reference: "▲ 4 vs usual". [good] colours it; null keeps it neutral. */
@Composable
fun Delta(text: String, good: Boolean?, modifier: Modifier = Modifier) {
    val c = HL.colors
    val color = when (good) { true -> c.ok; false -> c.pulse; null -> c.inkSoft }
    Box(modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.13f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(text, style = HL.type.label.copy(fontSize = 12.sp), color = color, maxLines = 1)
    }
}

/** A horizontal progress bar; read out as a percentage. */
@Composable
fun Meter(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp, track: Color = HL.colors.line, description: String? = null) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(600), label = "meter")
    Box(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(track)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f); if (description != null) contentDescription = description },
    ) { Box(Modifier.fillMaxWidth(f).height(height).clip(RoundedCornerShape(50)).background(color)) }
}

/** A ring gauge, filled clockwise from the top, with content in the middle. */
@Composable
fun Ring(fraction: Float, color: Color, modifier: Modifier = Modifier, size: Dp = 120.dp, stroke: Dp = 12.dp, description: String? = null,
         content: @Composable BoxScope.() -> Unit = {}) {
    val c = HL.colors
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(700), label = "ring")
    Box(modifier.size(size).semantics(mergeDescendants = true) {
        progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f); if (description != null) contentDescription = description
    }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val s = stroke.toPx(); val inset = s / 2
            val arc = Size(this.size.width - s, this.size.height - s)
            drawArc(c.line, -90f, 360f, false, Offset(inset, inset), arc, style = Stroke(s, cap = StrokeCap.Round))
            if (f > 0f) drawArc(color, -90f, 360f * f, false, Offset(inset, inset), arc, style = Stroke(s, cap = StrokeCap.Round))
        }
        content()
    }
}

/** A labelled value for stat grids; read as "label, value unit". */
@Composable
fun Fact(label: String, value: String, modifier: Modifier = Modifier, unit: String? = null, note: String? = null, color: Color = HL.colors.ink) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = HL.type.label, color = HL.colors.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        BigValue(value, unit, size = 24, color = color)
        if (note != null) Text(note, style = HL.type.small.copy(fontSize = 12.sp), color = HL.colors.inkSoft, maxLines = 2)
    }
}

/** Facts in rows of [columns]. */
@Composable
fun FactGrid(items: List<FactItem>, columns: Int = 3) {
    items.chunked(columns).forEachIndexed { i, row ->
        if (i > 0) Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            row.forEach { f -> Fact(f.label, f.value, Modifier.weight(1f), unit = f.unit, note = f.note, color = f.color ?: HL.colors.ink) }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}
data class FactItem(val label: String, val value: String, val unit: String? = null, val note: String? = null, val color: Color? = null)

/** A short explanation with an accent rule on the left. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier, color: Color = HL.colors.inkSoft, title: String? = null) {
    val c = HL.colors
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (color == c.inkSoft) c.raised else color.copy(alpha = 0.10f))
        .semantics(mergeDescendants = true) {}.padding(horizontal = 14.dp, vertical = 12.dp)) {
        if (title != null) Text(title, style = HL.type.bodyStrong.copy(fontSize = 14.sp), color = if (color == c.inkSoft) c.ink else color)
        Text(text, style = HL.type.small, color = c.inkSoft)
    }
}

/** ‹ date › stepper; both arrows are 48 dp buttons with spoken labels. */
@Composable
fun DateStepper(title: String, subtitle: String?, onPrev: () -> Unit, onNext: () -> Unit, nextEnabled: Boolean, modifier: Modifier = Modifier) {
    val c = HL.colors
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(50)),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = "Earlier", role = Role.Button, onClick = onPrev)
            .semantics { contentDescription = "Earlier" }, contentAlignment = Alignment.Center) {
            Icon(Icons.back, null, tint = c.ink, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = HL.type.bodyStrong, color = c.ink, maxLines = 1)
            if (subtitle != null) Text(subtitle, style = HL.type.small.copy(fontSize = 12.sp), color = c.inkSoft, maxLines = 1)
        }
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(enabled = nextEnabled, onClickLabel = "Later", role = Role.Button, onClick = onNext)
            .semantics { contentDescription = "Later" }, contentAlignment = Alignment.Center) {
            Icon(Icons.chevron, null, tint = if (nextEnabled) c.ink else c.line, modifier = Modifier.size(20.dp))
        }
    }
}

private val dayShort = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/**
 * Daily values as bars (or dots joined by a line), newest on the right, with an optional dashed [reference] (goal or
 * usual level). Tap a bar for its day and value. Screen readers get the average, the latest value and the range.
 */
@Composable
fun DayBars(points: List<DayPoint>, color: Color, modifier: Modifier = Modifier, height: Dp = 140.dp, reference: Double? = null, referenceLabel: String? = null,
            format: (Double) -> String = { "%,.0f".format(it) }, unit: String = "", line: Boolean = false, zeroBased: Boolean = !line, highlightLast: Boolean = true) {
    val c = HL.colors
    val tm = rememberTextMeasurer()
    var picked by remember(points) { mutableStateOf<Int?>(null) }
    val valid = points.mapNotNull { it.value }
    val spoken = if (valid.isEmpty()) "No data" else "Average ${format(valid.average())} $unit, latest ${points.lastOrNull { it.value != null }?.value?.let(format)} $unit, " +
        "range ${format(valid.min())} to ${format(valid.max())} $unit over ${points.size} days"
    Column(modifier.fillMaxWidth()) {
        val sel = picked?.let { points.getOrNull(it) }
        Row(Modifier.fillMaxWidth().heightIn(min = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            if (sel != null) {
                Text(sel.date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)), style = HL.type.label, color = c.inkSoft)
                Spacer(Modifier.width(8.dp))
                Text(sel.value?.let { format(it) + if (unit.isNotEmpty()) " $unit" else "" } ?: "no data", style = HL.type.bodyStrong, color = c.ink)
            } else if (referenceLabel != null && reference != null) {
                Text("- - $referenceLabel", style = HL.type.small.copy(fontSize = 12.sp), color = c.inkSoft)
            }
        }
        Canvas(Modifier.fillMaxWidth().height(height).semantics { contentDescription = spoken }
            .pointerInput(points) { detectTapGestures { o -> val i = ((o.x / size.width) * points.size).toInt().coerceIn(0, points.lastIndex); picked = if (picked == i) null else i } }
            .pointerInput(points) { detectHorizontalDragGestures { ch, _ -> picked = ((ch.position.x / size.width) * points.size).toInt().coerceIn(0, points.lastIndex); ch.consume() } }) {
            if (points.isEmpty()) return@Canvas
            val padB = 18.dp.toPx(); val plotH = size.height - padB - 4.dp.toPx()
            val vals = valid + listOfNotNull(reference)
            if (vals.isEmpty()) return@Canvas
            var lo = if (zeroBased) 0.0 else vals.min(); var hi = vals.max()
            if (!zeroBased) { val pad = (hi - lo).coerceAtLeast(1.0) * 0.15; lo -= pad; hi += pad }
            if (hi <= lo) hi = lo + 1
            val slot = size.width / points.size
            fun x(i: Int) = slot * i + slot / 2
            fun y(v: Double) = 4.dp.toPx() + (1 - ((v - lo) / (hi - lo)).toFloat()) * plotH
            if (line) {
                val path = Path(); var started = false; var prev = -2
                points.forEachIndexed { i, p -> p.value?.let { v -> if (!started || i - prev > 1) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)); started = true; prev = i } }
                drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                points.forEachIndexed { i, p -> p.value?.let { v ->
                    val last = i == points.lastIndex && highlightLast
                    drawCircle(if (picked == i) c.ink else color, (if (last || picked == i) 4.5f else 3f).dp.toPx(), Offset(x(i), y(v)))
                } }
            } else {
                val bw = max(4f, slot * 0.62f)
                points.forEachIndexed { i, p -> p.value?.let { v ->
                    val top = y(v); val base = y(lo)
                    val alpha = when { picked == i -> 1f; picked != null -> 0.45f; highlightLast && i == points.lastIndex -> 1f; else -> 0.7f }
                    drawRoundRect(color.copy(alpha = alpha), Offset(x(i) - bw / 2, top), Size(bw, max(2f, base - top)), CornerRadius(bw / 3))
                } }
            }
            reference?.let { r -> drawLine(c.inkSoft, Offset(0f, y(r)), Offset(size.width, y(r)), 1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))) }
            // Day initials under the bars, every day if they fit, otherwise every few.
            val every = if (points.size <= 14) 1 else (points.size / 7)
            points.forEachIndexed { i, p ->
                if (i % every != 0 && i != points.lastIndex) return@forEachIndexed
                val label = if (points.size <= 7) p.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).take(3) else p.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.ENGLISH)
                label(tm, label, x(i), size.height - padB / 2, if (i == points.lastIndex) c.ink else c.inkSoft, center = true)
            }
        }
        if (points.isNotEmpty()) Row(Modifier.fillMaxWidth()) {
            Text(points.first().date.format(dayShort), style = HL.type.small.copy(fontSize = 11.sp), color = c.inkSoft, modifier = Modifier.weight(1f))
            Text(points.last().date.format(dayShort), style = HL.type.small.copy(fontSize = 11.sp), color = c.inkSoft)
        }
    }
}

/** Sleep stages through the night as a step chart: awake on top, then REM, light and deep. */
@Composable
fun Hypnogram(minutes: List<MinuteUi>, start: Long, end: Long, modifier: Modifier = Modifier, spo2: List<dev.heartline.app.SeriesPoint> = emptyList()) {
    val c = HL.colors
    val tm = rememberTextMeasurer()
    val lanes = listOf("Awake", "REM", "Light", "Deep")
    fun lane(s: MinuteState): Int? = when (s) { MinuteState.SLEEP_REM -> 1; MinuteState.SLEEP_LIGHT -> 2; MinuteState.SLEEP_DEEP -> 3; MinuteState.ACTIVE, MinuteState.IDLE -> 0; else -> null }
    fun color(l: Int) = when (l) { 0 -> c.warn; 1 -> c.sleepRem; 2 -> c.sleepLight; else -> c.sleepDeep }
    val inNight = remember(minutes, start, end) { minutes.filter { it.ts in start until end }.sortedBy { it.ts } }
    val clock = DateTimeFormatter.ofPattern("HH:mm")
    var sel by remember(start, end) { mutableStateOf<Long?>(null) }
    var widthPx by remember { mutableStateOf(1f) }
    val padLdp = 48.dp
    // Readout: the touched minute's stage, heart rate and blood oxygen.
    Box(Modifier.fillMaxWidth().heightIn(min = 40.dp)) {
        val t = sel
        if (t == null) MutedText("Touch or drag along the night for each minute's stage, heart rate and blood oxygen.")
        else {
            val m = inNight.minByOrNull { abs(it.ts - t) }?.takeIf { abs(it.ts - t) <= 120 }
            val o = spo2.minByOrNull { abs(it.t - t) }?.takeIf { abs(it.t - t) <= 600 }
            Column {
                Text(Instant.ofEpochSecond(t).atZone(ZoneId.systemDefault()).format(clock), style = HL.type.label, color = c.inkSoft)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    val l = m?.state?.let { lane(it) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (l != null) { Dot(color(l), 9.dp); Spacer(Modifier.width(5.dp)) }
                        Text(m?.let { stateName(it.state) } ?: "No data", style = HL.type.bodyStrong, color = c.ink)
                    }
                    m?.hr?.let { Text("$it bpm", style = HL.type.bodyStrong, color = c.pulse) }
                    o?.let { Text("SpO2 %.0f%%".format(it.v), style = HL.type.bodyStrong, color = c.oxygen) }
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    Canvas(modifier.fillMaxWidth().height(170.dp)
        .semantics { contentDescription = "Sleep stages from ${Instant.ofEpochSecond(start).atZone(ZoneId.systemDefault()).format(clock)} to ${Instant.ofEpochSecond(end).atZone(ZoneId.systemDefault()).format(clock)}" }
        .pointerInput(start, end) {
            fun at(px: Float): Long { val padL = padLdp.toPx(); return start + (((px - padL) / (widthPx - padL)).coerceIn(0f, 1f) * (end - start)).toLong() }
            detectTapGestures { o -> sel = at(o.x).takeIf { sel == null || abs(it - sel!!) > 300 } }
        }
        .pointerInput(start, end) {
            fun at(px: Float): Long { val padL = padLdp.toPx(); return start + (((px - padL) / (widthPx - padL)).coerceIn(0f, 1f) * (end - start)).toLong() }
            detectHorizontalDragGestures(onDragStart = { sel = at(it.x) }) { ch, _ -> sel = at(ch.position.x); ch.consume() }
        }) {
        widthPx = size.width
        val padL = padLdp.toPx(); val padB = 20.dp.toPx()
        val w = size.width - padL; val laneH = (size.height - padB) / lanes.size
        fun x(ts: Long) = padL + ((ts - start).toFloat() / max(1L, end - start)) * w
        lanes.forEachIndexed { i, name ->
            drawRect(c.line.copy(alpha = 0.35f), Offset(padL, i * laneH + laneH * 0.5f - 0.5f), Size(w, 1f))
            label(tm, name, 0f, i * laneH + laneH / 2, c.inkSoft)
        }
        val mw = max(1.5f, w / max(1f, (end - start) / 60f))
        inNight.forEach { m -> lane(m.state)?.let { l ->
            val top = l * laneH + laneH * 0.18f
            drawRoundRect(color(l), Offset(x(m.ts), top), Size(mw + 0.6f, laneH * 0.64f), CornerRadius(2f))
        } }
        val hours = (start / 3600 + 1..end / 3600).map { it * 3600 }
        val step = max(1, hours.size / 6)
        hours.forEachIndexed { i, h -> if (i % step == 0) {
            drawLine(c.line.copy(alpha = 0.5f), Offset(x(h), size.height - padB), Offset(x(h), size.height - padB + 3.dp.toPx()), 1f)
            label(tm, Instant.ofEpochSecond(h).atZone(ZoneId.systemDefault()).format(clock), x(h), size.height - padB / 2 + 2.dp.toPx(), c.inkSoft, center = true)
        } }
        sel?.let { t -> drawLine(c.ink.copy(alpha = 0.6f), Offset(x(t), 0f), Offset(x(t), size.height - padB), 1.2.dp.toPx()) }
    }
}

/**
 * A share against its typical range on a 0-100% track: the typical range is the tinted stretch with end ticks, your
 * value the dot. [scale] is the track's top value (a stage never takes the whole night, so 0-80% reads better).
 */
@Composable
fun RangeBar(fraction: Float, low: Float, high: Float, color: Color, modifier: Modifier = Modifier, scale: Float = 0.8f) {
    val c = HL.colors
    Canvas(modifier.fillMaxWidth().height(18.dp)) {
        val h = 6.dp.toPx(); val cy = size.height / 2
        fun x(f: Float) = (f / scale).coerceIn(0f, 1f) * (size.width - h) + h / 2
        drawRoundRect(c.line, Offset(0f, cy - h / 2), Size(size.width, h), CornerRadius(h / 2))
        drawRoundRect(color.copy(alpha = 0.35f), Offset(x(low), cy - h / 2), Size(x(high) - x(low), h), CornerRadius(h / 2))
        listOf(low, high).forEach { f -> drawLine(color.copy(alpha = 0.8f), Offset(x(f), cy - h), Offset(x(f), cy + h), 1.5.dp.toPx()) }
        drawCircle(c.surface, 7.dp.toPx(), Offset(x(fraction), cy))
        drawCircle(color, 5.dp.toPx(), Offset(x(fraction), cy))
    }
}

/** Where a share sits against its typical range, in words. */
fun rangeWord(fraction: Float, low: Float, high: Float): String = when {
    fraction < low -> "below typical"; fraction > high -> "above typical"; else -> "in the typical range"
}

/** Minutes as "7h 05m" for headlines. */
fun hm(min: Int?): String = min?.let { "${it / 60}h ${"%02d".format(it % 60)}m" } ?: "–"

/** The latest non-null value of daily points with its date. */
fun List<DayPoint>.latest(): DayPoint? = lastOrNull { it.value != null }
fun List<DayPoint>.avgOf(days: Int = size): Double? = takeLast(days).mapNotNull { it.value }.takeIf { it.isNotEmpty() }?.average()
fun shortDate(d: LocalDate): String = d.format(dayShort)
