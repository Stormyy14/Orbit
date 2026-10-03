package app.orbit.ui

import android.content.res.Configuration
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orbit.R
import app.orbit.core.Accent
import app.orbit.core.Browser
import app.orbit.core.Corners
import app.orbit.core.FontChoice
import app.orbit.core.Settings
import app.orbit.core.ThemeMode

/**
 * Orbit design tokens. Monochrome first: one canvas, hairline borders, high-contrast text.
 * Colour is reserved for meaning — a space's identity, ghost mode, success, warning, danger —
 * plus the accent the user picks (Mono by default, which is just the text colour).
 */
@Immutable
data class Palette(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val field: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val blue: Color,
    val green: Color,
    val amber: Color,
    val red: Color,
    val purple: Color,
    /** Primary buttons, switches, selections. */
    val accent: Color = text,
    val onAccent: Color = bg,
    val mono: Boolean = true,
)

val DarkPalette = Palette(
    dark = true,
    bg = Color(0xFF0A0A0A),
    surface = Color(0xFF111111),
    field = Color(0xFF1A1A1A),
    border = Color(0xFF242424),
    borderStrong = Color(0xFF3A3A3A),
    text = Color(0xFFEDEDED),
    text2 = Color(0xFFA1A1A1),
    text3 = Color(0xFF7C7C7C),
    blue = Color(0xFF52A8FF),
    green = Color(0xFF62C073),
    amber = Color(0xFFFFB224),
    red = Color(0xFFFF6166),
    purple = Color(0xFFBF7AF0),
)

/** Dark, on a true-black canvas (saves power on OLED screens). */
val BlackPalette = DarkPalette.copy(
    bg = Color(0xFF000000),
    surface = Color(0xFF0A0A0A),
    field = Color(0xFF141414),
    border = Color(0xFF1F1F1F),
    borderStrong = Color(0xFF333333),
    onAccent = Color(0xFF000000),
)

val LightPalette = Palette(
    dark = false,
    bg = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    field = Color(0xFFF2F2F2),
    border = Color(0xFFEBEBEB),
    borderStrong = Color(0xFFD4D4D4),
    text = Color(0xFF171717),
    text2 = Color(0xFF5E5E5E),
    text3 = Color(0xFF8F8F8F),
    blue = Color(0xFF0068D6),
    green = Color(0xFF297A3A),
    amber = Color(0xFFA35200),
    red = Color(0xFFDA2F35),
    purple = Color(0xFF7D3CB5),
)

/** The parts of [Settings] that change how the app itself is drawn. */
@Immutable
data class Look(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val accent: Accent = Accent.MONO,
    val corners: Corners = Corners.SOFT,
    val font: FontChoice = FontChoice.GEIST,
    val textScale: Float = 1f,
) {
    companion object {
        fun of(s: Settings) = Look(s.theme, s.accent, s.corners, s.font, s.textScale)
    }
}

fun paletteFor(look: Look, systemDark: Boolean): Palette {
    val base = when (look.theme) {
        ThemeMode.LIGHT -> LightPalette
        ThemeMode.DARK -> DarkPalette
        ThemeMode.BLACK -> BlackPalette
        ThemeMode.SYSTEM -> if (systemDark) DarkPalette else LightPalette
    }
    if (look.accent == Accent.MONO) return base
    val a = Color(if (base.dark) look.accent.dark else look.accent.light)
    return base.copy(accent = a, onAccent = if (a.luminance() > 0.45f) Color(0xFF0A0A0A) else Color.White, mono = false)
}

object Orb {
    /** What the system asks for; only used when the theme is "System". */
    var systemDark by mutableStateOf(true)
        private set
    var look by mutableStateOf(Look())
        private set

    /** Backed by snapshot state, so every read recomposes when the theme or look changes. */
    val palette: Palette by derivedStateOf { paletteFor(look, systemDark) }
    val small: Shape by derivedStateOf { RoundedCornerShape(look.corners.small.dp) }
    val large: Shape by derivedStateOf { RoundedCornerShape(look.corners.large.dp) }
    val tiny: Shape by derivedStateOf { RoundedCornerShape((look.corners.small - 2).coerceAtLeast(1).dp) }
    val font: FontFamily by derivedStateOf {
        when (look.font) {
            FontChoice.GEIST -> GeistFamily
            FontChoice.SYSTEM -> FontFamily.Default
            FontChoice.SERIF -> FontFamily.Serif
            FontChoice.MONO -> GeistMono
        }
    }

