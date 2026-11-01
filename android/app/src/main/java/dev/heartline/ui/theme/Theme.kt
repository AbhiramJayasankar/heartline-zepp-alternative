package dev.heartline.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.heartline.R

/** The ECG-paper palette shared with the PC dashboard (web/style.css). */
@Immutable
data class Palette(
    val paper: Color,
    val gridMinor: Color,
    val gridMajor: Color,
    val ink: Color,
    val inkSoft: Color,
    val pulse: Color,
    val wash: Color,
    val line: Color,
    val surface: Color,
    val ok: Color,
    val warn: Color,
    val sleepDeep: Color,
    val sleepLight: Color,
    val sleepRem: Color,
    val idle: Color,
    val isDark: Boolean,
)

val LightPalette = Palette(
    paper = Color(0xFFFCF6F4), gridMinor = Color(0xFFF6E0DC), gridMajor = Color(0xFFECBDB6),
    ink = Color(0xFF1D2742), inkSoft = Color(0xFF5D6684), pulse = Color(0xFFC8372D),
    wash = Color(0xFFF4E9E6), line = Color(0xFFE7D3CF), surface = Color(0xFFFFFDFC),
    ok = Color(0xFF2F7A4F), warn = Color(0xFFA8641A),
    sleepDeep = Color(0xFF2B3870), sleepLight = Color(0xFF8D97C4), sleepRem = Color(0xFFC79BC3), idle = Color(0xFFEAD8D4),
    isDark = false,
)

val DarkPalette = Palette(
    paper = Color(0xFF121829), gridMinor = Color(0xFF1A2239), gridMajor = Color(0xFF28324F),
    ink = Color(0xFFEDF0F8), inkSoft = Color(0xFF99A3C0), pulse = Color(0xFFFF7D72),
    wash = Color(0xFF19213A), line = Color(0xFF273152), surface = Color(0xFF161D33),
    ok = Color(0xFF6FCF97), warn = Color(0xFFF0B35E),
    sleepDeep = Color(0xFF96A2EA), sleepLight = Color(0xFF4F5A8E), sleepRem = Color(0xFFB88AB4), idle = Color(0xFF252F4F),
    isDark = true,
)

val Display = FontFamily(
    Font(R.font.display_500, FontWeight.Medium),
    Font(R.font.display_700, FontWeight.Bold),
    Font(R.font.display_800, FontWeight.ExtraBold),
)

val Text = FontFamily(
    Font(R.font.text_400, FontWeight.Normal),
    Font(R.font.text_500, FontWeight.Medium),
    Font(R.font.text_600, FontWeight.SemiBold),
    Font(R.font.text_700, FontWeight.Bold),
)

/** Type scale: condensed display face for numbers and headings, grotesk for everything read. */
@Immutable
data class HeartlineType(
    val hero: TextStyle = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 120.sp, lineHeight = 104.sp),
    val score: TextStyle = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 48.sp, lineHeight = 46.sp),
    val stat: TextStyle = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 30.sp),
    val title: TextStyle = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 36.sp),
    val section: TextStyle = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 28.sp),
    val body: TextStyle = TextStyle(fontFamily = Text, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
    val bodyStrong: TextStyle = TextStyle(fontFamily = Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 23.sp),
    val label: TextStyle = TextStyle(fontFamily = Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 17.sp),
    val small: TextStyle = TextStyle(fontFamily = Text, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    val button: TextStyle = TextStyle(fontFamily = Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }
val LocalType = staticCompositionLocalOf { HeartlineType() }

object HL {
    val colors: Palette @Composable get() = LocalPalette.current
    val type: HeartlineType @Composable get() = LocalType.current
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Composable
fun HeartlineTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val p = if (dark) DarkPalette else LightPalette
    // Material components (dialogs, text fields, switches) pick up the same tokens.
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.pulse, onPrimary = p.paper, secondary = p.ink, background = p.paper, onBackground = p.ink,
            surface = p.surface, onSurface = p.ink, surfaceVariant = p.wash, onSurfaceVariant = p.inkSoft,
            outline = p.line, error = p.pulse,
        )
    } else {
        lightColorScheme(
            primary = p.ink, onPrimary = p.paper, secondary = p.pulse, background = p.paper, onBackground = p.ink,
            surface = p.surface, onSurface = p.ink, surfaceVariant = p.wash, onSurfaceVariant = p.inkSoft,
            outline = p.line, error = p.pulse,
        )
    }
    CompositionLocalProvider(LocalPalette provides p, LocalType provides HeartlineType()) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
