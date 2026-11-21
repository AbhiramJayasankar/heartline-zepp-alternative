package dev.heartline.ui.components

import android.content.Context
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

/** A lap to pick out on the map: its name, its span in seconds from the session's start, and a line of its figures. */
data class MapLap(val name: String, val start: Double, val end: Double, val summary: String)

/** One cache of OpenStreetMap tiles for the app, under its cache folder (Android may clear it; it refills on demand). */
private object OsmTiles {
    @Volatile private var store: TileStore? = null
    fun get(context: Context): TileStore = store ?: synchronized(this) {
        store ?: TileStore(java.io.File(context.cacheDir, "osm-tiles"), "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            "Heartline/0.9 (Android fitness app)").also { store = it }
    }
}

private const val MAP_PREFS = "route_map"
private const val TILES_ON = "tiles"

/**
 * The workout's GPS track, on an OpenStreetMap map or, with the map turned off, on ECG paper so nothing leaves the
 * phone. Map tiles are fetched from tile.openstreetmap.org, which sees the area being looked at (not the track), and
 * are kept in the app's cache. With [bouts], running is drawn in the running colour and walking in the walking colour,
 * as on the capture charts; [lapPoint] marks where laps start and end.
 *
 * On the page, two fingers move and zoom the map so one finger still scrolls the page. Full screen (its button, or a
 * double tap) takes one finger, pinch, double tap and zoom buttons, and with [laps] picks out one lap at a time.
 */
@Composable
fun RouteMap(route: List<RoutePoint>, modifier: Modifier = Modifier, bouts: List<RouteBout> = emptyList(),
             laps: List<MapLap> = emptyList(), lapPoint: Pair<Double, Double>? = null, title: String = "Route") {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(MAP_PREFS, Context.MODE_PRIVATE) }
    var tilesOn by remember { mutableStateOf(prefs.getBoolean(TILES_ON, true)) }
    var full by remember { mutableStateOf(false) }
    val toggle = { tilesOn = !tilesOn; prefs.edit().putBoolean(TILES_ON, tilesOn).apply() }
    if (full) FullscreenMap(title, route, bouts, laps, lapPoint, tilesOn, toggle) { full = false }
    MapArea(route, bouts, lapPoint, tilesOn, toggle, modifier.fillMaxWidth().aspectRatio(1.25f).clip(RoundedCornerShape(8.dp)),
        fullscreen = false, selected = null, onExpand = { full = true })
}

/** The map full screen: one finger moves it, pinch or double tap zooms, and a row of laps picks one out. */
@Composable
private fun FullscreenMap(title: String, route: List<RoutePoint>, bouts: List<RouteBout>, laps: List<MapLap>, lapPoint: Pair<Double, Double>?,
                          tilesOn: Boolean, toggle: () -> Unit, onClose: () -> Unit) {
    val c = HL.colors
    var selected by remember { mutableStateOf<MapLap?>(null) }
    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(c.paper)
            .padding(androidx.compose.foundation.layout.WindowInsets.safeDrawing.asPaddingValues())) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = HL.type.section, color = c.ink, modifier = Modifier.weight(1f))
                Box(Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable(onClickLabel = "Close", role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = "Close the map" }, contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(16.dp)) {
                        val st = 2.dp.toPx()
                        drawLine(c.ink, Offset(0f, 0f), Offset(size.width, size.height), st, cap = StrokeCap.Round)
                        drawLine(c.ink, Offset(size.width, 0f), Offset(0f, size.height), st, cap = StrokeCap.Round)
                    }
                }
            }
            MapArea(route, bouts, lapPoint, tilesOn, toggle, Modifier.fillMaxWidth().weight(1f).clipToBounds(), fullscreen = true, selected = selected, onExpand = {})
            if (laps.isNotEmpty()) {
                androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LapChip("All laps", selected == null) { selected = null }
                        laps.forEach { l -> LapChip(l.name, selected == l) { selected = if (selected == l) null else l } }
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                    Text(selected?.summary ?: "Pick a lap to see it on its own.", style = HL.type.small, color = if (selected == null) c.inkSoft else c.ink,
                        modifier = Modifier.heightIn(min = 36.dp))
                }
            }
        }
    }
}

@Composable
private fun LapChip(text: String, on: Boolean, onClick: () -> Unit) {
    val c = HL.colors
    Text(text, style = HL.type.label, color = if (on) c.paper else c.ink, modifier = Modifier.clip(RoundedCornerShape(16.dp))
        .background(if (on) c.ink else c.surface).border(1.dp, if (on) c.ink else c.line, RoundedCornerShape(16.dp))
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp))
}

