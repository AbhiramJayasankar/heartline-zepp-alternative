package dev.heartline.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.heartline.app.SeriesPoint
import dev.heartline.ui.theme.HL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** One plotted series. [format] renders a value for the readout; [gap] breaks the line across missing data (x units).
 * A zero [width] shows the series in the readout only. */
data class ChartLine(
    val points: List<SeriesPoint>, val color: Color, val label: String, val unit: String = "",
    val width: Dp = 2.dp, val fill: Boolean = false, val dashed: Boolean = false, val step: Boolean = false,
    val gap: Double = 600.0, val format: (Double) -> String = { "%.0f".format(it) }, val axis: Boolean = true,
)
/** Bars from [SeriesPoint.t] to t + [width] (x units), e.g. steps per hour. */
data class ChartBars(val points: List<SeriesPoint>, val width: Double, val color: Color, val label: String, val unit: String = "",
    val format: (Double) -> String = { "%.0f".format(it) })
/** A horizontal value band (heart-rate zone, stress band). */
data class ChartBand(val low: Double, val high: Double, val color: Color)
/** A shaded stretch of x (sleep, workout, set), optionally labelled in the readout when touched. */
data class ChartSpan(val start: Double, val end: Double, val color: Color, val label: String? = null)

private val clockFmt = DateTimeFormatter.ofPattern("HH:mm")
private val clockSecFmt = DateTimeFormatter.ofPattern("HH:mm:ss")
/** Epoch seconds as local clock time; the default x label. */
fun epochClock(t: Double, seconds: Boolean = false): String =
    Instant.ofEpochSecond(t.toLong()).atZone(ZoneId.systemDefault()).format(if (seconds) clockSecFmt else clockFmt)

/**
 * Touch-readable chart. Touch or drag sideways to read exact values at a moment (vertical drags still scroll the
 * page), pinch to zoom into part of the range, double-tap to zoom back out. The readout above the chart shows the
 * selected time, every series' value there, and the label of any span under the finger.
 */
