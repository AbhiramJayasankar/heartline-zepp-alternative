package dev.heartline.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.heartline.app.MapView
import dev.heartline.app.RoutePoint
import dev.heartline.app.TileId
import dev.heartline.app.TileStore
import dev.heartline.app.fitRoute
import dev.heartline.app.panned
import dev.heartline.app.screenOf
import dev.heartline.app.visibleTiles
import dev.heartline.app.zoomed
import dev.heartline.ui.theme.HL
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** A stretch of the route, by time from the workout's start: running (true) or walking. */
data class RouteBout(val start: Int, val end: Int, val running: Boolean)

/** One cache of OpenStreetMap tiles for the app, under its cache folder (Android may clear it; it refills on demand). */
private object OsmTiles {
    @Volatile private var store: TileStore? = null
    fun get(context: Context): TileStore = store ?: synchronized(this) {
        store ?: TileStore(java.io.File(context.cacheDir, "osm-tiles"), "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            "Heartline/0.7 (Android fitness app)").also { store = it }
    }
}

private const val MAP_PREFS = "route_map"
private const val TILES_ON = "tiles"

/**
 * The workout's GPS track, on an OpenStreetMap map or, with the map turned off, on ECG paper so nothing leaves the
 * phone. Map tiles are fetched from tile.openstreetmap.org, which sees the area being looked at (not the track), and
 * are kept in the app's cache. With [bouts], running is drawn in the running colour and walking in the walking colour,
 * as on the capture charts. Two fingers move and zoom the map; one finger scrolls the page.
 */
@Composable
fun RouteMap(route: List<RoutePoint>, modifier: Modifier = Modifier, bouts: List<RouteBout> = emptyList()) {
    val c = HL.colors
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(MAP_PREFS, Context.MODE_PRIVATE) }
    var tilesOn by remember { mutableStateOf(prefs.getBoolean(TILES_ON, true)) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val points = remember(route) { route.map { it.lat to it.lon } }
    var view by remember(route, size) { mutableStateOf(if (size.width > 0 && points.size >= 2) fitRoute(points, size.width, size.height, (size.width / 12).coerceAtLeast(1)) else null) }
    val fitted = remember(route, size) { view }
    val tiles = remember { mutableStateMapOf<TileId, ImageBitmap>() }
    val tm = rememberTextMeasurer()
    val store = remember { OsmTiles.get(context) }
    val v = view
    val placed = if (tilesOn && v != null) visibleTiles(v, size.width, size.height) else emptyList()
    LaunchedEffect(placed.map { it.id }.toSet(), tilesOn) {
        for (p in placed) if (p.id !in tiles) launch {
            store.tile(p.id)?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { tiles[p.id] = it.asImageBitmap() } }
        }
    }
    // Dark themes: the light map inverted and greyed, so it sits under the track like the paper does.
    val filter = remember(c.isDark) {
        if (!c.isDark) null else ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
            -0.6f, -0.25f, -0.1f, 0f, 230f,
            -0.25f, -0.6f, -0.1f, 0f, 230f,
            -0.25f, -0.25f, -0.45f, 0f, 225f,
            0f, 0f, 0f, 1f, 0f)))
    }
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.fillMaxWidth().aspectRatio(1.25f).clip(shape)) {
        Canvas(Modifier.fillMaxSize().ecgPaper(c.gridMinor, c.gridMajor, 8.dp).onSizeChanged { size = it }
            .pointerInput(size) {
                // Only a two-finger gesture belongs to the map, so the page still scrolls under one finger.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        val cur = view
                        if (pressed >= 2 && cur != null && size.width > 0) {
                            val zoom = event.calculateZoom(); val pan = event.calculatePan(); val at = event.calculateCentroid()
                            view = cur.zoomed(zoom, at.x, at.y, size.width, size.height, minZoom = 3.0, maxZoom = 19.0).panned(pan.x, pan.y)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }) {
            val cur = view ?: return@Canvas
            if (route.size < 2) return@Canvas
            for (p in placed) {
                val img = tiles[p.id] ?: continue
                drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
                    dstOffset = IntOffset(p.left.roundToInt(), p.top.roundToInt()),
                    dstSize = IntSize((p.left + p.size).roundToInt() - p.left.roundToInt(), (p.top + p.size).roundToInt() - p.top.roundToInt()),
                    colorFilter = filter)
            }
            fun at(i: Int) = screenOf(route[i].lat, route[i].lon, cur, size.width, size.height).let { Offset(it.first, it.second) }
            val casing = if (tilesOn) c.surface.copy(alpha = 0.9f) else c.paper
            val whole = Path().apply { moveTo(at(0).x, at(0).y); for (i in 1 until route.size) lineTo(at(i).x, at(i).y) }
            drawPath(whole, casing, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            // The track in pieces by what the feet were doing; with no bouts, all of it in the heart colour.
            val pieces = if (bouts.isEmpty()) listOf(route.indices to c.pulse) else {
                val colorOf = { t: Int -> bouts.firstOrNull { t >= it.start && t < it.end }?.let { if (it.running) c.warn else c.ok } ?: c.inkSoft }
                val out = ArrayList<Pair<IntRange, Color>>()
                var a = 0
                for (i in 1..route.size) {
                    if (i < route.size && colorOf(route[i].t) == colorOf(route[a].t)) continue
                    out += (a..minOf(i, route.lastIndex)) to colorOf(route[a].t)
                    a = i
                }
                out
            }
            for ((range, color) in pieces) {
                if (range.first >= range.last) continue
                val path = Path().apply { moveTo(at(range.first).x, at(range.first).y); for (i in range.first + 1..range.last) lineTo(at(i).x, at(i).y) }
                drawPath(path, color, style = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            drawCircle(casing, 7.5.dp.toPx(), at(0)); drawCircle(c.ok, 5.5.dp.toPx(), at(0))
            drawCircle(casing, 7.5.dp.toPx(), at(route.lastIndex)); drawCircle(c.ink, 5.5.dp.toPx(), at(route.lastIndex))
            scaleBar(tm, cur, c.ink, c.inkSoft, if (tilesOn) c.surface.copy(alpha = 0.85f) else null)
        }
        // OpenStreetMap's licence needs the credit on the map itself.
        if (tilesOn) Text("© OpenStreetMap contributors", style = HL.type.small, color = c.inkSoft,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).background(c.surface.copy(alpha = 0.85f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
        Row(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
            if (view != fitted && fitted != null) MapChip("Fit") { view = fitted }
            MapChip(if (tilesOn) "Paper" else "Map") { tilesOn = !tilesOn; prefs.edit().putBoolean(TILES_ON, tilesOn).apply() }
        }
    }
}

@Composable
private fun MapChip(text: String, onClick: () -> Unit) {
    val c = HL.colors
    Text(text, style = HL.type.label, color = c.ink, modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp))
        .background(c.surface.copy(alpha = 0.9f)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp))
}

/** The largest 1, 2 or 5 x 10^n metres that fits in a quarter of the width, at the map's scale at its centre. */
private fun DrawScope.scaleBar(tm: TextMeasurer, view: MapView, ink: Color, soft: Color, backing: Color?) {
    val metresPerPx = 156_543.03392 * cos(Math.toRadians(view.lat)) / 2.0.pow(view.zoom)
    val target = size.width / 4 * metresPerPx
    val mag = 10.0.pow(floor(log10(target)))
    val metres = listOf(5.0, 2.0, 1.0).map { it * mag }.first { it <= target }
    val len = (metres / metresPerPx).toFloat()
    // Top left: the bottom edge belongs to the map's credit, the top right to its buttons.
    val y = 26.dp.toPx()
    val x0 = 12.dp.toPx()
    val text = if (metres >= 1000) "${if (metres % 1000 == 0.0) (metres / 1000).toInt().toString() else (metres / 1000).toString()} km" else "${metres.toInt()} m"
    if (backing != null) drawRoundRect(backing, Offset(x0 - 6.dp.toPx(), y - 16.dp.toPx()),
        androidx.compose.ui.geometry.Size(len + 52.dp.toPx(), 22.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
    drawLine(ink, Offset(x0, y), Offset(x0 + len, y), 2.dp.toPx())
    label(tm, text, x0 + len + 6.dp.toPx(), y - 7.dp.toPx(), soft, center = false)
}
