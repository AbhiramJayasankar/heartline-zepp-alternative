package dev.heartline.ui.components

import androidx.compose.material3.Text
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.heartline.app.HrPoint
import dev.heartline.app.SeriesPoint
import dev.heartline.app.MinuteState
import dev.heartline.app.MinuteUi
import dev.heartline.app.RrUi
import dev.heartline.app.SatUi
import dev.heartline.app.SleepNightUi
import dev.heartline.app.TrendSeries
import dev.heartline.ui.theme.HL
import dev.heartline.ui.theme.Palette
import dev.heartline.ui.theme.Text as TextFamily
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val axisStyle = TextStyle(fontFamily = TextFamily, fontSize = 10.sp)
private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

internal fun DrawScope.label(tm: TextMeasurer, text: String, x: Float, y: Float, color: Color, alignEnd: Boolean = false, center: Boolean = false) {
    val r = tm.measure(text, axisStyle.copy(color = color))
    val dx = when {
        alignEnd -> -r.size.width.toFloat()
        center -> -r.size.width / 2f
        else -> 0f
    }
    drawText(r, topLeft = Offset(x + dx, y - r.size.height / 2f))
}

private fun clock(ts: Long): String = hhmm.format(Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()))

/** Heart rate over the last [windowS] seconds as an ink line on paper, gaps over 3 minutes left open. */
@Composable
fun HrTraceChart(points: List<HrPoint>, windowS: Long, nowS: Long, modifier: Modifier = Modifier) {
    val c = HL.colors
    // The window end moves once a minute, so a touched reading stays selected while live values arrive.
    val end = (nowS / 60 + 1) * 60.0
    val start = end - windowS
    val pts = points.filter { it.ts >= start && it.bpm > 0 }.map { SeriesPoint(it.ts.toDouble(), it.bpm.toDouble()) }
    InteractiveChart(listOf(ChartLine(pts, c.pulse, "Heart rate", "bpm", gap = 180.0, spacing = 60.0)), start, end, modifier, height = 200.dp, minSpan = 60.0,
        idleHint = if (pts.isEmpty()) "No readings in this window yet." else "Touch for the reading at a moment. Pinch to zoom.")
}

/** A scrolling signal, newest at the right edge, auto-scaled to its own range. */
@Composable
fun SignalCanvas(series: List<FloatArray>, colors: List<Color>, modifier: Modifier = Modifier, symmetric: Boolean = false, minSpan: Float = 0f) {
    Canvas(modifier) {
        val all = series.filter { it.size > 1 }
        if (all.isEmpty()) return@Canvas
        var lo = all.minOf { a -> a.min() }
        var hi = all.maxOf { a -> a.max() }
        if (symmetric) { val m = max(abs(lo), abs(hi)); lo = -m; hi = m }
        if (hi - lo < minSpan) { val mid = (hi + lo) / 2; lo = mid - minSpan / 2; hi = mid + minSpan / 2 }
        val pad = (hi - lo) * 0.1f + 1e-6f
        lo -= pad; hi += pad
        all.forEachIndexed { k, a ->
            val path = Path()
            val n = a.size
            for (i in 0 until n) {
                val x = size.width * i / (n - 1).toFloat()
                val y = size.height * (1 - (a[i] - lo) / (hi - lo))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, colors[k % colors.size], style = Stroke(if (k == 0 && all.size == 1) 2.2.dp.toPx() else 1.5.dp.toPx(), join = StrokeJoin.Round))
        }
    }
}

/** Removes the slow PPG drift (1 s moving average) and flips it so each heartbeat points up. */
fun detrendPulse(values: FloatArray, rate: Int = 25): FloatArray {
    if (values.size < 2) return values
    val out = FloatArray(values.size)
    var sum = 0.0
    for (i in values.indices) {
        sum += values[i]
        if (i >= rate) sum -= values[i - rate]
        val n = min(i + 1, rate)
        out[i] = (sum / n - values[i]).toFloat()
    }
    return out.copyOfRange(min(rate, out.size / 2), out.size)
}

