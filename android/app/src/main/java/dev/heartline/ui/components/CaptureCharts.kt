package dev.heartline.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.heartline.app.CaptureActivityUi
import dev.heartline.app.RecoveryPointUi
import dev.heartline.app.RepUi
import dev.heartline.app.SeriesPoint
import dev.heartline.ui.theme.HL
import dev.heartline.ui.theme.Palette

/** Exercise groups cycle through the palette's distinct hues; the pulse red stays for heart rate. */
fun groupColor(group: Int, c: Palette): Color = listOf(c.sleepDeep, c.ok, c.warn, c.sleepRem, c.sleepLight, c.inkSoft)[group.mod(6)]

/**
 * One block per rep: width is how long the rep took (on a shared [maxDurationS] scale so sets compare), the solid part
 * is its slower half, the faded part its faster half, and the height is the wrist's arc for that rep.
 */
@Composable
fun RepStrip(reps: List<RepUi>, maxDurationS: Double, maxArc: Double, color: Color, modifier: Modifier = Modifier) {
    val c = HL.colors
    Canvas(modifier.fillMaxWidth().height(34.dp)) {
        drawLine(c.line, Offset(0f, size.height - 1), Offset(size.width, size.height - 1), 1.dp.toPx())
        val sx = size.width / maxDurationS.coerceAtLeast(1.0).toFloat()
        val minH = 5.dp.toPx(); val gap = 1.5.dp.toPx()
        var x = 0f
        reps.forEach { r ->
            val h = minH + (r.arcDeg / maxArc.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f) * (size.height - minH - 1)
            val slow = r.slowS.toFloat() * sx; val fast = r.fastS.toFloat() * sx
            val top = size.height - 1 - h
            drawRect(color, Offset(x, top), Size((slow - gap / 2).coerceAtLeast(1f), h))
            drawRect(color.copy(alpha = 0.42f), Offset(x + slow, top), Size((fast - gap).coerceAtLeast(1f), h))
            x += r.durationS.toFloat() * sx
        }
    }
}

/** Average heart-rate change after a set's peak, with the session's median rest marked. */
@Composable
fun RecoveryChart(points: List<RecoveryPointUi>, medianRestS: Double?, modifier: Modifier = Modifier,
                  compare: List<RecoveryPointUi> = emptyList(), compareLabel: String? = null) {
    val c = HL.colors
    if (points.size < 2) return
    val end = points.maxOf { it.t }.coerceAtLeast(60).toDouble()
    val line = ChartLine(points.map { SeriesPoint(it.t.toDouble(), it.delta) }, c.pulse, "Change from set peak", "bpm", fill = compare.size < 2, gap = 30.0,
        format = { "%+.1f".format(it) })
    // An earlier session's curve over the same span, for comparison.
    val other = compare.filter { it.t <= end }.takeIf { it.size >= 2 }?.let { pts ->
        ChartLine(pts.map { SeriesPoint(it.t.toDouble(), it.delta) }, c.inkSoft, compareLabel ?: "Earlier", "bpm", width = 1.5.dp, gap = 30.0, format = { "%+.1f".format(it) })
    }
    val counts = points.map { SeriesPoint(it.t.toDouble(), it.n.toDouble()) }
    InteractiveChart(listOfNotNull(line, other, ChartLine(counts, c.inkSoft, "Rests averaged", "", width = 0.dp, axis = false, gap = 30.0)), 0.0, end, modifier, height = 170.dp,
        spans = medianRestS?.takeIf { it <= end }?.let { listOf(ChartSpan(it - 1, it + 1, c.inkSoft.copy(alpha = 0.6f), "Your median rest")) } ?: emptyList(),
        xLabel = { if (it == 0.0) "peak" else "${it.toInt()}s" }, readoutX = { "${it.toInt()} s after the set's peak" }, minSpan = 30.0,
        idleHint = "Touch to see the average change at that point in a rest.")
}

/** A few seconds of the filtered optical pulse with each detected beat marked above it. */
@Composable
fun PulseWave(excerpt: List<Double>, fs: Double, beats: List<Double>, modifier: Modifier = Modifier) {
    val c = HL.colors
    val tm = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(110.dp)) {
        if (excerpt.size < 2 || fs <= 0) return@Canvas
        val top = 12.dp.toPx(); val bottom = size.height - 18.dp.toPx(); val span = excerpt.size / fs
        val lo = excerpt.min(); val hi = excerpt.max().coerceAtLeast(lo + 1e-9)
        fun x(t: Double) = (t / span).toFloat() * size.width
        fun y(v: Double) = bottom - ((v - lo) / (hi - lo)).toFloat() * (bottom - top)
        beats.forEach { b -> drawLine(c.pulse.copy(alpha = 0.35f), Offset(x(b), top), Offset(x(b), bottom), 1f); drawCircle(c.pulse, 3.dp.toPx(), Offset(x(b), 4.dp.toPx())) }
        val p = Path().apply { excerpt.forEachIndexed { i, v -> val t = i / fs; if (i == 0) moveTo(x(t), y(v)) else lineTo(x(t), y(v)) } }
        drawPath(p, c.ink, style = Stroke(1.6.dp.toPx(), join = StrokeJoin.Round))
        var s = 0
        while (s <= span) { label(tm, "${s}s", x(s.toDouble()), bottom + 3.dp.toPx(), c.inkSoft, center = s > 0, alignEnd = s >= span - 1); s += 2 }
    }
}

