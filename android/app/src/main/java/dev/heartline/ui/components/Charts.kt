package dev.heartline.ui.components

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

private val axisStyle = TextStyle(fontFamily = TextFamily, fontSize = 10.sp)
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
    val tm = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(200.dp)) {
        val padR = 34.dp.toPx(); val padB = 18.dp.toPx(); val padT = 10.dp.toPx()
        val t0 = nowS - windowS
        val pts = points.filter { it.ts >= t0 && it.bpm > 0 }
        val lo = if (pts.isEmpty()) 50.0 else max(30.0, floor((pts.minOf { it.bpm } - 8) / 10.0) * 10)
        val hi = if (pts.isEmpty()) 110.0 else min(220.0, ceil((pts.maxOf { it.bpm } + 8) / 10.0) * 10)
        val w = size.width - padR; val h = size.height - padB - padT
        fun x(t: Long) = ((t - t0).toFloat() / windowS) * w
        fun y(v: Double) = padT + (1 - ((v - lo) / (hi - lo)).toFloat()) * h
        val step = if (hi - lo > 60) 20 else 10
        var v = lo
        while (v <= hi) {
            drawLine(c.gridMajor, Offset(0f, y(v)), Offset(w, y(v)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
            label(tm, v.toInt().toString(), w + 6.dp.toPx(), y(v), c.inkSoft)
            v += step
        }
        for (i in 0..4) {
            val t = t0 + windowS * i / 4
            label(tm, if (i == 4) "now" else clock(t), x(t), size.height - padB / 2, c.inkSoft, alignEnd = i == 4, center = i in 1..3)
        }
        if (pts.isNotEmpty()) {
            val path = Path()
            pts.forEachIndexed { i, p ->
                val gap = i == 0 || p.ts - pts[i - 1].ts > 180
                if (gap) path.moveTo(x(p.ts), y(p.bpm.toDouble())) else path.lineTo(x(p.ts), y(p.bpm.toDouble()))
            }
            drawPath(path, c.ink, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            val last = pts.last()
            drawCircle(c.pulse, 5.dp.toPx(), Offset(x(last.ts), y(last.bpm.toDouble())))
        }
    }
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
            val path = Path()
            pts.forEachIndexed { k, (i, v) -> if (k == 0 || i - pts[k - 1].first > 1) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
            drawPath(path, color, style = Stroke(1.8.dp.toPx(), join = StrokeJoin.Round))
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
@Composable
fun Tachogram(rr: List<RrUi>, modifier: Modifier = Modifier) {
    val c = HL.colors
    val tm = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(170.dp)) {
        if (rr.size < 2) return@Canvas
        val padR = 46.dp.toPx(); val padB = 16.dp.toPx()
        val lo = rr.minOf { it.rrMs } - 30; val hi = rr.maxOf { it.rrMs } + 30
        val tMax = rr.last().t.coerceAtLeast(1.0)
        val w = size.width - padR; val h = size.height - padB
        fun x(t: Double) = (t / tMax).toFloat() * w
        fun y(v: Double) = (1 - ((v - lo) / (hi - lo)).toFloat()) * h
        val path = Path()
        rr.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.t), y(p.rrMs)) else path.lineTo(x(p.t), y(p.rrMs)) }
        drawPath(path, c.ink, style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round))
        rr.forEach { p -> drawCircle(if (p.ectopic) c.pulse else c.ink, if (p.ectopic) 4.dp.toPx() else 2.dp.toPx(), Offset(x(p.t), y(p.rrMs))) }
        listOf(lo + 30, (lo + hi) / 2, hi - 30).forEach { v -> label(tm, "${v.toInt()} ms", w + 6.dp.toPx(), y(v), c.inkSoft) }
        label(tm, "0 s", 0f, size.height - padB / 2, c.inkSoft)
        label(tm, "${tMax.toInt()} s", w, size.height - padB / 2, c.inkSoft, alignEnd = true)
    }
}

/** Each interval against the next. A tight cigar along the diagonal is a steady rhythm; a cloud is irregular. */
@Composable
fun PoincarePlot(rr: List<RrUi>, modifier: Modifier = Modifier) {
    val c = HL.colors
    val tm = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().aspectRatio(1f)) {
        if (rr.size < 3) return@Canvas
        val lo = rr.minOf { it.rrMs } - 40; val hi = rr.maxOf { it.rrMs } + 40
        val pad = 24.dp.toPx(); val s = size.width - pad * 1.4f
        fun p(v: Double) = pad + ((v - lo) / (hi - lo)).toFloat() * s
        drawLine(c.gridMajor, Offset(p(lo), size.height - p(lo)), Offset(p(hi), size.height - p(hi)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f)))
        for (i in 0 until rr.size - 1) {
            val ect = rr[i].ectopic || rr[i + 1].ectopic
            drawCircle(if (ect) c.pulse else c.ink.copy(alpha = 0.55f), if (ect) 4.dp.toPx() else 3.dp.toPx(), Offset(p(rr[i].rrMs), size.height - p(rr[i + 1].rrMs)))
        }
        label(tm, "interval n, ms", size.width / 2, size.height - 6.dp.toPx(), c.inkSoft, center = true)
        label(tm, "${(lo + 40).toInt()}", pad, size.height - 18.dp.toPx(), c.inkSoft)
        label(tm, "${(hi - 40).toInt()}", p(hi - 40), size.height - 18.dp.toPx(), c.inkSoft, alignEnd = true)
    }
}

/** The last 15 s of the filtered pulse with each detected beat marked. */
@Composable
fun BeatsPlot(preview: FloatArray, fs: Double, beatTimes: DoubleArray, modifier: Modifier = Modifier) {
    val c = HL.colors
    Canvas(modifier.fillMaxWidth().height(120.dp)) {
        if (preview.size < 2 || fs <= 0) return@Canvas
        val dur = preview.size / fs
        val show = min(15.0, dur)
        val from = max(0, (preview.size - show * fs).toInt())
        val part = preview.copyOfRange(from, preview.size)
        val t0 = from / fs
        val lo = part.min(); val hi = part.max().coerceAtLeast(lo + 1e-3f)
        fun x(t: Double) = ((t - t0) / show).toFloat() * size.width
        fun y(v: Float) = 6f + (1 - (v - lo) / (hi - lo)) * (size.height - 12f)
        val path = Path()
        part.forEachIndexed { i, v -> val px = x(t0 + i / fs); if (i == 0) path.moveTo(px, y(v)) else path.lineTo(px, y(v)) }
        drawPath(path, c.ink, style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round))
        beatTimes.filter { it >= t0 }.forEach { bt ->
            val i = min(preview.size - 1, (bt * fs).toInt())
            drawCircle(c.pulse, 3.dp.toPx(), Offset(x(bt), y(preview[i])))
        }
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
