package dev.heartline.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.heartline.app.MinuteState
import dev.heartline.app.MinuteUi
import dev.heartline.app.SeriesPoint
import dev.heartline.ui.theme.HL
import dev.heartline.ui.theme.Palette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The stages chart's rows, top to bottom: awake, REM, light, deep. */
val STAGE_NAMES = listOf("Awake", "REM", "Light", "Deep")

/** A minute's row on the stages chart, or null for a minute that isn't part of the night (not worn, charging). */
fun stageLane(s: MinuteState): Int? = when (s) {
    MinuteState.ACTIVE, MinuteState.IDLE -> 0; MinuteState.SLEEP_REM -> 1; MinuteState.SLEEP_LIGHT -> 2; MinuteState.SLEEP_DEEP -> 3; else -> null
}

/** A stage's colour by row ([stageLane]). */
fun Palette.stageColor(lane: Int): Color = when (lane) { 0 -> stageAwake; 1 -> stageRem; 2 -> stageLight; else -> stageDeep }

/** One unbroken stretch of a stage, epoch seconds, [end] exclusive. */
data class StageStretch(val lane: Int, val start: Long, val end: Long) {
    val minutes: Int get() = ((end - start + 30) / 60).toInt()
}

/**
 * The night's minutes as stretches of one stage each. A missing minute or two inside a stretch is bridged; a longer
 * gap ends it, so the chart doesn't join across time the watch didn't record.
 */
fun stageStretches(minutes: List<MinuteUi>, start: Long, end: Long): List<StageStretch> {
    val out = ArrayList<StageStretch>()
    for (m in minutes.filter { it.ts in start until end }.sortedBy { it.ts }) {
        val lane = stageLane(m.state) ?: continue
        val last = out.lastOrNull()
        if (last != null && last.lane == lane && m.ts - last.end <= 120) out[out.lastIndex] = last.copy(end = min(end, m.ts + 60))
        else out += StageStretch(lane, m.ts, min(end, m.ts + 60))
    }
    return out
}

/**
 * REM or deep stretches with no more than this many minutes of other sleep between them are one episode, smoothed. Sleep
 * scoring commonly treats REM broken by up to 15 minutes as one REM period; 12 keeps clearly separate periods apart.
 */
const val STAGE_EPISODE_GAP = 12
/** A sleep stretch shorter than this (minutes) is folded into its neighbours when smoothed. */
const val STAGE_FOLD_MIN = 5

/**
 * The night as a watch shows it, from the stretches [stageStretches] finds minute by minute. The watch scores REM a
 * minute at a time against a threshold, so a REM period arrives as runs like REM 3, light 4, REM 4, light 1, REM 5 (the
 * night of 26 to 27 Nov 2026 had 1h 05m of REM in 24 such runs). Smoothed:
 * 1. REM and deep stretches with up to [STAGE_EPISODE_GAP] minutes of other sleep between them join into one episode;
 * 2. a sleep stretch under [STAGE_FOLD_MIN] minutes with the same stage either side is folded into it, shortest first;
 * 3. a sleep stretch under 3 minutes still left joins its longer sleeping neighbour.
 * Awake stretches are never folded or joined over: a waking, however short, stays. Gaps in the recording stay gaps.
 */
