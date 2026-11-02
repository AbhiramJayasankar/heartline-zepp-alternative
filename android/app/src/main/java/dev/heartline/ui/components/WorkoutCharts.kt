package dev.heartline.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.heartline.app.SegmentUi
import dev.heartline.app.SeriesPoint
import dev.heartline.app.SetUi
import dev.heartline.app.ZoneTimeUi
import dev.heartline.ui.theme.HL
import dev.heartline.ui.theme.Palette

/** Zone colours run from the calm sleep blues to the pulse red, so the top zone reads as effort. */
fun zoneColor(zone: Int, c: Palette): Color = when (zone) {
    1 -> c.sleepLight; 2 -> c.sleepDeep; 3 -> c.ok; 4 -> c.warn; else -> c.pulse
}

fun segmentColor(kind: String, c: Palette): Color = when (kind) {
    "lifting" -> c.pulse; "running" -> c.warn; "walking" -> c.ok; "rest" -> c.idle; else -> c.line
}

fun clock(seconds: Double): String { val s = seconds.toInt(); return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

/**
 * Heart rate across a session: faint horizontal zone bands, shaded set intervals, and the HR line on top.
 * [durationS] fixes the time axis so sets and HR line up exactly.
 */
@Composable
fun SessionChart(hr: List<SeriesPoint>, durationS: Int, zones: List<ZoneTimeUi>, sets: List<SetUi>, modifier: Modifier = Modifier) {
    val c = HL.colors
    val tm = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(200.dp)) {
        val left = 0f; val right = size.width - 30.dp.toPx(); val top = 6.dp.toPx(); val bottom = size.height - 18.dp.toPx()
        val vals = hr.map { it.v }
        val lo = ((vals.minOrNull() ?: 60.0) - 8).coerceAtMost(zones.firstOrNull()?.lowBpm?.toDouble() ?: 999.0).coerceAtLeast(40.0)
        val hi = ((vals.maxOrNull() ?: 160.0) + 8).coerceAtLeast(lo + 30)
        fun x(t: Double) = left + (t / durationS.coerceAtLeast(1)).toFloat() * (right - left)
        fun y(v: Double) = bottom - ((v - lo) / (hi - lo)).toFloat() * (bottom - top)
        zones.forEach { z ->
            val yTop = y((z.highBpm ?: hi.toInt()).toDouble().coerceAtMost(hi)); val yBot = y(z.lowBpm.toDouble().coerceAtLeast(lo))
            if (yBot > top && yTop < bottom) drawRect(zoneColor(z.zone, c).copy(alpha = 0.10f), Offset(left, yTop.coerceAtLeast(top)), Size(right - left, (yBot.coerceAtMost(bottom) - yTop.coerceAtLeast(top))))
        }
        sets.forEach { s -> drawRect(c.pulse.copy(alpha = 0.16f), Offset(x(s.start), top), Size((x(s.end) - x(s.start)).coerceAtLeast(2f), bottom - top)) }
        if (hr.size > 1) {
            val path = Path().apply { hr.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.t), y(p.v)) else lineTo(x(p.t), y(p.v)) } }
            drawPath(path, c.ink, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
        }
        listOf(lo, (lo + hi) / 2, hi).forEach { v -> label(tm, "${v.toInt()}", right + 4.dp.toPx(), y(v) - 7.dp.toPx(), c.inkSoft) }
        val step = listOf(300, 600, 900, 1800).firstOrNull { durationS / it <= 6 } ?: 3600
        var t = 0
        while (t <= durationS) { label(tm, clock(t.toDouble()), x(t.toDouble()), bottom + 3.dp.toPx(), c.inkSoft, center = t > 0); t += step }
    }
}

/** A thin strip of how hard the arm was moving, under the session chart. */
@Composable
fun MotionStrip(motion: List<SeriesPoint>, durationS: Int, modifier: Modifier = Modifier) {
    val c = HL.colors
    Canvas(modifier.fillMaxWidth().height(36.dp)) {
        val max = (motion.maxOfOrNull { it.v } ?: 1.0).coerceAtLeast(0.05)
        val w = size.width - 30.dp.toPx()
        val bar = (w / motion.size.coerceAtLeast(1)).coerceAtLeast(1f)
        motion.forEach { p ->
            val h = (p.v / max).toFloat() * size.height
            drawRect(c.inkSoft.copy(alpha = 0.55f), Offset((p.t / durationS.coerceAtLeast(1)).toFloat() * w, size.height - h), Size(bar, h))
        }
    }
}

/** What the session was made of, as one coloured bar. */
@Composable
fun SegmentBar(segments: List<SegmentUi>, durationS: Int, modifier: Modifier = Modifier) {
    val c = HL.colors
    Canvas(modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(4.dp))) {
        drawRect(c.line)
        segments.forEach { s ->
            val x0 = (s.start / durationS.coerceAtLeast(1)).toFloat() * size.width; val x1 = (s.end / durationS.coerceAtLeast(1)).toFloat() * size.width
            drawRect(segmentColor(s.kind, c), Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(1f), size.height))
        }
    }
}

/** Minutes in each heart-rate zone as horizontal bars, top zone first. */
@Composable
fun ZoneBars(zones: List<ZoneTimeUi>, modifier: Modifier = Modifier) {
    val c = HL.colors
    val total = zones.sumOf { it.seconds }.coerceAtLeast(1)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        zones.sortedByDescending { it.zone }.forEach { z ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(112.dp)) {
                    Text(z.name, style = HL.type.label, color = c.ink)
                    Text(if (z.highBpm == null) "${z.lowBpm}+ bpm" else "${z.lowBpm}–${z.highBpm} bpm", style = HL.type.small, color = c.inkSoft)
                }
                Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(c.line)) {
                    Box(Modifier.fillMaxWidth(z.seconds.toFloat() / total).height(10.dp).clip(RoundedCornerShape(5.dp)).background(zoneColor(z.zone, c)))
                }
                Spacer(Modifier.width(10.dp))
                Text(fmtHm((z.seconds + 30) / 60), style = HL.type.label, color = c.ink, modifier = Modifier.width(52.dp))
            }
        }
    }
}

/** A plain line chart for speed, cadence or altitude against workout time, with min and max marked. */
@Composable
fun SeriesChart(points: List<SeriesPoint>, color: Color, unit: String, modifier: Modifier = Modifier) {
    val c = HL.colors
    val tm = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(110.dp)) {
        if (points.size < 2) return@Canvas
        val right = size.width - 40.dp.toPx(); val top = 4.dp.toPx(); val bottom = size.height - 4.dp.toPx()
        val t0 = points.first().t; val t1 = points.last().t.coerceAtLeast(t0 + 1)
        val lo = points.minOf { it.v }; val hi = points.maxOf { it.v }.coerceAtLeast(lo + 1e-6)
        fun x(t: Double) = ((t - t0) / (t1 - t0)).toFloat() * right
        fun y(v: Double) = bottom - ((v - lo) / (hi - lo)).toFloat() * (bottom - top)
        val path = Path().apply { points.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.t), y(p.v)) else lineTo(x(p.t), y(p.v)) } }
        drawPath(path, color, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
        label(tm, fmtSeries(hi) + " " + unit, right + 4.dp.toPx(), top, c.inkSoft)
        label(tm, fmtSeries(lo), right + 4.dp.toPx(), bottom - 14.dp.toPx(), c.inkSoft)
    }
}

private fun fmtSeries(v: Double) = if (v >= 100) "%.0f".format(v) else "%.1f".format(v)

@Composable
fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, style = HL.type.small, color = HL.colors.inkSoft)
    }
}
