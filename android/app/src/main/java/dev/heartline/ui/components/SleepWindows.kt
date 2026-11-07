package dev.heartline.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.heartline.app.NightSpan
import dev.heartline.ui.theme.HL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** Hours from midnight of the morning [d] the night ended (negative = the evening before). */
private fun hoursFrom(ts: Long, d: java.time.LocalDate, zone: ZoneId): Double = (ts - d.atStartOfDay(zone).toEpochSecond()) / 3600.0

/** A clock time for hours-from-midnight, e.g. -1.5 -> "22:30". */
fun clockOf(h: Double): String {
    val m = Math.floorMod(Math.round(h * 60).toInt(), 24 * 60)
    return "%02d:%02d".format(m / 60, m % 60)
}

/** Median of [v]; null when empty. */
private fun medianOf(v: List<Double>): Double? = v.sorted().let { if (it.isEmpty()) null else if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }

/** Usual bedtime and wake time (medians, hours from midnight) over [nights]. */
fun usualWindow(nights: List<NightSpan>, zone: ZoneId = ZoneId.systemDefault()): Pair<Double, Double>? {
    val s = nights.mapNotNull { n -> n.start?.let { hoursFrom(it, n.date, zone) } }
    val e = nights.mapNotNull { n -> n.end?.let { hoursFrom(it, n.date, zone) } }
    return medianOf(s)?.let { a -> medianOf(e)?.let { b -> a to b } }
}

/**
 * Each night as a bar from falling asleep to waking, on a clock axis (evening at the top, morning at the bottom), so how
 * regular the nights are, and how much they overlap, shows at a glance. Dashed lines mark the usual (median) bedtime
 * and wake time. Touch or drag for a night's times and hours asleep.
 */
@Composable
fun SleepWindows(nights: List<NightSpan>, color: Color, modifier: Modifier = Modifier, height: Dp = 230.dp) {
    val c = HL.colors
    val tm = rememberTextMeasurer()
    val zone = ZoneId.systemDefault()
    var picked by remember(nights) { mutableStateOf<Int?>(null) }
    val spans = nights.map { n -> if (n.start != null && n.end != null) hoursFrom(n.start, n.date, zone) to hoursFrom(n.end, n.date, zone) else null }
    val usual = usualWindow(nights, zone)
    Column(modifier.fillMaxWidth()) {
        val sel = picked?.let { nights.getOrNull(it) }
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            if (sel != null) {
                val sp = spans[picked!!]
                Column {
                    Text("Night ending " + sel.date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)), style = HL.type.label, color = c.inkSoft)
                    Text(if (sp == null) "No sleep recorded" else "${clockOf(sp.first)} → ${clockOf(sp.second)}  ·  ${sel.asleepMin?.let { hm(it) } ?: "–"} asleep" +
                        if (sel.ongoing) ", so far" else "", style = HL.type.bodyStrong, color = c.ink)
                }
            } else MutedText("Each bar runs from falling asleep to waking. Dashed lines: your usual bedtime and wake time. Touch a night for its times.")
        }
        Spacer(Modifier.height(6.dp))
        val valid = spans.filterNotNull()
        Canvas(Modifier.fillMaxWidth().height(height)
            .semantics { contentDescription = usual?.let { "Usual sleep ${clockOf(it.first)} to ${clockOf(it.second)} over ${valid.size} nights" } ?: "No nights recorded" }
            .pointerInput(nights) { detectTapGestures { o -> val i = ((o.x / (size.width - 44.dp.toPx())) * nights.size).toInt().coerceIn(0, nights.lastIndex); picked = if (picked == i) null else i } }
            .pointerInput(nights) { detectHorizontalDragGestures { ch, _ -> picked = ((ch.position.x / (size.width - 44.dp.toPx())) * nights.size).toInt().coerceIn(0, nights.lastIndex); ch.consume() } }) {
            if (nights.isEmpty() || valid.isEmpty()) return@Canvas
            val axisW = 44.dp.toPx(); val padB = 18.dp.toPx(); val padT = 4.dp.toPx()
            // Whole hours around every night, at least 8 hours tall.
            var top = floor(valid.minOf { it.first }); var bottom = ceil(valid.maxOf { it.second })
            if (bottom - top < 8) { val mid = (top + bottom) / 2; top = floor(mid - 4); bottom = top + 8 }
            val plotH = size.height - padB - padT
            val slot = (size.width - axisW) / nights.size
            fun x(i: Int) = slot * i + slot / 2
            fun y(h: Double) = padT + ((h - top) / (bottom - top)).toFloat() * plotH
            // Gridlines only at the labelled clock times.
            val every = if (bottom - top > 12) 3 else 2
            var h = ceil(top / every) * every
            while (h <= bottom) {
                drawLine(c.line, Offset(0f, y(h)), Offset(size.width - axisW, y(h)), 1f)
                label(tm, clockOf(h), size.width - axisW + 6.dp.toPx(), y(h) - 7.dp.toPx(), c.inkSoft)
                h += every
            }
            usual?.let { (a, b) ->
                listOf(a, b).forEach { v -> drawLine(c.inkSoft, Offset(0f, y(v)), Offset(size.width - axisW, y(v)), 1.4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 7f))) }
            }
            val bw = max(5f, slot * 0.56f)
            spans.forEachIndexed { i, sp ->
                if (sp == null) {
                    if (i > 0 && i < nights.lastIndex) drawCircle(c.inkSoft, 2.5.dp.toPx(), Offset(x(i), size.height - padB - 2.dp.toPx()), style = Stroke(1.dp.toPx()))
                    return@forEachIndexed
                }
                val alpha = when { picked == i -> 1f; picked != null -> 0.45f; nights[i].ongoing -> 0.5f; else -> 0.85f }
                drawRoundRect(color.copy(alpha = alpha), Offset(x(i) - bw / 2, y(sp.first)), Size(bw, max(3f, y(sp.second) - y(sp.first))), CornerRadius(bw / 3))
            }
            val labelEvery = if (nights.size <= 14) 1 else 2
            nights.forEachIndexed { i, n ->
                if (i % labelEvery != 0 && i != nights.lastIndex) return@forEachIndexed
                label(tm, n.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.ENGLISH), x(i), size.height - padB / 2, if (i == nights.lastIndex) c.ink else c.inkSoft, center = true)
            }
        }
        Row(Modifier.fillMaxWidth().padding(end = 44.dp)) {
            Text(nights.first().date.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)), style = HL.type.small.copy(fontSize = 11.sp), color = c.inkSoft, modifier = Modifier.weight(1f))
            Text(nights.last().date.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)), style = HL.type.small.copy(fontSize = 11.sp), color = c.inkSoft)
        }
        val missing = spans.drop(1).dropLast(1).count { it == null }
        if (missing > 0) Text("Hollow dots: $missing night${if (missing > 1) "s" else ""} with no sleep recorded.", style = HL.type.small.copy(fontSize = 12.sp), color = c.inkSoft)
        if (nights.any { it.ongoing }) Text("Faded: last night may still be going on.", style = HL.type.small.copy(fontSize = 12.sp), color = c.inkSoft)
    }
}