@Composable
fun InteractiveChart(
    lines: List<ChartLine>, xStart: Double, xEnd: Double, modifier: Modifier = Modifier,
    height: Dp = 200.dp, yMin: Double? = null, yMax: Double? = null,
    bars: List<ChartBars> = emptyList(), bands: List<ChartBand> = emptyList(), spans: List<ChartSpan> = emptyList(),
    xLabel: (Double) -> String = { epochClock(it) }, readoutX: (Double) -> String = { epochClock(it, seconds = true) },
    minSpan: Double = 600.0, idleHint: String? = "Touch the chart for exact values. Pinch to zoom.",
    summary: (@Composable () -> Unit)? = null,
) {
    val c = HL.colors
    val tm = rememberTextMeasurer()
    var view by remember(xStart, xEnd) { mutableStateOf(xStart to xEnd) }
    var selected by remember(xStart, xEnd) { mutableStateOf<Double?>(null) }
    var widthPx by remember { mutableStateOf(1f) }
    val rightPad = with(androidx.compose.ui.platform.LocalDensity.current) { 34.dp.toPx() }
    fun toX(px: Float): Double { val (a, b) = view; return a + (px / (widthPx - rightPad)).coerceIn(0f, 1f) * (b - a) }

    Column(modifier.fillMaxWidth()) {
        // Readout: values at the touched moment, or the chart's own summary/hint at rest.
        Box(Modifier.fillMaxWidth().heightIn(min = 62.dp)) {
            val sel = selected
            if (sel == null) {
                if (summary != null) summary() else if (idleHint != null) MutedText(idleHint)
            } else {
                Column {
                    Text(readoutX(sel), style = HL.type.label, color = c.inkSoft)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        lines.forEach { l -> nearest(l.points, sel, l.gap)?.let { p -> ReadoutValue(l.color, l.label, l.format(p.v) + if (l.unit.isEmpty()) "" else " ${l.unit}") } }
                        bars.forEach { b -> b.points.firstOrNull { sel >= it.t && sel < it.t + b.width }?.let { p -> ReadoutValue(b.color, b.label, b.format(p.v) + if (b.unit.isEmpty()) "" else " ${b.unit}") } }
                        spans.firstOrNull { sel >= it.start && sel < it.end && it.label != null }?.let { s -> ReadoutValue(s.color.copy(alpha = 1f), s.label!!, "") }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Canvas(
            Modifier.fillMaxWidth().height(height)
                .pointerInput(xStart, xEnd) {
                    detectTapGestures(
                        onDoubleTap = { view = xStart to xEnd; selected = null },
                        onTap = { selected = toX(it.x) },
                    )
                }
                .pointerInput(xStart, xEnd) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var pinching = false; var scrubbing = false; var travelX = 0f; var travelY = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                pinching = true
                                val zoom = event.calculateZoom(); val centre = toX(event.calculateCentroid().x)
                                val (a, b) = view
                                val span = ((b - a) / zoom).coerceIn(minSpan, xEnd - xStart)
                                val frac = (centre - a) / (b - a)
                                var na = centre - frac * span; var nb = na + span
                                if (na < xStart) { na = xStart; nb = xStart + span }
                                if (nb > xEnd) { nb = xEnd; na = xEnd - span }
                                view = na to nb
                                event.changes.forEach { it.consume() }
                            } else if (!pinching) {
                                val ch = pressed.first(); val d = ch.positionChange()
                                travelX += abs(d.x); travelY += abs(d.y)
                                // Claim the gesture only once it is clearly sideways, so the page still scrolls.
                                if (!scrubbing && travelX > 12f && travelX > travelY * 1.3f) scrubbing = true
                                if (scrubbing) {
                                    val zoomed = view.second - view.first < xEnd - xStart - 1
                                    if (zoomed && pressed.size == 1 && selected == null) {
                                        // Zoomed with no readout: one-finger drag pans.
                                        val dx = -d.x / (widthPx - rightPad) * (view.second - view.first)
                                        val span = view.second - view.first
                                        val na = (view.first + dx).coerceIn(xStart, xEnd - span); view = na to na + span
                                    } else selected = toX(ch.position.x)
                                    ch.consume()
                                }
                            }
                        }
                        if (down.pressed) Unit
                    }
                },
        ) {
            widthPx = size.width
            val right = size.width - rightPad; val top = 4.dp.toPx(); val bottom = size.height - 18.dp.toPx()
            val (a, b) = view
            fun x(t: Double) = ((t - a) / (b - a)).toFloat() * right
            val visible = lines.filter { it.axis }.flatMap { l -> l.points.filter { it.t in a..b }.map { it.v } } +
                bars.flatMap { s -> s.points.filter { it.t + s.width >= a && it.t <= b }.map { it.v } }
            var lo = yMin ?: (visible.minOrNull() ?: 0.0); var hi = yMax ?: (visible.maxOrNull() ?: 1.0)
            if (bars.isNotEmpty() && yMin == null) lo = min(lo, 0.0)
            if (hi - lo < 1e-9) { lo -= 1; hi += 1 }
            val pad = (hi - lo) * 0.08; if (yMin == null && bars.isEmpty()) lo -= pad; if (yMax == null) hi += pad
            fun y(v: Double) = bottom - ((v - lo) / (hi - lo)).toFloat() * (bottom - top)

            bands.forEach { band ->
                val y1 = y(band.high.coerceAtMost(hi)); val y2 = y(band.low.coerceAtLeast(lo))
                if (y2 > top && y1 < bottom) drawRect(band.color, Offset(0f, y1.coerceAtLeast(top)), Size(right, y2.coerceAtMost(bottom) - y1.coerceAtLeast(top)))
            }
            spans.forEach { s ->
                val x1 = x(s.start).coerceAtLeast(0f); val x2 = x(s.end).coerceAtMost(right)
                if (x2 > x1) drawRect(s.color, Offset(x1, top), Size(max(x2 - x1, 1.5f), bottom - top))
            }
            // Grid and y labels at round values.
            niceTicks(lo, hi, 4).forEach { v ->
                drawLine(c.line, Offset(0f, y(v)), Offset(right, y(v)), 1f)
                label(tm, formatTick(v), right + 4.dp.toPx(), y(v) - 7.dp.toPx(), c.inkSoft)
            }
            bars.forEach { s ->
                s.points.forEach { p ->
                    if (p.t + s.width < a || p.t > b) return@forEach
                    val x1 = x(p.t); val x2 = x(p.t + s.width)
                    val w = max((x2 - x1) * 0.8f, 1.5f)
                    val yv = y(p.v); val y0 = y(max(lo, 0.0))
                    val sel = selected?.let { it >= p.t && it < p.t + s.width } == true
                    drawRect(if (sel) s.color else s.color.copy(alpha = 0.75f), Offset(x1 + (x2 - x1 - w) / 2, min(yv, y0)), Size(w, abs(y0 - yv)))
                }
            }
            lines.forEach { l ->
                if (l.width.value <= 0f) return@forEach // readout-only series
                val pts = l.points.filter { it.t >= a - l.gap && it.t <= b + l.gap }
                if (pts.isEmpty()) return@forEach
                val path = Path(); var started = false; var prev: SeriesPoint? = null
                pts.forEach { p ->
                    val px = x(p.t); val py = y(p.v)
                    if (!started || (prev != null && p.t - prev!!.t > l.gap)) { path.moveTo(px, py); started = true }
                    else { if (l.step) path.lineTo(px, y(prev!!.v)); path.lineTo(px, py) }
                    prev = p
                }
                if (l.fill && pts.size > 1) {
                    val area = Path().apply { addPath(path); lineTo(x(pts.last().t), bottom); lineTo(x(pts.first().t), bottom); close() }
                    drawPath(area, l.color.copy(alpha = 0.12f))
                }
                drawPath(path, l.color, style = Stroke(l.width.toPx(), join = StrokeJoin.Round,
                    pathEffect = if (l.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 7f)) else null))
                if (pts.size == 1) drawCircle(l.color, l.width.toPx() * 1.5f, Offset(x(pts[0].t), y(pts[0].v)))
            }
            // X labels at a round step for the visible span.
            val span = b - a
            val step = listOf(60.0, 300.0, 900.0, 1800.0, 3600.0, 7200.0, 10800.0, 21600.0, 43200.0, 86400.0, 7 * 86400.0)
                .firstOrNull { span / it <= 6 } ?: (30 * 86400.0)
            val zoneOffset = ZoneId.systemDefault().rules.getOffset(Instant.ofEpochSecond(a.toLong())).totalSeconds
            var t = ceil((a + zoneOffset) / step) * step - zoneOffset
            while (t <= b) { label(tm, xLabel(t), x(t), bottom + 3.dp.toPx(), c.inkSoft, center = true); t += step }
            // Crosshair and the value dots.
            selected?.let { s ->
                if (s in a..b) {
                    drawLine(c.ink.copy(alpha = 0.55f), Offset(x(s), top), Offset(x(s), bottom), 1.2.dp.toPx())
                    lines.filter { it.width.value > 0f }.forEach { l -> nearest(l.points, s, l.gap)?.let { p -> drawCircle(c.paper, 5.dp.toPx(), Offset(x(p.t), y(p.v))); drawCircle(l.color, 3.5.dp.toPx(), Offset(x(p.t), y(p.v))) } }
                }
            }
        }
        if (view.second - view.first < xEnd - xStart - 1) {
            Spacer(Modifier.height(4.dp))
            MutedText("Zoomed to ${xLabel(view.first)}–${xLabel(view.second)}. Double-tap to zoom out.")
        }
    }
}