/** Fourteen-day small multiple: bars or a dotted line, optional dashed baseline. */
@Composable
fun MiniTrend(series: TrendSeries, modifier: Modifier = Modifier) {
    val c = HL.colors
    val color = if (series.accent) c.pulse else c.ink
    Canvas(modifier.fillMaxWidth().height(64.dp)) {
        val pts = series.points.mapIndexedNotNull { i, p -> p.value?.let { i to it } }
        if (pts.isEmpty()) return@Canvas
        val values = pts.map { it.second } + listOfNotNull(series.baseline)
        var lo = values.min(); var hi = values.max()
        if (series.bars) lo = 0.0
        if (hi == lo) { hi += 1; lo -= if (series.bars) 0.0 else 1.0 }
        val slots = series.points.size.coerceAtLeast(1)
        val stepX = size.width / slots
        fun x(i: Int) = stepX * i + stepX / 2
        fun y(v: Double) = 4f + (1 - ((v - lo) / (hi - lo)).toFloat()) * (size.height - 8f)
        series.baseline?.let { b -> drawLine(c.inkSoft, Offset(0f, y(b)), Offset(size.width, y(b)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))) }
        if (series.bars) {
            val bw = max(3f, stepX * 0.6f)
            pts.forEach { (i, v) -> drawRoundRect(color.copy(alpha = 0.8f), Offset(x(i) - bw / 2, y(v)), Size(bw, max(1f, y(lo) - y(v))), CornerRadius(2f)) }
        } else {
            // Measured days solid; days with no reading bridged with a faded dotted line.
            val path = Path(); val bridge = Path()
            pts.forEachIndexed { k, (i, v) ->
                when {
                    k == 0 -> path.moveTo(x(i), y(v))
                    i - pts[k - 1].first > 1 -> { bridge.moveTo(x(pts[k - 1].first), y(pts[k - 1].second)); bridge.lineTo(x(i), y(v)); path.moveTo(x(i), y(v)) }
                    else -> path.lineTo(x(i), y(v))
                }
            }
            drawPath(path, color, style = Stroke(1.8.dp.toPx(), join = StrokeJoin.Round))
            drawPath(bridge, color.copy(alpha = 0.45f), style = Stroke(1.3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 5f)), cap = StrokeCap.Round))
            pts.forEach { (i, v) -> drawCircle(color, 2.4.dp.toPx(), Offset(x(i), y(v))) }
        }
    }
}

fun stateColor(s: MinuteState, p: Palette): Color? = when (s) {
    MinuteState.SLEEP_DEEP -> p.sleepDeep
    MinuteState.SLEEP_LIGHT -> p.sleepLight
    MinuteState.SLEEP_REM -> p.sleepRem
    MinuteState.ACTIVE -> p.pulse
    MinuteState.IDLE -> p.idle
    MinuteState.CHARGING -> p.warn
    else -> null
}

/** One day minute by minute: state band on top, steps as bars, heart rate as a line. */
@Composable
fun DayTimeline(minutes: List<MinuteUi>, dayStart: Long, modifier: Modifier = Modifier) {
    val c = HL.colors
    val tm = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(190.dp)) {
        val padR = 30.dp.toPx(); val padB = 18.dp.toPx(); val band = 22.dp.toPx()
        val w = size.width - padR
        val plotTop = band + 10.dp.toPx(); val plotH = size.height - plotTop - padB
        fun x(ts: Long) = ((ts - dayStart) / 86400f) * w
        val mw = max(1f, w / 1440f)
        for (hour in 0..24 step 6) {
            val px = w * hour / 24f
            drawLine(c.line, Offset(px, 0f), Offset(px, size.height - padB), 1f)
            label(tm, if (hour == 24) "24" else "%02d".format(hour), px, size.height - padB / 2, c.inkSoft, alignEnd = hour == 24, center = hour in 1..23)
        }
        minutes.forEach { m ->
            val col = stateColor(m.state, c)
            if (col != null) drawRect(col, Offset(x(m.ts), 0f), Size(mw + 0.5f, band))
            else if (m.state == MinuteState.NOT_WORN) {
                val px = x(m.ts)
                if (((px / 4).toInt()) % 2 == 0) drawRect(c.line, Offset(px, 0f), Size(mw + 0.5f, band))
            }
        }
        val maxSteps = max(30, minutes.maxOfOrNull { it.steps } ?: 0)
        minutes.forEach { m ->
            if (m.steps > 0) {
                val bh = m.steps / maxSteps.toFloat() * plotH * 0.9f
                drawRect(c.pulse.copy(alpha = 0.5f), Offset(x(m.ts), plotTop + plotH - bh), Size(mw + 0.5f, bh))
            }
        }
        val hrs = minutes.filter { it.hr != null }
        if (hrs.isNotEmpty()) {
            val lo = max(30, hrs.minOf { it.hr!! } - 5).toFloat(); val hi = (hrs.maxOf { it.hr!! } + 5).toFloat()
            fun y(v: Int) = plotTop + (1 - (v - lo) / (hi - lo)) * plotH
            val path = Path()
            hrs.forEachIndexed { i, m -> if (i == 0 || m.ts - hrs[i - 1].ts > 600) path.moveTo(x(m.ts), y(m.hr!!)) else path.lineTo(x(m.ts), y(m.hr!!)) }
            drawPath(path, c.ink, style = Stroke(1.4.dp.toPx(), join = StrokeJoin.Round))
            label(tm, hi.toInt().toString(), w + 5.dp.toPx(), plotTop, c.inkSoft)
            label(tm, lo.toInt().toString(), w + 5.dp.toPx(), plotTop + plotH, c.inkSoft)
        }
    }
}

