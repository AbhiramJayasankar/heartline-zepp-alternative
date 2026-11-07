package dev.heartline.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.border
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.heartline.ui.Icons
import dev.heartline.ui.theme.HL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val Gutter = 20.dp

/** Tabs keep one scroll position each, so coming back from a sub-screen lands where you left. Sub-screens get null. */
val LocalTabScroll = androidx.compose.runtime.staticCompositionLocalOf<ScrollState?> { null }
/** True while a banner (a heart check, a capture, find phone) sits under the status bar, so screens don't leave room for it again. */
val LocalStatusBarCovered = androidx.compose.runtime.compositionLocalOf { false }

/** Pull down to refresh on the tabs: whether a refresh is running, and what pulling starts. */
class PageRefresh(val refreshing: Boolean, val onRefresh: () -> Unit)
val LocalPageRefresh = androidx.compose.runtime.compositionLocalOf<PageRefresh?> { null }

/**
 * A full-height scrolling page. Headers may draw behind the status bar at rest; once the page scrolls,
 * a paper scrim fades in under the status bar so content never runs into the clock and icons.
 */
/** Where each tab was left, kept apart from its ScrollState, which clamps to the top while a returning tab is still loading. */
private val leftAt = java.util.WeakHashMap<ScrollState, Int>()

@Composable
fun ScrollPage(state: ScrollState = LocalTabScroll.current ?: rememberScrollState(), content: @Composable ColumnScope.() -> Unit) {
    if (state === LocalTabScroll.current) {
        androidx.compose.runtime.DisposableEffect(state) { onDispose { leftAt[state] = state.value } }
        // Coming back to a tab: once its content is tall enough again, put it back where it was left.
        androidx.compose.runtime.LaunchedEffect(state) {
            val want = leftAt[state] ?: return@LaunchedEffect
            if (want == 0 || state.value >= want) return@LaunchedEffect
            kotlinx.coroutines.withTimeoutOrNull(3000) { androidx.compose.runtime.snapshotFlow { state.maxValue }.first { it >= want } }
            if (state.value < want) state.scrollTo(minOf(want, state.maxValue))
        }
    }
    val scrolled by remember(state) { derivedStateOf { state.value > 0 } }
    val alpha by animateFloatAsState(if (scrolled) 0.96f else 0f, label = "scrim")
    val refresh = LocalPageRefresh.current
    Box(Modifier.fillMaxSize()) {
        if (refresh == null) Column(Modifier.fillMaxSize().verticalScroll(state), content = content)
        else androidx.compose.material3.pulltorefresh.PullToRefreshBox(refresh.refreshing, refresh.onRefresh, Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(state), content = content)
        }
        if (!LocalStatusBarCovered.current) Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(HL.colors.paper.copy(alpha = alpha)))
    }
}

/** A scrolling screen with a display-face title, optional back arrow and trailing actions. */
@Composable
fun Screen(
    title: String?,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = Gutter),
    /** With an icon, the subtitle sits beside a tinted badge in a header card, so each inner page has its own face. */
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    tint: androidx.compose.ui.graphics.Color? = null,
    /** Pass one in to scroll the page from outside (to a focused section, say). */
    scroll: ScrollState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ScrollPage(scroll ?: LocalTabScroll.current ?: rememberScrollState()) {
        if (!LocalStatusBarCovered.current) Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        if (title != null || onBack != null) {
            Row(Modifier.fillMaxWidth().padding(start = if (onBack != null) 6.dp else Gutter, end = Gutter, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconButton(onClick = onBack) { Icon(Icons.back, contentDescription = "Back", tint = HL.colors.ink) }
                }
                if (title != null) Text(title, style = HL.type.title, color = HL.colors.ink, modifier = Modifier.weight(1f).semantics { heading() })
                else Spacer(Modifier.weight(1f))
                trailing?.invoke()
            }
            if (icon != null) {
                val c = HL.colors; val t = tint ?: c.pulse
                val shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                Row(Modifier.padding(horizontal = Gutter, vertical = 10.dp).fillMaxWidth().clip(shape)
                    .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(t.copy(alpha = 0.16f), c.surface)))
                    .border(1.dp, c.line, shape).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).clip(androidx.compose.foundation.shape.CircleShape).background(t.copy(alpha = 0.20f)), contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = t, modifier = Modifier.size(26.dp))
                    }
                    if (subtitle != null) {
                        Spacer(Modifier.width(14.dp))
                        Text(subtitle, style = HL.type.small.copy(fontSize = 14.sp, lineHeight = 20.sp), color = c.ink, modifier = Modifier.weight(1f))
                    }
                }
            } else if (subtitle != null) Text(subtitle, style = HL.type.small, color = HL.colors.inkSoft, modifier = Modifier.padding(horizontal = Gutter, vertical = 4.dp))
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

/** Rows grouped on one rounded card; the last row's divider is tucked away under the card's edge. */
@Composable
fun NavGroup(content: @Composable ColumnScope.() -> Unit) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(shape).background(HL.colors.surface)
        .border(1.dp, HL.colors.line, shape).padding(horizontal = 16.dp)
        .layout { m, c -> val p = m.measure(c); layout(p.width, (p.height - 1.dp.roundToPx()).coerceAtLeast(0)) { p.place(0, 0) } }
        .clipToBounds(), content = content)
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

/**
 * Marks a card as a section links can jump to: when [nav]'s focus is [key], the tab scrolls it near the top and outlines
 * it for a moment. Only works on tab pages (they share [LocalTabScroll]).
 */
@Composable
fun Modifier.section(nav: dev.heartline.ui.Navigator, key: String): Modifier {
    val scroll = LocalTabScroll.current ?: return this
    val density = androidx.compose.ui.platform.LocalDensity.current
    var top by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Float?>(null) }
    var flash by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val focused = nav.focus == key
    // Keyed on the position becoming known, not on its value, which changes while scrolling.
    androidx.compose.runtime.LaunchedEffect(focused, top != null) {
        if (!focused || top == null) return@LaunchedEffect
        kotlinx.coroutines.delay(150)
        val y = top ?: return@LaunchedEffect
        scroll.animateScrollTo((scroll.value + y - with(density) { 96.dp.toPx() }).toInt().coerceAtLeast(0))
        flash = true
        // Done: forget the focus now, so coming back to this tab later keeps wherever you scrolled.
        nav.focus = null
    }
    androidx.compose.runtime.LaunchedEffect(flash) { if (flash) { kotlinx.coroutines.delay(1800); flash = false } }
    return this.then(androidx.compose.ui.Modifier.onGloballyPositioned { top = it.positionInRoot().y })
        .then(if (flash) androidx.compose.ui.Modifier.border(2.dp, HL.colors.pulse, androidx.compose.foundation.shape.RoundedCornerShape(18.dp)) else androidx.compose.ui.Modifier)
}

/**
 * A section title that links can jump to. Its outline reaches 12 dp past the text on each side (into the page gutter)
 * with room above and below, so the highlight frames the title like a card instead of clipping the letters.
 */
@Composable
fun FocusTitle(nav: dev.heartline.ui.Navigator, key: String, title: String) {
    val bleed = 12.dp
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxWidth()
            .layout { m, c ->
                val extra = bleed.roundToPx()
                val p = m.measure(c.copy(minWidth = c.maxWidth + 2 * extra, maxWidth = c.maxWidth + 2 * extra))
                layout(c.maxWidth, p.height) { p.place(-extra, 0) }
            }
            .section(nav, key)
            .padding(horizontal = bleed, vertical = 10.dp),
    ) { dev.heartline.ui.components.SectionTitle(title) }
}