fun smoothStretches(stretches: List<StageStretch>): List<StageStretch> {
    if (stretches.size < 3) return stretches
    val t0 = stretches.first().start
    val n = ((stretches.last().end - t0 + 59) / 60).toInt()
    // One lane a minute, -1 where nothing was recorded.
    val lane = IntArray(n) { -1 }
    for (st in stretches) for (i in ((st.start - t0) / 60).toInt() until min(n, ((st.end - t0 + 59) / 60).toInt())) lane[i] = st.lane
    data class Run(val lane: Int, val from: Int, val to: Int) { val len get() = to - from }
    fun runs(): List<Run> {
        val out = ArrayList<Run>(); var i = 0
        while (i < n) { var j = i; while (j < n && lane[j] == lane[i]) j++; out += Run(lane[i], i, j); i = j }
        return out
    }
    fun sleeping(l: Int) = l in 1..3
    // 1. Episodes.
    for (l in listOf(1, 3)) {
        val r = runs()
        var k = 0
        while (k < r.size) {
            if (r[k].lane != l) { k++; continue }
            var j = k + 1
            while (j < r.size && r[j].lane != l && sleeping(r[j].lane) && r[j].to - r[k].to <= STAGE_EPISODE_GAP) j++
            if (j < r.size && r[j].lane == l && r[j].from - r[k].to <= STAGE_EPISODE_GAP) {
                for (i in r[k].to until r[j].from) lane[i] = l
                k = j
            } else k++
        }
    }
    // 2. Short stretches inside one stage fold into it, shortest first.
    while (true) {
        val r = runs()
        val k = (1 until r.size - 1).filter { sleeping(r[it].lane) && r[it].len < STAGE_FOLD_MIN && r[it - 1].lane == r[it + 1].lane && sleeping(r[it - 1].lane) }
            .minByOrNull { r[it].len } ?: break
        for (i in r[k].from until r[k].to) lane[i] = r[k - 1].lane
    }
    // 3. Any blip left joins its longer sleeping neighbour.
    while (true) {
        val r = runs()
        val k = r.indices.filter { sleeping(r[it].lane) && r[it].len < 3 && (sleeping(r.getOrNull(it - 1)?.lane ?: -1) || sleeping(r.getOrNull(it + 1)?.lane ?: -1)) }
            .minByOrNull { r[it].len } ?: break
        val prev = r.getOrNull(k - 1)?.takeIf { sleeping(it.lane) }; val next = r.getOrNull(k + 1)?.takeIf { sleeping(it.lane) }
        val into = listOfNotNull(prev, next).maxByOrNull { it.len }!!.lane
        for (i in r[k].from until r[k].to) lane[i] = into
    }
    return runs().filter { it.lane >= 0 }.map { StageStretch(it.lane, t0 + it.from * 60L, min(stretches.last().end, t0 + it.to * 60L)) }
}

private val hhmm = DateTimeFormatter.ofPattern("HH:mm")
private fun clockAt(ts: Long) = Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()).format(hhmm)
private fun span(min: Int) = if (min >= 60) "${min / 60}h ${"%02d".format(min % 60)}m" else "${min}m"

/** A colour a little lighter, for the top of a block, so it reads as lit from above. */
private fun Color.lit(dark: Boolean) = lerp(this, Color.White, if (dark) 0.16f else 0.12f)

/**
 * Draws the stretches in rows [top]..[top] + 4 * [rowH] between [x0] and [x1], after the way a watch draws them: each
 * stretch a solid rounded block lit from above, inside a see-through rim of its colour, and at each change of stage a bar twice the
 * rim's width ([connector]) centred on the change, from the upper block's rim to the lower's. Inside a row the bar is
 * lost in the rims; between rows it shades from one stage's colour to the next and widens into each rim with a curved
 * fillet, so one block flows into the next. Stretches other than [focus] fade when one is picked.
 */
