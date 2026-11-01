package dev.heartline.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.heartline.ui.Icons
import dev.heartline.ui.theme.HL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val Gutter = 20.dp

/** A scrolling screen with a display-face title, optional back arrow and trailing actions. */
@Composable
fun Screen(
    title: String?,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = Gutter),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()) {
        if (title != null || onBack != null) {
            Row(Modifier.fillMaxWidth().padding(start = if (onBack != null) 6.dp else Gutter, end = Gutter, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconButton(onClick = onBack) { Icon(Icons.back, contentDescription = "Back", tint = HL.colors.ink) }
                }
                if (title != null) Text(title, style = HL.type.title, color = HL.colors.ink, modifier = Modifier.weight(1f))
                else Spacer(Modifier.weight(1f))
                trailing?.invoke()
            }
            if (subtitle != null) Text(subtitle, style = HL.type.small, color = HL.colors.inkSoft, modifier = Modifier.padding(horizontal = Gutter, vertical = 4.dp))
            Spacer(Modifier.height(12.dp))
        }
        Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(0.dp), content = content)
        Spacer(Modifier.height(32.dp))
    }
}

/** A tappable list row with an optional detail line and chevron. */
@Composable
fun NavRow(title: String, detail: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = HL.type.bodyStrong, color = HL.colors.ink)
            if (detail != null) Text(detail, style = HL.type.small, color = HL.colors.inkSoft)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.chevron, contentDescription = null, tint = HL.colors.inkSoft, modifier = Modifier.size(18.dp))
    }
    HorizontalDivider(color = HL.colors.line)
}

@Composable
fun Gap(h: Int = 16) = Spacer(Modifier.height(h.dp))

private val zone get() = ZoneId.systemDefault()
fun timeOf(ts: Long): String = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochSecond(ts).atZone(zone))
fun dateTimeOf(ts: Long): String = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm").format(Instant.ofEpochSecond(ts).atZone(zone))
fun dayLabel(d: LocalDate): String {
    val today = LocalDate.now(zone)
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> DateTimeFormatter.ofPattern("EEE d").format(d)
    }
}
fun agoOf(ts: Long): String {
    val s = System.currentTimeMillis() / 1000 - ts
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> "${s / 86400} d ago"
    }
}