/** Beat-to-beat intervals over the recording; extra beats in red. */
/** Round axis values every [step] from [lo] to [hi]. */
private fun ticksEvery(lo: Double, hi: Double, step: Double): List<Double> {
    val out = ArrayList<Double>(); var v = kotlin.math.ceil(lo / step) * step
    while (v <= hi + 1e-9) { out.add(v); v += step }
    return out
}
/** A step giving 3 to 6 round ticks across [span]. */
private fun niceStep(span: Double, targets: Int = 4): Double {
    val raw = span / targets
    val mag = Math.pow(10.0, Math.floor(Math.log10(raw)))
    return listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
}
/** The range most gaps fall in (2nd to 98th percentile, padded), so one bad gap doesn't squash the chart. */
private fun rrRange(rr: List<RrUi>): Pair<Double, Double> {
    val v = rr.map { it.rrMs }.sorted()
    val lo = v[(v.size * 0.02).toInt()]; val hi = v[((v.size - 1) * 0.98).toInt()]
    val pad = max(20.0, (hi - lo) * 0.12)
    return (lo - pad) to (hi + pad)
}
/** A gap more than 30% off the median is one Heartline set aside (a missed beat or movement). */
private fun outlier(v: Double, median: Double) = kotlin.math.abs(v - median) / median > 0.30

/**
 * Every gap between beats through the check. Gridlines sit at the labelled values. Gaps Heartline set aside are hollow,
 * and any outside the chart's range are pinned to its edge with an arrow-like mark, so they don't squash the rest.
 */
@Composable
fun Tachogram(rr: List<RrUi>, modifier: Modifier = Modifier) {
    val c = HL.colors
    val tm = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(180.dp)) {
        if (rr.size < 2) return@Canvas
        val padR = 46.dp.toPx(); val padB = 16.dp.toPx(); val padT = 6.dp.toPx()
        val (lo, hi) = rrRange(rr)
        val med = rr.map { it.rrMs }.sorted()[rr.size / 2]
        val tMax = rr.last().t.coerceAtLeast(1.0)
        val w = size.width - padR; val h = size.height - padB
        fun x(t: Double) = (t / tMax).toFloat() * w
        fun y(v: Double) = padT + (1 - ((v.coerceIn(lo, hi) - lo) / (hi - lo)).toFloat()) * (h - padT)
        ticksEvery(lo, hi, niceStep(hi - lo)).forEach { v ->
            drawLine(c.line, Offset(0f, y(v)), Offset(w, y(v)), 1f)
            label(tm, "${v.toInt()} ms", w + 6.dp.toPx(), y(v) - 6.dp.toPx(), c.inkSoft)
        }
        ticksEvery(0.0, tMax, niceStep(tMax, 5)).forEach { t ->
            drawLine(c.line.copy(alpha = 0.7f), Offset(x(t), padT), Offset(x(t), h), 1f)
            label(tm, "${t.toInt()} s", x(t), size.height - padB / 2, c.inkSoft, center = t > 0 && x(t) < w - 20f)
        }
        // The line joins clean gaps only; set-aside ones break it.
        val path = Path(); var open = false
        rr.forEach { p ->
            val bad = p.ectopic || outlier(p.rrMs, med)
            if (bad) { open = false; return@forEach }
            if (!open) { path.moveTo(x(p.t), y(p.rrMs)); open = true } else path.lineTo(x(p.t), y(p.rrMs))
        }
        drawPath(path, c.ink, style = Stroke(1.3.dp.toPx(), join = StrokeJoin.Round))
        rr.forEach { p ->
            val o = Offset(x(p.t), y(p.rrMs))
            when {
                p.ectopic -> drawCircle(c.pulse, 4.dp.toPx(), o)
                outlier(p.rrMs, med) -> drawCircle(c.inkSoft, 4.dp.toPx(), o, style = Stroke(1.5.dp.toPx()))
                else -> drawCircle(c.ink, 1.8.dp.toPx(), o)
            }
        }
    }
}

