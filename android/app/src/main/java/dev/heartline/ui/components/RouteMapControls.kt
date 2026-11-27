package dev.heartline.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.heartline.app.RoutePoint
import dev.heartline.app.SeriesPoint
import dev.heartline.ui.theme.HL
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A second GPS track of the same session to compare on the map: its [name] ("Watch GPS") and points. */
data class MapTrack(val name: String, val points: List<RoutePoint>)

/** What the route's colour shows. */
internal enum class MapColouring(val label: String) {
    ACTIVITY("Activity"), PLAIN("Plain"), HEART("Heart rate"), SPEED("Speed"), ALTITUDE("Altitude")
}

/** Which tracks the map shows when it has two: the main one, the other one, or both on top of each other. */
internal enum class MapSource { MAIN, OTHER, BOTH }

/** The map's choices, shared by the map on the page and full screen, so the scrubber keeps its place between them. */
@Stable
internal class MapState(colouring: MapColouring) {
    var source by mutableStateOf(MapSource.MAIN)
    var colouring by mutableStateOf(colouring)
    /** The moment scrubbed to, seconds from the session's start; null shows the whole route. */
    var at by mutableStateOf<Double?>(null)
    var playing by mutableStateOf(false)
    /** Bumped to make the full-screen map re-centre on the marker. */
    var follow by mutableIntStateOf(0)
}

/** A track with what the map needs at each point: metres so far, speed (km/h over about 20 s) and altitude. */
internal class TrackInfo(val points: List<RoutePoint>) {
    val cumM = DoubleArray(points.size)
    val kmh = DoubleArray(points.size) { Double.NaN }
    val hasAlt = points.count { it.altM != null } >= 60
    init {
        for (i in 1 until points.size) cumM[i] = cumM[i - 1] + metresBetween(points[i - 1], points[i])
        var j = 0; var k = 0
        for (i in points.indices) {
            val t = points[i].t
            while (j < i && points[j].t < t - 10) j++
            while (k < points.lastIndex && points[k + 1].t <= t + 10) k++
            val dt = points[k].t - points[j].t
            if (dt >= 5) kmh[i] = (cumM[k] - cumM[j]) / dt * 3.6
        }
    }
    val start: Double get() = points.firstOrNull()?.t?.toDouble() ?: 0.0
    val end: Double get() = points.lastOrNull()?.t?.toDouble() ?: 0.0

    /** The index of the last point at or before [t]. */
    fun indexAt(t: Double): Int {
        var lo = 0; var hi = points.lastIndex
        if (hi < 0 || t <= points[0].t) return 0
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (points[mid].t <= t) lo = mid else hi = mid - 1 }
        return lo
    }

    /** Where the track was at [t], between its two fixes; null outside it or across a gap of more than a minute. */
    fun positionAt(t: Double): Pair<Double, Double>? {
        if (points.size < 2 || t < start - 1 || t > end + 1) return null
        val i = indexAt(t)
        val a = points[i]; val b = points.getOrNull(i + 1) ?: return a.lat to a.lon
        if (b.t - a.t > 60) return if (t - a.t <= 5) a.lat to a.lon else null
        val f = ((t - a.t) / (b.t - a.t).coerceAtLeast(1)).coerceIn(0.0, 1.0)
        return (a.lat + (b.lat - a.lat) * f) to (a.lon + (b.lon - a.lon) * f)
    }

    fun metresAt(t: Double): Double = cumM.getOrElse(indexAt(t)) { 0.0 }
}

private const val EARTH_M = 6_371_000.0
internal fun metresBetween(a: RoutePoint, b: RoutePoint): Double = metresBetween(a.lat, a.lon, b.lat, b.lon)
internal fun metresBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = lat1 * PI / 180; val p2 = lat2 * PI / 180
    val dp = p2 - p1; val dl = (lon2 - lon1) * PI / 180
    val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
    return 2 * EARTH_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

