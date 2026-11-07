package dev.heartline.ui.components

import dev.heartline.ui.theme.raised

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.heartline.app.ConnectionUi
import dev.heartline.app.LinkState
import dev.heartline.ui.theme.HL

/** ECG chart paper: a 1 mm-style minor grid with a heavier line every fifth step. Only behind live signals. */
fun Modifier.ecgPaper(minor: Color, major: Color, step: Dp = 6.dp): Modifier = drawBehind {
    val s = step.toPx()
    val w = size.width
    val h = size.height
    var i = 0
    var x = 0f
    while (x <= w) {
        drawLine(if (i % 5 == 0) major else minor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
        x += s; i++
    }
    i = 0
    var y = 0f
    while (y <= h) {
        drawLine(if (i % 5 == 0) major else minor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        y += s; i++
    }
}

@Composable
fun Modifier.paper(): Modifier = ecgPaper(HL.colors.gridMinor, HL.colors.gridMajor)

enum class ButtonStyle { PRIMARY, PULSE, QUIET }

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.PRIMARY,
    enabled: Boolean = true,
    pressed: Boolean = false,
) {
    val c = HL.colors
    val (bg, fg, border) = when (style) {
        ButtonStyle.PRIMARY -> Triple(c.ink, c.paper, c.ink)
        ButtonStyle.PULSE -> if (pressed) Triple(c.pulse, c.surface, c.pulse) else Triple(c.surface, c.pulse, c.pulse)
        ButtonStyle.QUIET -> Triple(Color.Transparent, c.ink, c.line)
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        color = bg,
        contentColor = fg,
        border = BorderStroke(1.5.dp, border),
        modifier = modifier,
    ) {
        Box(Modifier.padding(horizontal = if (style == ButtonStyle.QUIET) 14.dp else 20.dp, vertical = if (style == ButtonStyle.QUIET) 8.dp else 12.dp), contentAlignment = Alignment.Center) {
            Text(text, style = HL.type.button, color = if (enabled) fg else fg.copy(alpha = 0.4f), maxLines = 1, softWrap = false)
        }
    }
}

/** Segmented choice, used for ranges and measurement lengths. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = HL.colors
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(50))
            .padding(3.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) c.ink else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(label, style = HL.type.label, color = if (on) c.paper else c.inkSoft)
            }
        }
    }
}

/** A labelled number in the display face, with an optional small unit. */
@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, unit: String? = null, big: Boolean = false, color: Color = HL.colors.ink) {
    Column(modifier) {
        Text(label, style = HL.type.label, color = HL.colors.inkSoft)
        Text(
            buildAnnotatedString {
                append(value)
                if (unit != null && value != "–") withStyle(SpanStyle(fontSize = HL.type.label.fontSize, color = HL.colors.inkSoft, fontFamily = HL.type.label.fontFamily)) { append(" $unit") }
            },
            style = if (big) HL.type.score else HL.type.stat,
            color = color,
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text, style = HL.type.section, color = HL.colors.ink, modifier = Modifier.weight(1f, fill = false).semantics { heading() })
        if (trailing != null) Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, wash: Boolean = false, padding: PaddingValues = PaddingValues(18.dp), content: @Composable ColumnScope.() -> Unit) {
    val c = HL.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (wash) c.raised else c.surface)
            .border(1.dp, c.line, RoundedCornerShape(18.dp))
            .padding(padding),
        content = content,
    )
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

@Composable
fun ConnectionChip(conn: ConnectionUi, modifier: Modifier = Modifier) {
    val c = HL.colors
    val (color, text) = when (conn.state) {
        LinkState.CONNECTED -> c.ok to "Connected"
        LinkState.SCANNING -> c.warn to "Looking for the watch"
        LinkState.CONNECTING -> c.warn to "Connecting"
        LinkState.AUTHENTICATING -> c.warn to "Unlocking"
        LinkState.ERROR -> c.pulse to "Not reachable"
        LinkState.DISCONNECTED -> c.gridMajor to "Not connected"
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Dot(color)
        Spacer(Modifier.width(8.dp))
        Text(text, style = HL.type.label, color = if (conn.state == LinkState.ERROR) c.pulse else c.inkSoft)
    }
}

@Composable
fun BatteryGlyph(level: Int, charging: Boolean, modifier: Modifier = Modifier) {
    val c = HL.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 24.dp, height = 12.dp)
                .border(1.5.dp, c.ink, RoundedCornerShape(3.dp))
                .padding(2.dp),
        ) {
            Box(Modifier.fillMaxWidth(level.coerceIn(0, 100) / 100f).height(8.dp).background(if (level <= 20) c.pulse else c.ink, RoundedCornerShape(1.dp)))
        }
        Spacer(Modifier.width(6.dp))
        Text("$level%${if (charging) " charging" else ""}", style = HL.type.label, color = c.ink)
    }
}

@Composable
fun MutedText(text: String, modifier: Modifier = Modifier) = Text(text, style = HL.type.small, color = HL.colors.inkSoft, modifier = modifier)

fun fmtInt(v: Number?): String = v?.let { "%,d".format(it.toLong()) } ?: "–"
fun fmtOne(v: Double?): String = v?.let { "%.1f".format(it) } ?: "–"
fun fmtHm(min: Int?): String = min?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" } ?: "–"