    val Bg get() = palette.bg
    val Surface get() = palette.surface
    val Field get() = palette.field
    val Border get() = palette.border
    val BorderStrong get() = palette.borderStrong
    val Text get() = palette.text
    val Text2 get() = palette.text2
    val Text3 get() = palette.text3
    /** Primary actions: the accent, or the text colour (black on light, white on dark) in Mono. */
    val Contrast get() = palette.accent
    val OnContrast get() = palette.onAccent
    /** Quiet things that may carry the accent (loading spinner, pinned sites): grey in Mono. */
    val Tint get() = if (palette.mono) palette.text2 else palette.accent
    val Blue get() = palette.blue
    val Green get() = palette.green
    val Amber get() = palette.amber
    val Red get() = palette.red
    val Ghost get() = palette.purple

    fun applySystem(config: Configuration) {
        systemDark = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun applyLook(settings: Settings) {
        look = Look.of(settings)
    }
}

/** The current space's identity colour (or ghost purple). Used sparingly: dots, focus, selection. */
val LocalAccent = compositionLocalOf { Color(0xFF0A72EF) }
val LocalBrowser = compositionLocalOf<Browser> { error("No browser") }

@OptIn(ExperimentalTextApi::class)
private fun geist(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val GeistFamily = FontFamily(
    geist(R.font.geist, 400), geist(R.font.geist, 500), geist(R.font.geist, 600), geist(R.font.geist, 700),
)
val GeistMono = FontFamily(geist(R.font.geist_mono, 400), geist(R.font.geist_mono, 500))

/** The app's typeface: Geist unless the user picked another one in Customize. */
val Geist: FontFamily get() = Orb.font

/** Tabular figures so counts and timers don't jitter. */
const val TNUM = "tnum"

private fun typography(f: FontFamily) = Typography(
    displayLarge = TextStyle(fontFamily = f, fontWeight = FontWeight.SemiBold, fontSize = 48.sp, lineHeight = 52.sp, letterSpacing = (-2).sp, fontFeatureSettings = TNUM),
    headlineMedium = TextStyle(fontFamily = f, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.8).sp),
    titleLarge = TextStyle(fontFamily = f, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.5).sp),
    titleMedium = TextStyle(fontFamily = f, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp),
    titleSmall = TextStyle(fontFamily = f, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.1).sp),
    bodyLarge = TextStyle(fontFamily = f, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = f, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = f, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = f, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = f, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = f, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)

val MonoSmall = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)
val MonoBody = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)

@Composable
fun OrbitTheme(content: @Composable () -> Unit) {
    val p = Orb.palette
    val scheme = if (p.dark) {
        darkColorScheme(
            primary = p.accent, onPrimary = p.onAccent, secondary = p.text2,
            background = p.bg, surface = p.surface, surfaceContainer = p.surface,
            surfaceContainerHigh = p.field, surfaceContainerLow = p.surface,
            onSurface = p.text, onSurfaceVariant = p.text2, outline = p.borderStrong, outlineVariant = p.border,
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = p.onAccent, secondary = p.text2,
            background = p.bg, surface = p.surface, surfaceContainer = p.surface,
            surfaceContainerHigh = p.field, surfaceContainerLow = p.surface,
            onSurface = p.text, onSurfaceVariant = p.text2, outline = p.borderStrong, outlineVariant = p.border,
        )
    }
    val font = Orb.font
    val type = remember(font) { typography(font) }
    val density = LocalDensity.current
    val scale = Orb.look.textScale
    val scaled = remember(density, scale) { Density(density.density, density.fontScale * scale) }
    MaterialTheme(colorScheme = scheme, typography = type) {
        CompositionLocalProvider(LocalContentColor provides p.text, LocalDensity provides scaled, content = content)
    }
}