/** Heart rate at [t] from [hr] (seconds from the start), the nearest reading within 20 seconds. */
internal fun hrAt(hr: List<SeriesPoint>, t: Double): Double? {
    if (hr.isEmpty()) return null
    var lo = 0; var hi = hr.lastIndex
    while (lo < hi) { val mid = (lo + hi) / 2; if (hr[mid].t < t) lo = mid + 1 else hi = mid }
    val best = listOfNotNull(hr.getOrNull(lo), hr.getOrNull(lo - 1)).minByOrNull { kotlin.math.abs(it.t - t) } ?: return null
    return best.v.takeIf { kotlin.math.abs(best.t - t) <= 20 }
}

/** Each point's value for a colouring that has one (heart rate, speed, altitude); NaN where it can't say. */
internal fun TrackInfo.values(colouring: MapColouring, hr: List<SeriesPoint>): DoubleArray? = when (colouring) {
    MapColouring.HEART -> DoubleArray(points.size) { hrAt(hr, points[it].t.toDouble()) ?: Double.NaN }
    MapColouring.SPEED -> kmh
    MapColouring.ALTITUDE -> DoubleArray(points.size) { points[it].altM ?: Double.NaN }
    else -> null
}

/** The 5th and 95th percentiles of [v]: the colour scale's ends, so one bad reading doesn't flatten the rest. */
internal fun valueRange(v: DoubleArray): Pair<Double, Double>? {
    val s = v.filter { it.isFinite() }.sorted()
    if (s.size < 10) return null
    val lo = s[(s.size * 0.05).toInt()]; val hi = s[(s.size * 0.95).toInt().coerceAtMost(s.lastIndex)]
    return if (hi - lo < 1e-6) (lo - 1) to (hi + 1) else lo to hi
}

/** Low to high: green, amber, red, the colours the app uses for easy, harder and hardest. */
internal fun rampColor(f: Double, low: Color, mid: Color, high: Color): Color {
    val x = f.coerceIn(0.0, 1.0).toFloat()
    return if (x < 0.5f) lerp(low, mid, x * 2) else lerp(mid, high, (x - 0.5f) * 2)
}

/** "1:02:03" or "12:03". */
private fun elapsed(s: Double): String {
    val t = s.roundToInt().coerceAtLeast(0)
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
}

/**
 * Under the map: the scrubber (play, and a slider through the session's time with what was happening at that moment),
 * the choice of track when there are two, and what the colour shows, with its scale.
 */