private fun DrawScope.stages(stretches: List<StageStretch>, start: Long, end: Long, x0: Float, x1: Float, top: Float, rowH: Float,
                             c: Palette, blockFrac: Float, connector: Dp, focus: StageStretch?) {
    fun x(ts: Long) = x0 + (ts - start).toFloat() / max(1L, end - start) * (x1 - x0)
    fun cy(lane: Int) = top + rowH * (lane + 0.5f)
    val bh = rowH * blockFrac
    val cw = connector.toPx(); val h = cw / 2
    // Short stretches keep their true width, as a watch draws them: a waking of a minute or two is a thin bright line
    // inside its bar. Only one too thin to see at all is widened, to this.
    val minW = 1.5.dp.toPx()
    val radius = bh * 0.2f
    val fillet = bh * 0.16f
    // Where each block is drawn. Stretches that follow on share an edge, and a bar sits on it. A stretch narrower than
    // [minW] is widened evenly about its middle into the ones either side, so its bars stay on its edges.
    val n = stretches.size
    val ls = FloatArray(n) { x(stretches[it].start) }; val rs = FloatArray(n) { x(stretches[it].end) }
    fun touches(i: Int) = stretches[i + 1].start - stretches[i].end <= 300
    for (i in 0 until n - 1) if (touches(i)) rs[i] = ls[i + 1]
    for (i in 0 until n) {
        if (rs[i] - ls[i] >= minW) continue
        val m = (ls[i] + rs[i]) / 2
        ls[i] = m - minW / 2; rs[i] = m + minW / 2
        if (ls[i] < x0) { rs[i] += x0 - ls[i]; ls[i] = x0 }
        if (rs[i] > x1) { ls[i] -= rs[i] - x1; rs[i] = x1 }
        if (i > 0 && touches(i - 1)) rs[i - 1] = max(ls[i - 1] + 1f, ls[i])
        if (i < n - 1 && touches(i)) ls[i + 1] = min(rs[i + 1] - 1f, rs[i])
    }
    val blocks = stretches.mapIndexed { i, s ->
        val y = cy(s.lane) - bh / 2
        RoundRect(ls[i], y, rs[i], y + bh, CornerRadius(min(radius, (rs[i] - ls[i]) / 2)))
    }
    val rims = blocks.map { r -> RoundRect(r.left - h, r.top - h, r.right + h, r.bottom + h, CornerRadius(r.topLeftCornerRadius.x + h)) }
    val joins = (0 until stretches.size - 1).filter { i ->
        val a = stretches[i]; val b = stretches[i + 1]
        b.start - a.end <= 300 && a.lane != b.lane
    }

    // The bars and rims, drawn solid in a layer that's laid down see-through, so where they overlap they don't add up.
    fun rimsAndBars(only: Set<Int>?, layerAlpha: Float) {
        drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, size), Paint().apply { alpha = layerAlpha }) }
        for (i in joins) {
            if (only != null && i !in only && i + 1 !in only) continue
            val a = stretches[i]; val b = stretches[i + 1]
            val bx = rs[i] // the edge the two blocks share
            val ui = if (a.lane < b.lane) i else i + 1; val li = if (ui == i) i + 1 else i
            val ur = rims[ui]; val lr = rims[li]
            // Which way from the bar each block lies: the earlier stretch on the left, the later on the right.
            val uSide = if (ui == i) -1f else 1f; val lSide = -uSide
            val bar = Path().apply {
                // Between the rims only: past the near corners the rims carry it, and a bar to their far corners would
                // poke out past their curve.
                addRect(Rect(bx - h, ur.bottom - min(ur.topLeftCornerRadius.x, (ur.right - ur.left) / 2), bx + h,
                    lr.top + min(lr.topLeftCornerRadius.x, (lr.right - lr.left) / 2)))
                // Square off each rim's corner on the bar's side, so the bar meets a straight edge, then curve into it.
                for ((rim, side, down) in listOf(Triple(ur, uSide, true), Triple(lr, lSide, false))) {
                    val w = rim.right - rim.left
                    val rc = min(rim.topLeftCornerRadius.x, w / 2)
                    val outer = bx - side * h // the rim's edge on the bar's side
                    val edgeY = if (down) rim.bottom else rim.top
                    addRect(Rect(min(outer, outer + side * rc), if (down) edgeY - rc else edgeY, max(outer, outer + side * rc), if (down) edgeY else edgeY + rc))
                    val f = min(fillet, max(0f, w - cw - rc))
                    addPath(filletPath(bx + side * h, edgeY, side, if (down) 1f else -1f, f))
                }
            }
            val span = lr.bottom - ur.top
            val up = c.stageColor(stretches[ui].lane); val dn = c.stageColor(stretches[li].lane)
            drawPath(bar, Brush.verticalGradient(0f to up, ((ur.bottom - ur.top) / span) to up, (1 - (lr.bottom - lr.top) / span) to dn, 1f to dn,
                startY = ur.top, endY = lr.bottom))
        }
        rims.forEachIndexed { i, r -> if (only == null || i in only) drawPath(Path().apply { addRoundRect(r) }, c.stageColor(stretches[i].lane)) }
        // Cut the blocks out, so a faded block doesn't show its rim through it.
        blocks.forEach { r -> drawPath(Path().apply { addRoundRect(r) }, Color.Black, blendMode = BlendMode.Clear) }
        drawIntoCanvas { it.restore() }
    }
    rimsAndBars(null, if (focus == null) 0.5f else 0.14f)
    val fi = focus?.let { stretches.indexOf(it) } ?: -1
    if (fi >= 0) rimsAndBars(setOf(fi), 0.5f)
    stretches.forEachIndexed { i, s ->
        val a = if (focus == null || focus == s) 1f else 0.25f
        val col = c.stageColor(s.lane); val r = blocks[i]
        drawPath(Path().apply { addRoundRect(r) }, Brush.verticalGradient(listOf(col.lit(c.isDark).copy(alpha = a), col.copy(alpha = a)), r.top, r.bottom))
    }
}

/**
 * A concave fillet in the corner at ([cx], [cy]) where a bar's side meets a block's edge: it reaches [f] along the edge
 * (towards [dx]) and [f] along the bar (towards [dy]), its open side a quarter circle, so the bar curves into the block.
 */
