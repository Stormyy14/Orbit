package app.orbitline.ui

import android.content.res.Configuration
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.orbitline.R
import app.orbitline.core.Browser

/**
 * Orbit design tokens. Monochrome first: one canvas, hairline borders, high-contrast text.
 * Colour is reserved for meaning — a space's identity, ghost mode, success, warning, danger.
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

object Orb {
    /** Backed by snapshot state, so every read recomposes when the system theme flips. */
    var palette by mutableStateOf(DarkPalette)

    val Bg get() = palette.bg
    val Surface get() = palette.surface
    val Field get() = palette.field
    val Border get() = palette.border
    val BorderStrong get() = palette.borderStrong
    val Text get() = palette.text
    val Text2 get() = palette.text2
    val Text3 get() = palette.text3
    /** Primary actions are drawn in the text colour: black on light, white on dark. */
    val Contrast get() = palette.text
    val OnContrast get() = palette.bg
    val Blue get() = palette.blue
    val Green get() = palette.green
    val Amber get() = palette.amber
    val Red get() = palette.red
    val Ghost get() = palette.purple

    fun applySystem(config: Configuration) {
        val night = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        palette = if (night) DarkPalette else LightPalette
    }
}

/** The current space's identity colour (or ghost purple). Used sparingly: dots, focus, selection. */
val LocalAccent = compositionLocalOf { Color(0xFF0A72EF) }
val LocalBrowser = compositionLocalOf<Browser> { error("No browser") }

@OptIn(ExperimentalTextApi::class)
private fun geist(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Geist = FontFamily(
    geist(R.font.geist, 400), geist(R.font.geist, 500), geist(R.font.geist, 600), geist(R.font.geist, 700),
)
val GeistMono = FontFamily(geist(R.font.geist_mono, 400), geist(R.font.geist_mono, 500))

/** Tabular figures so counts and timers don't jitter. */
const val TNUM = "tnum"

private val OrbTypography = Typography(
    displayLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 48.sp, lineHeight = 52.sp, letterSpacing = (-2).sp, fontFeatureSettings = TNUM),
    headlineMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.8).sp),
    titleLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.5).sp),
    titleMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp),
    titleSmall = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.1).sp),
    bodyLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)

val MonoSmall = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)
val MonoBody = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)

@Composable
fun OrbitTheme(content: @Composable () -> Unit) {
    val p = Orb.palette
    val scheme = if (p.dark) {
        darkColorScheme(
            primary = p.text, onPrimary = p.bg, secondary = p.text2,
            background = p.bg, surface = p.surface, surfaceContainer = p.surface,
            surfaceContainerHigh = p.field, surfaceContainerLow = p.surface,
            onSurface = p.text, onSurfaceVariant = p.text2, outline = p.borderStrong, outlineVariant = p.border,
        )
    } else {
        lightColorScheme(
            primary = p.text, onPrimary = p.bg, secondary = p.text2,
            background = p.bg, surface = p.surface, surfaceContainer = p.surface,
            surfaceContainerHigh = p.field, surfaceContainerLow = p.surface,
            onSurface = p.text, onSurfaceVariant = p.text2, outline = p.borderStrong, outlineVariant = p.border,
        )
    }
    MaterialTheme(colorScheme = scheme, typography = OrbTypography) {
        CompositionLocalProvider(LocalContentColor provides p.text, content = content)
    }
}