@Composable
private fun ReadoutValue(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).let { it }) { Canvas(Modifier.size(9.dp)) { drawRect(color) } }
        Spacer(Modifier.width(5.dp))
        Text(label + if (value.isEmpty()) "" else " ", style = HL.type.small, color = HL.colors.inkSoft)
        if (value.isNotEmpty()) Text(value, style = HL.type.bodyStrong, color = HL.colors.ink)
    }
}

/** The data point closest to [t], or null when the nearest one is further than [gap] away. Points must be sorted. */
fun nearest(points: List<SeriesPoint>, t: Double, gap: Double): SeriesPoint? {
    if (points.isEmpty()) return null
    var lo = 0; var hi = points.lastIndex
    while (lo < hi) { val m = (lo + hi) / 2; if (points[m].t < t) lo = m + 1 else hi = m }
    val cands = listOfNotNull(points.getOrNull(lo), points.getOrNull(lo - 1))
    return cands.minByOrNull { abs(it.t - t) }?.takeIf { abs(it.t - t) <= gap }
}

private fun niceTicks(lo: Double, hi: Double, count: Int): List<Double> {
    val raw = (hi - lo) / count
    val mag = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
    var v = ceil(lo / step) * step
    val out = ArrayList<Double>()
    while (v <= hi + 1e-9) { out += v; v += step }
    return out
}

private fun formatTick(v: Double): String = if (abs(v) >= 100 || v == floor(v)) "%.0f".format(v) else "%.1f".format(v)
