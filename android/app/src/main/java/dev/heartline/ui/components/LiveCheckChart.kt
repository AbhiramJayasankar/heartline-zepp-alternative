package dev.heartline.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.heartline.analytics.LiveCheck
import dev.heartline.ui.theme.HL
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

private const val STRIP_S = 10.0

/**
 * The pulse of a running heart check, drawn like an ECG strip: the filtered light signal with every beat Heartline has
 * found (filled = used, hollow = set aside), stretches where the wrist moved shaded beneath it, and the wrist's
 * movement in its own lane under that. "Last 10 s" follows the newest second; "Whole check" shows everything so far.
 * Below it, the figures so far: heart rate, beats, clean time, RMSSD and movement now.
 */
@Composable
fun LiveCheckPanel(live: LiveCheck?, modifier: Modifier = Modifier) {
    val c = HL.colors
    var whole by rememberSaveable { mutableStateOf(false) }
    Panel(modifier, padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your pulse, live", style = HL.type.label, color = c.inkSoft, modifier = Modifier.weight(1f))
            Segmented(listOf(false to "Last 10 s", true to "Whole check"), whole, { whole = it })
        }
        Spacer(Modifier.height(10.dp))
        if (live == null || live.wave.size < 25) {
            Box(Modifier.fillMaxWidth().height(208.dp), contentAlignment = Alignment.Center) {
                MutedText("Waiting for the watch's pulse signal…")
            }
        } else {
            val from = if (whole) 0.0 else max(0.0, live.durationS - STRIP_S)
            val to = if (whole) max(live.durationS, STRIP_S) else max(live.durationS, STRIP_S)
            PulseStrip(live, from, to, Modifier.fillMaxWidth().height(168.dp))
            MotionLane(live, from, to, Modifier.fillMaxWidth().height(40.dp))
        }
        Spacer(Modifier.height(8.dp))
        Legend()
        Spacer(Modifier.height(14.dp))
        LiveStats(live)
    }
}

private val gutter = 46.dp
private val axisH = 16.dp