/** Each gap against the next. A tight cigar along the diagonal is a steady rhythm; a cloud is irregular. */
@Composable
fun PoincarePlot(rr: List<RrUi>, modifier: Modifier = Modifier) {
    val c = HL.colors
    val tm = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().aspectRatio(1f)) {
        if (rr.size < 3) return@Canvas
        val (lo, hi) = rrRange(rr)
        val med = rr.map { it.rrMs }.sorted()[rr.size / 2]
        val padL = 40.dp.toPx(); val padB = 30.dp.toPx(); val padT = 22.dp.toPx(); val padR = 6.dp.toPx()
        val side = min(size.width - padL - padR, size.height - padB - padT)
        fun px(v: Double) = padL + ((v.coerceIn(lo, hi) - lo) / (hi - lo)).toFloat() * side
        fun py(v: Double) = padT + side - ((v.coerceIn(lo, hi) - lo) / (hi - lo)).toFloat() * side
        ticksEvery(lo, hi, niceStep(hi - lo)).forEach { v ->
            drawLine(c.line, Offset(px(v), padT), Offset(px(v), padT + side), 1f)
            drawLine(c.line, Offset(padL, py(v)), Offset(padL + side, py(v)), 1f)
            label(tm, "${v.toInt()}", px(v), padT + side + 8.dp.toPx(), c.inkSoft, center = true)
            label(tm, "${v.toInt()}", padL - 4.dp.toPx(), py(v) - 6.dp.toPx(), c.inkSoft, alignEnd = true)
        }
        drawLine(c.gridMajor, Offset(px(lo), py(lo)), Offset(px(hi), py(hi)), 1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f)))
        for (i in 0 until rr.size - 1) {
            val a = rr[i]; val b = rr[i + 1]
            val o = Offset(px(a.rrMs), py(b.rrMs))
            when {
                a.ectopic || b.ectopic -> drawCircle(c.pulse, 4.dp.toPx(), o)
                outlier(a.rrMs, med) || outlier(b.rrMs, med) -> drawCircle(c.inkSoft, 3.5.dp.toPx(), o, style = Stroke(1.4.dp.toPx()))
                else -> drawCircle(c.ink.copy(alpha = 0.55f), 2.6.dp.toPx(), o)
            }
        }
        label(tm, "this gap, ms", padL + side / 2, size.height - 8.dp.toPx(), c.inkSoft, center = true)
        label(tm, "next gap, ms", 0f, 0f, c.inkSoft)
    }
}

/** The last 15 s of the filtered pulse with each detected beat marked. */
/**
 * The filtered pulse with every beat Heartline found: filled dots were used, hollow ones were rejected (odd shape or
 * size, or the wrist was moving). Shows 15 s at a time; drag sideways to move through the whole recording.
 */
