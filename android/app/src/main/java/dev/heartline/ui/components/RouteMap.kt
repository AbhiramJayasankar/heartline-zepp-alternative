package dev.heartline.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.heartline.app.RoutePoint
import dev.heartline.ui.theme.HL
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** The workout's GPS track on ECG paper. No map tiles, so nothing leaves the phone. Start is green, finish is ink. */
@Composable
fun RouteMap(route: List<RoutePoint>, modifier: Modifier = Modifier) {
    val c = HL.colors
    val tm = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().aspectRatio(1.25f).ecgPaper(c.gridMinor, c.gridMajor, 8.dp)) {
        if (route.size < 2) return@Canvas
        val lat0 = Math.toRadians(route.sumOf { it.lat } / route.size)
        val mPerDegLat = 111_320.0
        val mPerDegLon = mPerDegLat * cos(lat0)
        val xs = route.map { it.lon * mPerDegLon }
        val ys = route.map { it.lat * mPerDegLat }
        val spanX = (xs.max() - xs.min()).coerceAtLeast(1.0)
        val spanY = (ys.max() - ys.min()).coerceAtLeast(1.0)
        val pad = 22.dp.toPx()
        val scale = minOf((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY) // px per metre
        val ox = (size.width - spanX * scale) / 2
        val oy = (size.height - spanY * scale) / 2
        fun at(i: Int) = Offset((ox + (xs[i] - xs.min()) * scale).toFloat(), (size.height - oy - (ys[i] - ys.min()) * scale).toFloat())
        val path = Path().apply { moveTo(at(0).x, at(0).y); for (i in 1 until route.size) lineTo(at(i).x, at(i).y) }
        drawPath(path, c.paper, style = Stroke(9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, c.pulse, style = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(c.ok, 6.dp.toPx(), at(0))
        drawCircle(c.ink, 6.dp.toPx(), at(route.lastIndex))
        // Scale bar: the largest 1, 2 or 5 x 10^n metres that fits in a quarter of the width.
        val target = size.width / 4 / scale
        val mag = 10.0.pow(floor(log10(target)))
        val metres = listOf(5.0, 2.0, 1.0).map { it * mag }.first { it <= target }
        val len = (metres * scale).toFloat()
        val y = size.height - 10.dp.toPx()
        val x0 = 12.dp.toPx()
        drawLine(c.ink, Offset(x0, y), Offset(x0 + len, y), 2.dp.toPx())
        val text = if (metres >= 1000) "${if (metres % 1000 == 0.0) (metres / 1000).toInt().toString() else (metres / 1000).toString()} km" else "${metres.toInt()} m"
        label(tm, text, x0 + len + 6.dp.toPx(), y - 7.dp.toPx(), c.inkSoft, center = false)
    }
}
