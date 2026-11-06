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
import androidx.compose.ui.unit.dp
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
    /** Blood oxygen and body readings. */
    val oxygen: Color = Color(0xFF2A7FA8),
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
    isDark = true, oxygen = Color(0xFF6CC4E8),
)

/** True black for OLED screens: the page and header are #000 (pixels off); cards are a near-black grey, text and accents bright. */
val BlackPalette = Palette(
    paper = Color(0xFF000000), gridMinor = Color(0xFF0B0B0B), gridMajor = Color(0xFF1C1C1C),
    ink = Color(0xFFF2F2F2), inkSoft = Color(0xFF9B9B9B), pulse = Color(0xFFFF5A4E),
    wash = Color(0xFF000000), line = Color(0xFF242424), surface = Color(0xFF0E0E0E),
    ok = Color(0xFF4ADE80), warn = Color(0xFFF5B041),
    sleepDeep = Color(0xFF8E9BFF), sleepLight = Color(0xFF465094), sleepRem = Color(0xFFD08CE0), idle = Color(0xFF1E1E1E),
    isDark = true, oxygen = Color(0xFF4FC3F7),
)

/** Warm dark: espresso and plum with a coral accent. */
val DuskPalette = Palette(
    paper = Color(0xFF191214), gridMinor = Color(0xFF21181B), gridMajor = Color(0xFF33262A),
    ink = Color(0xFFF6ECE7), inkSoft = Color(0xFFB9A7A1), pulse = Color(0xFFFF8A65),
    wash = Color(0xFF221A1D), line = Color(0xFF3A2D31), surface = Color(0xFF211A1D),
    ok = Color(0xFF8CD3A1), warn = Color(0xFFF2C46D),
    sleepDeep = Color(0xFFB4A4F2), sleepLight = Color(0xFF5E5089), sleepRem = Color(0xFFE09AC2), idle = Color(0xFF2E2427),
    isDark = true, oxygen = Color(0xFF7FCBE0),
)

/** Cool light: sage paper and pine ink. */
val SagePalette = Palette(
    paper = Color(0xFFF2F5F0), gridMinor = Color(0xFFE2EADF), gridMajor = Color(0xFFC6D4C1),
    ink = Color(0xFF17251E), inkSoft = Color(0xFF55665C), pulse = Color(0xFFC9402F),
    wash = Color(0xFFE5EDE2), line = Color(0xFFD2DCCE), surface = Color(0xFFFCFDFB),
    ok = Color(0xFF237A4B), warn = Color(0xFFA9620E),
    sleepDeep = Color(0xFF2E4A7D), sleepLight = Color(0xFF8DA2C9), sleepRem = Color(0xFFB98DB8), idle = Color(0xFFDDE5D9),
    isDark = false, oxygen = Color(0xFF1F7A9E),
)

/** A ready-made theme: its name, what it's like, and its palette ([ThemeMode.SYSTEM] has none of its own). */
data class ThemeInfo(val mode: ThemeMode, val name: String, val about: String)
val THEMES = listOf(
    ThemeInfo(ThemeMode.SYSTEM, "Follow phone", "Paper by day, Night when the phone is dark"),
    ThemeInfo(ThemeMode.LIGHT, "Paper", "Warm ECG-paper light"),
    ThemeInfo(ThemeMode.DARK, "Night", "Deep navy"),
    ThemeInfo(ThemeMode.BLACK, "OLED Black", "True black; pixels off on OLED screens"),
    ThemeInfo(ThemeMode.DUSK, "Dusk", "Warm espresso dark"),
    ThemeInfo(ThemeMode.SAGE, "Sage", "Cool green light"),
)

/** The palette for [mode]; [systemDark] decides "Follow phone". */
fun paletteFor(mode: ThemeMode, systemDark: Boolean): Palette = when (mode) {
    ThemeMode.SYSTEM -> if (systemDark) DarkPalette else LightPalette
    ThemeMode.LIGHT -> LightPalette; ThemeMode.DARK -> DarkPalette; ThemeMode.BLACK -> BlackPalette
    ThemeMode.DUSK -> DuskPalette; ThemeMode.SAGE -> SagePalette
}

/** One hue per part of health, used for its page accent, tile and charts. */
enum class Area { SLEEP, HEART, MOVE, STRESS, BODY, RECOVERY }
fun Palette.hue(a: Area): Color = when (a) {
    Area.SLEEP -> sleepDeep; Area.HEART -> pulse; Area.MOVE -> ok; Area.STRESS -> warn; Area.BODY -> oxygen; Area.RECOVERY -> ink
}

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

enum class ThemeMode { SYSTEM, LIGHT, DARK, BLACK, DUSK, SAGE }

@Composable
fun HeartlineTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val p = paletteFor(mode, isSystemInDarkTheme())
    // Material components (dialogs, text fields, switches) pick up the same tokens.
    val scheme = if (p.isDark) {
        darkColorScheme(
            primary = p.pulse, onPrimary = p.paper, secondary = p.ink, background = p.paper, onBackground = p.ink,
            surface = p.surface, onSurface = p.ink, surfaceVariant = p.wash, onSurfaceVariant = p.inkSoft,
            outline = p.line, error = p.pulse, surfaceContainer = p.surface, surfaceContainerHigh = p.surface,
        )
    } else {
        lightColorScheme(
            primary = p.ink, onPrimary = p.paper, secondary = p.pulse, background = p.paper, onBackground = p.ink,
            surface = p.surface, onSurface = p.ink, surfaceVariant = p.wash, onSurfaceVariant = p.inkSoft,
            outline = p.line, error = p.pulse,
        )
    }
    CompositionLocalProvider(LocalPalette provides p, LocalType provides HeartlineType()) {
        MaterialTheme(colorScheme = scheme, shapes = androidx.compose.material3.Shapes(
            extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)), content = content)
    }
}
