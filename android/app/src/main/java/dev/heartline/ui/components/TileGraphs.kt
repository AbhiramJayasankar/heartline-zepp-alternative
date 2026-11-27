package dev.heartline.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.heartline.app.SeriesPoint
import dev.heartline.ui.theme.HL
import kotlin.math.max
import kotlin.math.min

/** The height of the small graph in each Today tile, so the tiles in a row line up. */
val TILE_GRAPH_HEIGHT = 52.dp

/** [points] as means over [stepS]-second buckets, so a tile's line shows the shape of the day, not each minute's jitter. */
fun bucketed(points: List<SeriesPoint>, stepS: Double): List<SeriesPoint> =
    points.filter { it.v.isFinite() }.groupBy { (it.t / stepS).toLong() }.toSortedMap()
        .map { (k, v) -> SeriesPoint(k * stepS + stepS / 2, v.sumOf { it.v } / v.size) }

/**
 * A small line for a tile: [points] (epoch seconds) between [x0] and [x1], with a soft fill fading down under it and a
 * dot on the latest reading. Readings more than [gap] seconds apart aren't joined. No axes or grid: the tile's number
 * says the value, the line says the shape of the day.
 */
@Composable
fun TileLine(points: List<SeriesPoint>, x0: Double, x1: Double, color: Color, modifier: Modifier = Modifier, height: Dp = TILE_GRAPH_HEIGHT,
             gap: Double = 3600.0, description: String = "") {
    val c = HL.colors
    val pts = remember(points, x0, x1) { points.filter { it.t in x0..x1 && it.v.isFinite() }.sortedBy { it.t } }
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        if (pts.size < 2) return@Canvas
        val lo = pts.minOf { it.v }; val hi = pts.maxOf { it.v }
        val span = max(hi - lo, 1e-6)
        val pad = 4.dp.toPx()
        fun x(t: Double) = ((t - x0) / max(1.0, x1 - x0) * size.width).toFloat()
        fun y(v: Double) = (pad + (1 - (v - lo) / span) * (size.height - 2 * pad)).toFloat()
        // One path per joined run, each with its own fill.
        val runs = ArrayList<MutableList<SeriesPoint>>()
        pts.forEach { p -> if (runs.isEmpty() || p.t - runs.last().last().t > gap) runs.add(mutableListOf(p)) else runs.last().add(p) }
        runs.filter { it.size >= 2 }.forEach { r ->
            val line = Path().apply {
                moveTo(x(r[0].t), y(r[0].v))
                // Smooth between readings: a curve through the midpoints.
                for (i in 1 until r.size) {
                    val px = x(r[i - 1].t); val py = y(r[i - 1].v); val cx = x(r[i].t); val cyy = y(r[i].v)
                    quadraticTo(px, py, (px + cx) / 2, (py + cyy) / 2)
                }
                lineTo(x(r.last().t), y(r.last().v))
            }
            val area = Path().apply { addPath(line); lineTo(x(r.last().t), size.height); lineTo(x(r[0].t), size.height); close() }
            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f)), 0f, size.height))
            drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        val last = pts.last()
        drawCircle(c.surface, 4.5.dp.toPx(), Offset(x(last.t), y(last.v)))
        drawCircle(color, 3.dp.toPx(), Offset(x(last.t), y(last.v)))
    }
}

/**
 * Small bars for a tile, one per slot (the hours of the day): [values] null or 0 where there's nothing, shown as a
 * faint dot so the day's timeline still reads; [colorOf] colours each bar by its value. Slots from [current] on are
 * still to come and stay empty.
 */
@Composable
fun TileBars(values: List<Double?>, colorOf: (Double) -> Color, modifier: Modifier = Modifier, height: Dp = TILE_GRAPH_HEIGHT,
             current: Int? = null, top: Double? = null, description: String = "") {
    val c = HL.colors
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        if (values.isEmpty()) return@Canvas
        val hi = top ?: values.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val slot = size.width / values.size
        val w = max(2.dp.toPx(), slot * 0.62f)
        val r = min(w / 2, 3.dp.toPx())
        values.forEachIndexed { i, v ->
            val cx = slot * (i + 0.5f)
            if (v == null || v <= 0) {
                if (current == null || i <= current) drawCircle(c.line, 1.3.dp.toPx(), Offset(cx, size.height - 1.3.dp.toPx()))
                return@forEachIndexed
            }
            val h = max(w, (v / hi).toFloat().coerceIn(0f, 1f) * size.height)
            val col = colorOf(v)
            drawRoundRect(Brush.verticalGradient(listOf(col, col.copy(alpha = 0.7f)), size.height - h, size.height),
                Offset(cx - w / 2, size.height - h), Size(w, h), CornerRadius(r))
        }
    }
}

/**
 * Readings as dots for a tile (blood oxygen through the night): each reading between [x0] and [x1] on a scale from
 * [yMin] to 100, the low ones in [lowColor] under [lowBelow].
 */
@Composable
fun TileDots(points: List<SeriesPoint>, x0: Double, x1: Double, color: Color, lowColor: Color, modifier: Modifier = Modifier,
             height: Dp = TILE_GRAPH_HEIGHT, yMin: Double = 88.0, yMax: Double = 100.0, lowBelow: Double = 92.0, description: String = "") {
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        val pad = 4.dp.toPx()
        fun x(t: Double) = pad + ((t - x0) / max(1.0, x1 - x0) * (size.width - 2 * pad)).toFloat()
        fun y(v: Double) = (pad + (1 - (v.coerceIn(yMin, yMax) - yMin) / (yMax - yMin)) * (size.height - 2 * pad)).toFloat()
        points.filter { it.t in x0..x1 }.forEach { p ->
            val col = if (p.v < lowBelow) lowColor else color
            drawCircle(col.copy(alpha = 0.85f), 1.9.dp.toPx(), Offset(x(p.t), y(p.v)))
        }
    }
}