private fun filletPath(cx: Float, cy: Float, dx: Float, dy: Float, f: Float): Path {
    val k = 0.5523f // a cubic's handle length for a quarter circle
    return Path().apply {
        moveTo(cx, cy)
        lineTo(cx, cy + dy * f)
        cubicTo(cx, cy + dy * f * (1 - k), cx + dx * f * (1 - k), cy, cx + dx * f, cy)
        close()
    }
}

/**
 * The night's sleep stages, after the way a watch shows them: a row per stage (awake, REM, light, deep) with its total
 * on the left, each stretch a rounded block in its stage's colour, a bar down the blocks' sides joining one
 * stretch to the next, and the time along the bottom. Touch or drag for the stretch at that moment, with heart rate and blood oxygen;
 * the rest of the night fades back while one is picked. [totals] are the night's minutes per row (awake, REM, light,
 * deep) as the rest of the page counts them; without them the rows are summed from [minutes].
 */
@Composable
fun SleepStagesChart(minutes: List<MinuteUi>, start: Long, end: Long, modifier: Modifier = Modifier,
                     spo2: List<SeriesPoint> = emptyList(), totals: List<Int>? = null) {
    val c = HL.colors
    val tm = rememberTextMeasurer()
    val night = remember(minutes, start, end) { minutes.filter { it.ts in start until end }.sortedBy { it.ts } }
    val raw = remember(night, start, end) { stageStretches(night, start, end) }
    val smooth = remember(raw) { smoothStretches(raw) }
    // Smoothed by default, as a watch shows it; every minute on request.
    var everyMinute by remember { mutableStateOf(false) }
    val stretches = if (everyMinute) raw else smooth
    val rows = totals ?: (0..3).map { l -> raw.filter { it.lane == l }.sumOf { it.minutes } }
    var sel by remember(start, end) { mutableStateOf<Long?>(null) }
    var widthPx by remember { mutableStateOf(1f) }
    val padL = 64.dp; val padR = 6.dp
    val focus = sel?.let { t -> stretches.firstOrNull { t >= it.start && t < it.end } }

    // What the touched moment was: its stretch, then heart rate and blood oxygen at that minute.
    Box(Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
        val t = sel
        if (t == null) MutedText("Touch or drag along the night for each stretch, with your heart rate and blood oxygen.")
        else {
            val m = night.minByOrNull { abs(it.ts - t) }?.takeIf { abs(it.ts - t) <= 120 }
            val o = spo2.minByOrNull { abs(it.t - t) }?.takeIf { abs(it.t - t) <= 600 }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (focus != null) { Dot(c.stageColor(focus.lane), 10.dp); Spacer(Modifier.width(6.dp)) }
                    Text(focus?.let { STAGE_NAMES[it.lane] } ?: "No data", style = HL.type.bodyStrong, color = c.ink)
                    focus?.let {
                        Spacer(Modifier.width(8.dp))
                        Text("${clockAt(it.start)}–${clockAt(it.end)}, ${span(it.minutes)}", style = HL.type.label, color = c.inkSoft)
                    }
                    // A folded-in minute: say what the watch had for it.
                    val own = m?.state?.let { stageLane(it) }
                    if (focus != null && own != null && own != focus.lane) {
                        Spacer(Modifier.width(8.dp))
                        Text("(this minute ${STAGE_NAMES[own]})", style = HL.type.label, color = c.inkSoft)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(clockAt(t), style = HL.type.label, color = c.inkSoft)
                    m?.hr?.let { Text("$it bpm", style = HL.type.label, color = c.pulse) }
                    o?.let { Text("SpO2 %.0f%%".format(it.v), style = HL.type.label, color = c.oxygen) }
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    val nameStyle = HL.type.label.copy(fontSize = 13.sp, color = c.ink)
    val totalStyle = HL.type.small.copy(fontSize = 12.sp, color = c.inkSoft)
    val clockStyle = HL.type.label.copy(fontSize = 12.sp, color = c.inkSoft)
    val describe = STAGE_NAMES.indices.joinToString(", ") { "${STAGE_NAMES[it]} ${span(rows.getOrElse(it) { 0 })}" }
    Canvas(modifier.fillMaxWidth().height(236.dp)
        .semantics { contentDescription = "Sleep stages from ${clockAt(start)} to ${clockAt(end)}: $describe" }
        .pointerInput(start, end) {
            fun at(px: Float): Long { val l = padL.toPx(); val r = widthPx - padR.toPx(); return start + (((px - l) / (r - l)).coerceIn(0f, 1f) * (end - start)).toLong() }
            detectTapGestures { o -> sel = at(o.x).takeIf { sel == null || abs(it - sel!!) > 300 } }
        }
        .pointerInput(start, end) {
            fun at(px: Float): Long { val l = padL.toPx(); val r = widthPx - padR.toPx(); return start + (((px - l) / (r - l)).coerceIn(0f, 1f) * (end - start)).toLong() }
            detectHorizontalDragGestures(onDragStart = { sel = at(it.x) }) { ch, _ -> sel = at(ch.position.x); ch.consume() }
        }) {
        widthPx = size.width
        val x0 = padL.toPx(); val x1 = size.width - padR.toPx()
        val axisH = 34.dp.toPx()
        val rowH = (size.height - axisH) / 4
        fun x(ts: Long) = x0 + (ts - start).toFloat() / max(1L, end - start) * (x1 - x0)
        // Each row's name and its total for the night.
        STAGE_NAMES.forEachIndexed { i, name ->
            val nameText = tm.measure(name, nameStyle)
            val total = tm.measure(span(rows.getOrElse(i) { 0 }), totalStyle)
            val y = rowH * (i + 0.5f) - (nameText.size.height + total.size.height) / 2f
            drawText(nameText, topLeft = Offset(0f, y))
            drawText(total, topLeft = Offset(0f, y + nameText.size.height))
        }
        stages(stretches, start, end, x0, x1, 0f, rowH, c, blockFrac = 0.6f, connector = 4.dp, focus = focus)
        // The time along the bottom: a dot every half hour, a bar at each end with its clock time under it.
        val ay = rowH * 4 + 9.dp.toPx()
        val firstHalf = (start / 1800 + 1) * 1800
        var h = firstHalf
        while (h < end) {
            if (x(h) - x0 > 6.dp.toPx() && x1 - x(h) > 6.dp.toPx()) drawCircle(c.inkSoft.copy(alpha = if (h % 3600 == 0L) 0.8f else 0.45f), if (h % 3600 == 0L) 1.7.dp.toPx() else 1.2.dp.toPx(), Offset(x(h), ay))
            h += 1800
        }
        listOf(x0, x1).forEach { xx -> drawCircle(c.inkSoft, 2.4.dp.toPx(), Offset(xx, ay)) }
        val st = tm.measure(clockAt(start), clockStyle)
        val en = tm.measure(clockAt(end), clockStyle)
        drawText(st, topLeft = Offset(x0, ay + 7.dp.toPx()))
        drawText(en, topLeft = Offset(x1 - en.size.width, ay + 7.dp.toPx()))
        // The moment picked.
        sel?.let { t -> drawLine(c.ink.copy(alpha = 0.35f), Offset(x(t), 0f), Offset(x(t), rowH * 4), 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round) }
    }
    // Smoothed or every minute: what the watch scored minute by minute is a click away.
    if (raw.size != smooth.size) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (everyMinute) "Every minute as the watch scored it." else "Smoothed into episodes, as a watch shows them; the totals count every minute.",
                style = HL.type.small.copy(fontSize = 12.sp), color = c.inkSoft, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(if (everyMinute) "Smooth" else "Every minute", style = HL.type.label, color = c.ink,
                modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .border(1.dp, c.line, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) { everyMinute = !everyMinute }
                    .padding(horizontal = 12.dp, vertical = 7.dp))
        }
    }
}

/** The stages chart in small, for a tile: the same rows and colours, no labels or axis. */
@Composable
fun SleepStagesMini(minutes: List<MinuteUi>, start: Long, end: Long, modifier: Modifier = Modifier, height: Dp = 54.dp) {
    val c = HL.colors
    val stretches = remember(minutes, start, end) { smoothStretches(stageStretches(minutes, start, end)) }
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = "Sleep stages from ${clockAt(start)} to ${clockAt(end)}" }) {
        stages(stretches, start, end, 0f, size.width, 0f, size.height / 4, c, blockFrac = 0.64f, connector = 2.dp, focus = null)
    }
}

/** A sleep length in the large type of a headline, hours and minutes big and their units small, in [color]. */
@Composable
fun SleepLength(minutes: Int?, color: Color, modifier: Modifier = Modifier, size: Int = 34) {
    val unit = SpanStyle(fontSize = (size * 0.5).sp)
    Text(
        buildAnnotatedString {
            if (minutes == null) append("–")
            else {
                append("${minutes / 60}"); withStyle(unit) { append("h ") }
                append("%02d".format(minutes % 60)); withStyle(unit) { append("m") }
            }
        },
        style = HL.type.stat.copy(fontSize = size.sp, lineHeight = (size * 1.02).sp), color = color, modifier = modifier, maxLines = 1,
    )
}