@Composable
private fun PulseStrip(live: LiveCheck, from: Double, to: Double, modifier: Modifier) {
    val c = HL.colors
    val tm = rememberTextMeasurer()
    val moving = movingSpans(live)
    val used = live.beatGood.count { it }
    Canvas(modifier.semantics { contentDescription = "Pulse from ${from.roundToInt()} to ${to.roundToInt()} seconds, ${live.beats} beats found, $used used" }) {
        val w = size.width - gutter.toPx(); val h = size.height - axisH.toPx()
        val span = to - from
        fun x(t: Double) = ((t - from) / span).toFloat() * w
        // Range from the shown samples, robust to one spike (1st to 99th percentile), padded.
        val fs = live.waveFs
        val i0 = (from * fs).toInt().coerceIn(0, live.wave.lastIndex); val i1 = (to * fs).toInt().coerceIn(i0 + 1, live.wave.size)
        val part = live.wave.copyOfRange(i0, i1).sorted()
        var lo = part[(part.size * .01).toInt()].toDouble(); var hi = part[((part.size - 1) * .99).toInt()].toDouble()
        if (hi - lo < 20) { val m = (hi + lo) / 2; lo = m - 10; hi = m + 10 }
        val pad = (hi - lo) * .12; lo -= pad; hi += pad
        fun y(v: Double) = (1 - ((v.coerceIn(lo, hi) - lo) / (hi - lo))).toFloat() * h

        // Moving stretches first, under everything.
        moving.forEach { (a, b) -> if (b > from && a < to) drawRect(c.warn.copy(alpha = .13f), Offset(x(max(a, from)), 0f), Size(x(min(b, to)) - x(max(a, from)), h)) }
        // ECG paper: minor lines every 0.2 s in the 10 s strip, major every second; on longer spans round steps.
        if (span <= STRIP_S + 1) {
            var t = ceil(from / .2) * .2
            while (t <= to + 1e-9) {
                val major = abs(t - t.roundToInt()) < 1e-6
                drawLine(if (major) c.gridMajor else c.gridMinor, Offset(x(t), 0f), Offset(x(t), h), if (major) 1.2f else 1f)
                t += .2
            }
        } else {
            val step = roundStep(span, 6)
            var t = ceil(from / (step / 5)) * (step / 5)
            while (t <= to + 1e-9) {
                val major = abs(t / step - (t / step).roundToInt()) < 1e-6
                drawLine(if (major) c.gridMajor else c.gridMinor, Offset(x(t), 0f), Offset(x(t), h), if (major) 1.2f else 1f)
                t += step / 5
            }
        }
        val yStep = roundStep(hi - lo, 4)
        var v = ceil(lo / yStep) * yStep
        while (v <= hi) {
            drawLine(if (abs(v) < 1e-9) c.gridMajor else c.gridMinor, Offset(0f, y(v)), Offset(w, y(v)), 1f)
            label(tm, signed(v), w + 6.dp.toPx(), y(v), c.inkSoft)
            v += yStep
        }
        label(tm, "counts", w + 6.dp.toPx(), 6.dp.toPx(), c.inkSoft)
        timeAxis(tm, from, to, w, size.height - axisH.toPx() / 2, c.inkSoft, ::x)

        // The trace. Dense spans draw each pixel column's low-to-high, so a 10-minute check stays a readable band.
        val perPx = (i1 - i0) / w
        if (perPx <= 2f) {
            val path = Path()
            for (i in i0 until i1) { val px = x(i / fs); val py = y(live.wave[i].toDouble()); if (i == i0) path.moveTo(px, py) else path.lineTo(px, py) }
            drawPath(path, c.ink, style = Stroke(1.6.dp.toPx(), join = StrokeJoin.Round))
        } else {
            var col = 0
            while (col < w) {
                val a = (i0 + col * perPx).toInt(); val b = min(i1, (i0 + (col + 1) * perPx).toInt() + 1)
                if (b > a) {
                    var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
                    for (i in a until b) { mn = min(mn, live.wave[i]); mx = max(mx, live.wave[i]) }
                    drawLine(c.ink, Offset(col.toFloat(), y(mx.toDouble())), Offset(col.toFloat(), y(mn.toDouble()) + 1f), 1f)
                }
                col++
            }
        }
        // Beats on the curve; on long spans smaller, so the trace still shows between them.
        val r = if (span <= STRIP_S + 1) 3.6.dp.toPx() else 2.dp.toPx()
        live.beatT.forEachIndexed { k, t ->
            if (t < from || t > to) return@forEachIndexed
            val f = t * fs; val j = f.toInt().coerceIn(0, live.wave.size - 2); val wgt = (f - j).toFloat().coerceIn(0f, 1f)
            val o = Offset(x(t), y((live.wave[j] * (1 - wgt) + live.wave[j + 1] * wgt).toDouble()))
            if (live.beatGood.getOrElse(k) { true }) drawCircle(c.pulse, r, o)
            else { drawCircle(c.surface, r + .5f, o); drawCircle(c.inkSoft, r, o, style = Stroke(1.5.dp.toPx())) }
        }
    }
}