@Composable
private fun MapArea(route: List<RoutePoint>, bouts: List<RouteBout>, lapPoint: Pair<Double, Double>?, tilesOn: Boolean, toggle: () -> Unit,
                    modifier: Modifier, fullscreen: Boolean, selected: MapLap?, onExpand: () -> Unit) {
    val c = HL.colors
    val context = LocalContext.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    // The part to fit: the picked lap, else the whole route.
    val shown = remember(route, selected) { selected?.let { l -> route.filter { it.t >= l.start - 1 && it.t <= l.end + 1 } }?.takeIf { it.size >= 2 } ?: route }
    val points = remember(shown) { shown.map { it.lat to it.lon } }
    var view by remember(route, size, selected) { mutableStateOf(if (size.width > 0 && points.size >= 2) fitRoute(points, size.width, size.height, (size.width / 12).coerceAtLeast(1)) else null) }
    val fitted = remember(route, size, selected) { view }
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
    fun zoomBy(f: Float) { view?.let { cur -> view = cur.zoomed(f, size.width / 2f, size.height / 2f, size.width, size.height, minZoom = 3.0, maxZoom = 19.0) } }
    Box(modifier) {
        val gestures = if (fullscreen) Modifier
            .pointerInput(size) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val cur = view ?: return@detectTransformGestures
                    view = cur.zoomed(zoom, centroid.x, centroid.y, size.width, size.height, minZoom = 3.0, maxZoom = 19.0).panned(pan.x, pan.y)
                }
            }
            .pointerInput(size) { detectTapGestures(onDoubleTap = { at -> view?.let { cur -> view = cur.zoomed(2f, at.x, at.y, size.width, size.height, minZoom = 3.0, maxZoom = 19.0) } }) }
        else Modifier
            .pointerInput(size) {
                // Only a two-finger gesture belongs to the map here, so the page still scrolls under one finger.
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
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onExpand() }) }
        Canvas(Modifier.fillMaxSize().ecgPaper(c.gridMinor, c.gridMajor, 8.dp).onSizeChanged { size = it }.then(gestures)) {
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
            // The track in pieces by what the feet were doing, faded outside the lap picked.
            val colorOf = { i: Int ->
                val t = route[i].t
                val base = if (bouts.isEmpty()) c.pulse else bouts.firstOrNull { t >= it.start && t < it.end }?.let { if (it.running) c.warn else c.ok } ?: c.inkSoft
                if (selected == null || (t >= selected.start && t <= selected.end)) base else base.copy(alpha = 0.22f)
            }
            var a = 0
            for (i in 1..route.size) {
                if (i < route.size && colorOf(i) == colorOf(a)) continue
                val last = minOf(i, route.lastIndex)
                if (last > a) {
                    val path = Path().apply { moveTo(at(a).x, at(a).y); for (k in a + 1..last) lineTo(at(k).x, at(k).y) }
                    drawPath(path, colorOf(a), style = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                a = i
            }
            drawCircle(casing, 7.5.dp.toPx(), at(0)); drawCircle(c.ok, 5.5.dp.toPx(), at(0))
            drawCircle(casing, 7.5.dp.toPx(), at(route.lastIndex)); drawCircle(c.ink, 5.5.dp.toPx(), at(route.lastIndex))
            // The lap point: a ring where each lap starts and ends.
            lapPoint?.let { (lat, lon) ->
                val p = screenOf(lat, lon, cur, size.width, size.height).let { Offset(it.first, it.second) }
                drawCircle(casing, 10.dp.toPx(), p); drawCircle(c.pulse, 8.dp.toPx(), p, style = Stroke(3.dp.toPx()))
            }
            scaleBar(tm, cur, c.ink, c.inkSoft, if (tilesOn) c.surface.copy(alpha = 0.85f) else null)
        }
        // OpenStreetMap's licence needs the credit on the map itself.
        if (tilesOn) Text("© OpenStreetMap contributors", style = HL.type.small, color = c.inkSoft,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).background(c.surface.copy(alpha = 0.85f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
        Row(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
            if (view != fitted && fitted != null) MapChip("Fit") { view = fitted }
            MapChip(if (tilesOn) "Paper" else "Map") { toggle() }
            if (!fullscreen) MapChip("Full screen") { onExpand() }
        }
        if (fullscreen) androidx.compose.foundation.layout.Column(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 32.dp)) {
            ZoomButton("+", "Zoom in") { zoomBy(2f) }
            androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
            ZoomButton("−", "Zoom out") { zoomBy(0.5f) }
        }
    }
}

@Composable
private fun ZoomButton(text: String, label: String, onClick: () -> Unit) {
    val c = HL.colors
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(c.surface.copy(alpha = 0.92f)).border(1.dp, c.line, RoundedCornerShape(10.dp))
        .clickable(onClickLabel = label, role = Role.Button, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Text(text, style = HL.type.section, color = c.ink)
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