@Composable
fun BeatsPlot(preview: FloatArray, fs: Double, beatTimes: DoubleArray, modifier: Modifier = Modifier, beatGood: BooleanArray = BooleanArray(0)) {
    val c = HL.colors
    val tm = rememberTextMeasurer()
    if (preview.size < 2 || fs <= 0) return
    val dur = preview.size / fs
    val show = min(15.0, dur)
    var from by remember(preview) { mutableStateOf(max(0.0, dur - show)) }
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(140.dp).pointerInput(preview) {
            detectHorizontalDragGestures { ch, drag -> from = (from - drag / size.width * show).coerceIn(0.0, max(0.0, dur - show)); ch.consume() }
        }) {
            val axisH = 16.dp.toPx()
            val i0 = (from * fs).toInt().coerceIn(0, preview.size - 1)
            val i1 = ((from + show) * fs).toInt().coerceIn(i0 + 1, preview.size)
            val part = preview.copyOfRange(i0, i1)
            val lo = part.min(); val hi = part.max().coerceAtLeast(lo + 1e-3f)
            fun x(t: Double) = ((t - from) / show).toFloat() * size.width
            fun y(v: Float) = 6f + (1 - (v - lo) / (hi - lo)) * (size.height - axisH - 12f)
            // A gridline at every whole second (labelled every 5), the only grid on this chart.
            var sec = kotlin.math.ceil(from)
            while (sec <= from + show) {
                val px = x(sec)
                drawLine(c.line.copy(alpha = if (sec.toInt() % 5 == 0) 0.9f else 0.4f), Offset(px, 0f), Offset(px, size.height - axisH), 1f)
                if (sec.toInt() % 5 == 0) label(tm, "${sec.toInt()} s", px, size.height - axisH / 2, c.inkSoft, center = true)
                sec += 1.0
            }
            val path = Path()
            part.forEachIndexed { k, v -> val px = x((i0 + k) / fs); if (k == 0) path.moveTo(px, y(v)) else path.lineTo(px, y(v)) }
            drawPath(path, c.ink, style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round))
            beatTimes.forEachIndexed { b, bt ->
                if (bt < from || bt > from + show) return@forEachIndexed
                // The wave's value at the beat's exact time, so the dot sits on the curve.
                val f = bt * fs; val k = f.toInt().coerceIn(0, preview.size - 2); val w = (f - k).toFloat().coerceIn(0f, 1f)
                val v = preview[k] * (1 - w) + preview[k + 1] * w
                val good = beatGood.getOrNull(b) ?: true
                if (good) drawCircle(c.pulse, 3.5.dp.toPx(), Offset(x(bt), y(v)))
                else drawCircle(c.inkSoft, 4.dp.toPx(), Offset(x(bt), y(v)), style = Stroke(1.6.dp.toPx()))
            }
        }
        val used = if (beatGood.isEmpty()) beatTimes.size else beatGood.count { it }
        Text("${"%.0f".format(from)}–${"%.0f".format(from + show)} s of ${"%.0f".format(dur)} s" + if (dur > show) " · drag to move" else "",
            style = HL.type.small.copy(fontSize = 12.sp), color = c.inkSoft)
        if (beatGood.isNotEmpty()) Text("$used of ${beatTimes.size} beats used (filled); ${beatTimes.size - used} rejected (hollow) for an odd shape or size, or movement.",
            style = HL.type.small.copy(fontSize = 12.sp), color = c.inkSoft)
    }
}

@Composable
fun CompassDial(headingDeg: Double?, modifier: Modifier = Modifier) {
    val c = HL.colors
    val tm = rememberTextMeasurer()
    Canvas(modifier.aspectRatio(1f)) {
        val r = size.minDimension / 2 * 0.92f
        val ctr = Offset(size.width / 2, size.height / 2)
        drawCircle(c.gridMajor, r, ctr, style = Stroke(1.5.dp.toPx()))
        for (d in 0 until 360 step 30) {
            val rad = Math.toRadians(d.toDouble())
            val inner = if (d % 90 == 0) r * 0.86f else r * 0.92f
            drawLine(c.inkSoft, Offset(ctr.x + r * sin(rad).toFloat(), ctr.y - r * cos(rad).toFloat()), Offset(ctr.x + inner * sin(rad).toFloat(), ctr.y - inner * cos(rad).toFloat()), 1f)
        }
        listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0).forEach { (l, d) ->
            val rad = Math.toRadians(d)
            val rr = tm.measure(l, axisStyle.copy(fontSize = 12.sp, color = if (l == "N") c.pulse else c.inkSoft))
            drawText(rr, topLeft = Offset(ctr.x + r * 0.7f * sin(rad).toFloat() - rr.size.width / 2, ctr.y - r * 0.7f * cos(rad).toFloat() - rr.size.height / 2))
        }
        if (headingDeg != null) {
            rotate(headingDeg.toFloat(), ctr) {
                val needle = Path().apply { moveTo(ctr.x, ctr.y - r * 0.8f); lineTo(ctr.x + r * 0.08f, ctr.y); lineTo(ctr.x - r * 0.08f, ctr.y); close() }
                val tail = Path().apply { moveTo(ctr.x, ctr.y + r * 0.8f); lineTo(ctr.x + r * 0.08f, ctr.y); lineTo(ctr.x - r * 0.08f, ctr.y); close() }
                drawPath(needle, c.pulse)
                drawPath(tail, c.inkSoft)
            }
        }
    }
}