/** Movement: the accelerometer's spread (g) over time, with the line above which beats are set aside. */
@Composable
private fun MotionLane(live: LiveCheck, from: Double, to: Double, modifier: Modifier) {
    val c = HL.colors
    val tm = rememberTextMeasurer()
    Canvas(modifier) {
        val w = size.width - gutter.toPx(); val top = 6.dp.toPx(); val h = size.height
        val span = to - from
        fun x(t: Double) = ((t - from) / span).toFloat() * w
        val shown = live.motionT.indices.filter { live.motionT[it] in from..to }
        val peak = shown.maxOfOrNull { live.motionG[it] }?.toDouble() ?: 0.0
        val hi = max(LiveCheck.MOVING_G * 2.5, peak * 1.1)
        fun y(g: Double) = top + (1 - (min(g, hi) / hi)).toFloat() * (h - top)
        drawLine(c.line, Offset(0f, h - .5f), Offset(w, h - .5f), 1f)
        drawLine(c.warn, Offset(0f, y(LiveCheck.MOVING_G)), Offset(w, y(LiveCheck.MOVING_G)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
        label(tm, "%.3f g".format(LiveCheck.MOVING_G), w + 6.dp.toPx(), y(LiveCheck.MOVING_G), c.warn)
        label(tm, "0", w + 6.dp.toPx(), h - 5.dp.toPx(), c.inkSoft)
        if (shown.size < 2) return@Canvas
        val path = Path()
        shown.forEachIndexed { n, i -> val px = x(live.motionT[i]); val py = y(live.motionG[i].toDouble()); if (n == 0) path.moveTo(px, py) else path.lineTo(px, py) }
        drawPath(path, c.inkSoft, style = Stroke(1.3.dp.toPx(), join = StrokeJoin.Round))
    }
}

@Composable
private fun Legend() {
    val c = HL.colors
    val style = HL.type.small.copy(fontSize = 12.sp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Dot(c.pulse, 7.dp); Text("beat used", style = style, color = c.inkSoft)
        Spacer(Modifier.width(6.dp))
        Canvas(Modifier.size(8.dp)) { drawCircle(c.inkSoft, size.minDimension / 2 - 1.dp.toPx(), style = Stroke(1.5.dp.toPx())) }
        Text("set aside", style = style, color = c.inkSoft)
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(12.dp, 9.dp).background(c.warn.copy(alpha = .22f)))
        Text("wrist moving", style = style, color = c.inkSoft)
    }
}

@Composable
private fun LiveStats(live: LiveCheck?) {
    val c = HL.colors
    val moving = (live?.motionNowG ?: 0.0) > LiveCheck.MOVING_G
    val items = listOf(
        Triple("Heart rate", live?.hrBpm?.roundToInt()?.toString() ?: "–", "bpm"),
        Triple("Beats used", live?.let { "${it.cleanBeats}" } ?: "–", live?.let { "of ${it.beats}" }),
        Triple("Clean time", live?.let { "${it.cleanS.roundToInt()}" } ?: "–", "s"),
        Triple("RMSSD", live?.rmssdMs?.roundToInt()?.toString() ?: "–", "ms"),
        Triple("Movement", live?.motionNowG?.let { "%.3f".format(it) } ?: "–", "g"),
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = if (maxWidth >= 520.dp) 5 else 3
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items.chunked(cols).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { (label, value, unit) ->
                        Stat(label, value, Modifier.weight(1f), unit = unit,
                            color = if (label == "Movement" && moving) c.warn else c.ink)
                    }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    MutedText(when {
        live == null -> "Figures appear once the watch has sent a few seconds of pulse."
        moving -> "Your wrist is moving: beats now are set aside. Rest your forearm and keep your hand still."
        live.rmssdMs == null -> "RMSSD appears after about 10 clean beats in a row."
        else -> "Heart rate is from the last 15 s; RMSSD, beats and clean time are for the whole check so far."
    })
}

/** Stretches where the motion level is over the line beats are set aside at. */
private fun movingSpans(live: LiveCheck): List<Pair<Double, Double>> {
    val out = ArrayList<Pair<Double, Double>>()
    var start = -1.0
    for (i in live.motionT.indices) {
        val m = live.motionG[i] > LiveCheck.MOVING_G
        if (m && start < 0) start = live.motionT[i] - .25
        if (!m && start >= 0) { out += start to live.motionT[i] - .25; start = -1.0 }
    }
    if (start >= 0) out += start to (live.motionT.lastOrNull() ?: start)
    return out
}

private fun DrawScope.timeAxis(tm: TextMeasurer, from: Double, to: Double, w: Float, y: Float, c: Color, x: (Double) -> Float) {
    val step = if (to - from <= STRIP_S + 1) 2.0 else roundStep(to - from, 5)
    var t = ceil(from / step) * step
    while (t <= to + 1e-9) {
        val px = x(t)
        if (px > 10f && px < w - 10f) label(tm, if (t >= 60 && step >= 30) "%d:%02d".format(t.toInt() / 60, t.toInt() % 60) else "${t.roundToInt()} s", px, y, c, center = true)
        t += step
    }
}

/** A step giving about [targets] round ticks (1, 2, 2.5, 5 × 10ⁿ) across [span]. */
private fun roundStep(span: Double, targets: Int): Double {
    val raw = span / targets
    val mag = 10.0.pow(floor(log10(raw)))
    return listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
}

private fun signed(v: Double) = when {
    abs(v) < 1e-9 -> "0"
    v > 0 -> "+${v.roundToInt()}"
    else -> "−${(-v).roundToInt()}"
}
