package dev.heartline.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.heartline.app.MinuteState
import dev.heartline.app.MinuteUi
import dev.heartline.app.SeriesPoint
import dev.heartline.ui.theme.HL

fun stateName(s: MinuteState): String = when (s) {
    MinuteState.SLEEP_DEEP -> "Deep sleep"; MinuteState.SLEEP_LIGHT -> "Light sleep"; MinuteState.SLEEP_REM -> "REM sleep"
    MinuteState.ACTIVE -> "Active"; MinuteState.IDLE -> "Still"; MinuteState.CHARGING -> "Charging"; MinuteState.NOT_WORN -> "Not worn"
    MinuteState.UNKNOWN -> "No data"
}

/**
 * One day minute by minute, touchable: what you were doing as shaded stretches, steps each minute as bars and heart
 * rate as a line. Touch for the minute's state, steps and heart rate; pinch to zoom; expand for landscape.
 */
@Composable
fun MinuteChart(minutes: List<MinuteUi>, dayStart: Long, modifier: Modifier = Modifier, height: Dp = 200.dp, title: String = "Minute by minute") {
    val c = HL.colors
    val (spans, steps, hr) = remember(minutes, c) {
        val spans = ArrayList<ChartSpan>()
        var runStart = 0L; var runState: MinuteState? = null; var last = 0L
        fun close() {
            val s = runState ?: return
            val col = if (s == MinuteState.NOT_WORN || s == MinuteState.UNKNOWN) c.line.copy(alpha = 0.35f) else (stateColor(s, c) ?: c.line).copy(alpha = 0.22f)
            spans += ChartSpan(runStart.toDouble(), last + 60.0, col, stateName(s))
        }
        minutes.sortedBy { it.ts }.forEach { m ->
            if (m.state != runState || m.ts - last > 120) { close(); runStart = m.ts; runState = m.state }
            last = m.ts
        }
        close()
        Triple(spans, minutes.filter { it.steps > 0 }.map { SeriesPoint(it.ts.toDouble(), it.steps.toDouble()) },
            minutes.mapNotNull { m -> m.hr?.let { SeriesPoint(m.ts.toDouble(), it.toDouble()) } })
    }
    InteractiveChart(
        listOf(ChartLine(hr, c.pulse, "Heart rate", "bpm", width = 1.6.dp, gap = 600.0)),
        dayStart.toDouble(), dayStart + 86_400.0, modifier, height = height, yMin = 0.0,
        bars = listOf(ChartBars(steps, 60.0, c.ok, "Steps", "/min")), spans = spans,
        readoutX = { t -> epochClock(t - (t - dayStart) % 60) }, minSpan = 1800.0,
        idleHint = "Shading: what you were doing. Green bars: steps each minute. Red line: heart rate. Touch for a minute's detail.",
        title = title,
    )
}