@Composable
internal fun MapControls(state: MapState, main: TrackInfo, mainName: String, other: TrackInfo?, otherName: String?,
                         hr: List<SeriesPoint>, startEpoch: Long?, colourings: List<MapColouring>, range: Pair<Double, Double>?,
                         modifier: Modifier = Modifier) {
    val c = HL.colors
    val active = if (state.source == MapSource.OTHER && other != null) other else main
    val t0 = minOf(main.start, other?.start ?: main.start); val t1 = maxOf(main.end, other?.end ?: main.end)
    val span = (t1 - t0).coerceAtLeast(1.0)
    // Playing runs the whole session in about 25 seconds, a frame at a time.
    LaunchedEffect(state.playing) {
        if (!state.playing) return@LaunchedEffect
        if ((state.at ?: t1) >= t1 - 0.5) state.at = t0
        var last = withFrameNanos { it }
        while (state.playing) {
            val now = withFrameNanos { it }
            val next = (state.at ?: t0) + (now - last) / 1e9 * span / 25.0
            last = now
            if (next >= t1) { state.at = t1; state.playing = false } else state.at = next
            state.follow++
        }
    }
    Column(modifier) {
        val at = state.at
        val readout = if (at == null) "Drag through the session to follow the route, or play it back."
        else listOfNotNull(
            "${elapsed(at - t0)} in" + (startEpoch?.let { ", ${epochClock(it + at)}" } ?: ""),
            "%.2f km".format(active.metresAt(at) / 1000),
            active.kmh.getOrNull(active.indexAt(at))?.takeIf { it.isFinite() }?.let { "%.1f km/h".format(it) },
            hrAt(hr, at)?.let { "${it.roundToInt()} bpm" },
            active.points.getOrNull(active.indexAt(at))?.altM?.let { "altitude ${it.roundToInt()} m" },
            if (state.source == MapSource.BOTH && other != null) {
                val a = main.positionAt(at); val b = other.positionAt(at)
                if (a != null && b != null) "tracks ${metresBetween(a.first, a.second, b.first, b.second).roundToInt()} m apart" else null
            } else null,
        ).joinToString(", ")
        Text(readout, style = HL.type.small, color = if (at == null) c.inkSoft else c.ink, modifier = Modifier.heightIn(min = 34.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayButton(state.playing) { state.playing = !state.playing }
            Spacer(Modifier.width(8.dp))
            Slider(value = (at ?: t0).toFloat().coerceIn(t0.toFloat(), t1.toFloat()), onValueChange = { state.playing = false; state.at = it.toDouble(); state.follow++ },
                valueRange = t0.toFloat()..t1.toFloat().coerceAtLeast(t0.toFloat() + 1f), modifier = Modifier.weight(1f).semantics { contentDescription = "Time in the session" },
                colors = SliderDefaults.colors(thumbColor = c.ink, activeTrackColor = c.ink, inactiveTrackColor = c.line, activeTickColor = c.ink, inactiveTickColor = c.line))
            if (at != null) {
                Spacer(Modifier.width(4.dp))
                Text("Whole route", style = HL.type.label, color = c.inkSoft, modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button) { state.playing = false; state.at = null }.padding(horizontal = 6.dp, vertical = 8.dp))
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (other != null && otherName != null) {
                ChoiceChip(mainName, state.source == MapSource.MAIN) { state.source = MapSource.MAIN }
                ChoiceChip(otherName, state.source == MapSource.OTHER) { state.source = MapSource.OTHER }
                ChoiceChip("Both", state.source == MapSource.BOTH) { state.source = MapSource.BOTH }
                Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(22.dp).background(c.line))
            }
            if (colourings.size > 1) colourings.forEach { k -> ChoiceChip(k.label, state.colouring == k) { state.colouring = k } }
        }
        if (range != null && state.colouring in listOf(MapColouring.HEART, MapColouring.SPEED, MapColouring.ALTITUDE)) {
            Spacer(Modifier.height(8.dp))
            val unit = when (state.colouring) { MapColouring.HEART -> "bpm"; MapColouring.SPEED -> "km/h"; else -> "m" }
            fun f(v: Double) = if (state.colouring == MapColouring.SPEED) "%.1f".format(v) else v.roundToInt().toString()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f(range.first), style = HL.type.small, color = c.inkSoft)
                Box(Modifier.padding(horizontal = 8.dp).width(120.dp).height(6.dp).clip(RoundedCornerShape(3.dp))
                    .background(Brush.horizontalGradient(listOf(c.ok, c.warn, c.pulse))))
                Text("${f(range.second)} $unit", style = HL.type.small, color = c.inkSoft)
            }
        } else if (state.source == MapSource.BOTH && otherName != null) {
            Spacer(Modifier.height(8.dp))
            Text("$mainName solid, $otherName dashed.", style = HL.type.small, color = c.inkSoft)
        }
    }
}

@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    val c = HL.colors
    val label = if (playing) "Pause" else "Play the route back"
    Box(Modifier.size(40.dp).clip(CircleShape).background(c.ink).clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(14.dp)) {
            if (playing) {
                val w = size.width * 0.3f
                drawRect(c.paper, Offset(size.width * 0.1f, 0f), androidx.compose.ui.geometry.Size(w, size.height))
                drawRect(c.paper, Offset(size.width * 0.6f, 0f), androidx.compose.ui.geometry.Size(w, size.height))
            } else drawPath(Path().apply { moveTo(size.width * 0.15f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * 0.15f, size.height); close() }, c.paper)
        }
    }
}

@Composable
private fun ChoiceChip(text: String, on: Boolean, onClick: () -> Unit) {
    val c = HL.colors
    Text(text, style = HL.type.label, color = if (on) c.paper else c.ink, modifier = Modifier.clip(RoundedCornerShape(16.dp))
        .background(if (on) c.ink else c.surface).border(1.dp, if (on) c.ink else c.line, RoundedCornerShape(16.dp))
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp))
}