/** Satellites by direction (around) and height (centre is overhead, edge is the horizon). */
@Composable
fun SkyPlot(sats: List<SatUi>, modifier: Modifier = Modifier) {
    val c = HL.colors
    val tm = rememberTextMeasurer()
    Canvas(modifier.aspectRatio(1f)) {
        val r = size.minDimension / 2 * 0.86f
        val ctr = Offset(size.width / 2, size.height / 2)
        listOf(0.0, 30.0, 60.0).forEach { el -> drawCircle(c.gridMajor, ((90 - el) / 90).toFloat() * r, ctr, style = Stroke(1f)) }
        drawLine(c.gridMajor, Offset(ctr.x - r, ctr.y), Offset(ctr.x + r, ctr.y), 1f)
        drawLine(c.gridMajor, Offset(ctr.x, ctr.y - r), Offset(ctr.x, ctr.y + r), 1f)
        label(tm, "N", ctr.x, ctr.y - r - 8.dp.toPx(), c.pulse, center = true)
        sats.forEach { s ->
            val rr = ((90 - s.elev) / 90).toFloat() * r
            val rad = Math.toRadians(s.az)
            val p = Offset(ctr.x + rr * sin(rad).toFloat(), ctr.y - rr * cos(rad).toFloat())
            val good = (s.snr ?: 0.0) >= 25
            val weak = s.snr != null && !good
            drawCircle(if (good) c.ok else if (weak) c.sleepLight else c.surface, 5.dp.toPx(), p)
            drawCircle(if (good) c.ok else c.ink, 5.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
            if (s.snr != null) label(tm, s.id, p.x, p.y - 11.dp.toPx(), c.inkSoft, center = true)
        }
    }
}

/** Live level history in dB below full scale (-70..0). */
@Composable
fun LevelHistory(db: FloatArray, modifier: Modifier = Modifier) {
    val c = HL.colors
    Canvas(modifier.fillMaxWidth().height(70.dp)) {
        if (db.size < 2) return@Canvas
        val path = Path()
        db.forEachIndexed { i, v ->
            val x = size.width * i / (db.size - 1).toFloat()
            val y = size.height * (1 - ((v.coerceIn(-70f, 0f) + 70f) / 70f))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, c.ink, style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round))
    }
}

@Composable
fun StagesBar(night: SleepNightUi, modifier: Modifier = Modifier) {
    val c = HL.colors
    val parts = listOf(night.deepMin to c.sleepDeep, night.lightMin to c.sleepLight, night.remMin to c.sleepRem, night.awakeMin to c.idle)
    val total = parts.sumOf { it.first }.coerceAtLeast(1)
    Canvas(modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
        var x = 0f
        parts.forEach { (m, col) ->
            val w = size.width * m / total
            drawRect(col, Offset(x, 0f), Size(w, size.height))
            x += w
        }
    }
}

@Composable
fun LevelBar(fraction: Float, loud: Boolean, modifier: Modifier = Modifier) {
    val c = HL.colors
    Box(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) {
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            drawRect(c.gridMinor)
            drawRect(if (loud) c.pulse else c.ok, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height))
        }
    }
}

/** Heart rate estimated from the live waveform: median gap between clear peaks over the last 8 s. */
fun estimateBpm(signal: FloatArray, rate: Int = 25): Int? {
    if (signal.size < rate * 4) return null
    val s = signal.copyOfRange(max(0, signal.size - rate * 8), signal.size)
    val mean = s.average()
    val sd = kotlin.math.sqrt(s.sumOf { (it - mean) * (it - mean) } / s.size)
    val peaks = ArrayList<Int>()
    for (i in 2 until s.size - 2) {
        val v = s[i]
        if (v > mean + 0.3 * sd && v >= s[i - 1] && v >= s[i - 2] && v > s[i + 1] && v > s[i + 2] && (peaks.isEmpty() || i - peaks.last() >= rate * 0.33)) peaks += i
    }
    if (peaks.size < 3) return null
    val gaps = peaks.zipWithNext { a, b -> b - a }.sorted()
    val bpm = (60.0 * rate / gaps[gaps.size / 2]).toInt()
    return bpm.takeIf { it in 35..200 }
}

@Composable
fun rememberDayStart(epochDay: Long): Long = remember(epochDay) { java.time.LocalDate.ofEpochDay(epochDay).atStartOfDay(ZoneId.systemDefault()).toEpochSecond() }