/** Each beat interval against the next. Regular rhythm hugs the diagonal; an irregular one fans out. */
@Composable
fun PoincarePlot(pairs: List<Pair<Double, Double>>, modifier: Modifier = Modifier) {
    val c = HL.colors
    val tm = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().aspectRatio(1f)) {
        if (pairs.isEmpty()) return@Canvas
        val all = pairs.flatMap { listOf(it.first, it.second) }.sorted()
        val mid = all[all.size / 2]; val half = maxOf(120.0, (all[(all.size * 0.98).toInt().coerceAtMost(all.lastIndex)] - all[(all.size * 0.02).toInt()]) * 0.75)
        val lo = Math.floor((mid - half) / 50) * 50; val hi = Math.ceil((mid + half) / 50) * 50
        val left = 40.dp.toPx(); val bottom = size.height - 22.dp.toPx(); val top = 6.dp.toPx(); val right = size.width - 6.dp.toPx()
        fun x(v: Double) = left + ((v - lo) / (hi - lo)).toFloat() * (right - left)
        fun y(v: Double) = bottom - ((v - lo) / (hi - lo)).toFloat() * (bottom - top)
        var g = lo
        while (g <= hi) {
            drawLine(c.gridMajor, Offset(x(g), top), Offset(x(g), bottom), 1f); drawLine(c.gridMajor, Offset(left, y(g)), Offset(right, y(g)), 1f)
            if (((g - lo) / 50).toInt() % 2 == 0) { label(tm, "${g.toInt()}", x(g), bottom + 4.dp.toPx(), c.inkSoft, center = true); label(tm, "${g.toInt()}", left - 4.dp.toPx(), y(g) - 7.dp.toPx(), c.inkSoft, alignEnd = true) }
            g += 50
        }
        drawLine(c.inkSoft, Offset(x(lo), y(lo)), Offset(x(hi), y(hi)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
        pairs.forEach { (a, b) -> drawCircle(c.pulse.copy(alpha = 0.5f), 2.6.dp.toPx(), Offset(x(a.coerceIn(lo, hi)), y(b.coerceIn(lo, hi)))) }
    }
}

/** The watch's own workouts as named bars on the capture's time axis. */
@Composable
fun ActivityBand(activities: List<CaptureActivityUi>, durationS: Int, modifier: Modifier = Modifier) {
    val c = HL.colors
    val tm = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(20.dp)) {
        val w = size.width - 30.dp.toPx()
        activities.forEach { a ->
            val x0 = (a.start / durationS.coerceAtLeast(1)).toFloat() * w; val x1 = (a.end / durationS.coerceAtLeast(1)).toFloat() * w
            drawRect(c.ink.copy(alpha = 0.10f), Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(2f), size.height))
            drawRect(c.ink, Offset(x0, 0f), Size(2.dp.toPx(), size.height))
            val r = tm.measure(a.type, axisStyle.copy(color = c.ink))
            if (r.size.width < x1 - x0 - 8.dp.toPx()) label(tm, a.type, x0 + 6.dp.toPx(), (size.height - r.size.height) / 2, c.ink)
        }
    }
}

/** Heart rate around one block (e.g. a treadmill walk), the block shaded. */
@Composable
fun HrWindowChart(hr: List<SeriesPoint>, from: Double, to: Double, shadeFrom: Double, shadeTo: Double, modifier: Modifier = Modifier) {
    val c = HL.colors
    InteractiveChart(listOf(ChartLine(hr.filter { it.t in from..to }, c.pulse, "Heart rate", "bpm", gap = 30.0)), from, to, modifier, height = 160.dp,
        spans = listOf(ChartSpan(shadeFrom, shadeTo, c.warn.copy(alpha = 0.14f), "During the block")),
        xLabel = { clock(it) }, readoutX = { "${clock(it)} into the capture" + if (it > shadeTo) ", ${clock(it - shadeTo)} after it ended" else "" }, minSpan = 30.0)
}
