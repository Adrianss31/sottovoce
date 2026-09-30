package it.sottovoce.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import it.sottovoce.app.R
import it.sottovoce.app.data.AppTheme

/**
 * Sottovoce v2 design tokens. Theme colors apply to the library, sub-screens and
 * sheets; the player and the now-playing surfaces use each book's own colors.
 */
@Immutable
data class SvPalette(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val ink: Color,
    val ink2: Color,
    val accent: Color,
    val onAccent: Color,
    val line: Color,
    val dark: Boolean,
) {
    val error: Color get() = SvError
}

val SvError = Color(0xFFD9573A)

private fun palette(bg: Long, sf: Long, sf2: Long, ink: Long, ink2: Long, ac: Long, onAc: Long, dark: Boolean) = SvPalette(
    Color(bg), Color(sf), Color(sf2), Color(ink), Color(ink2), Color(ac), Color(onAc),
    if (dark) Color.White.copy(alpha = .10f) else Color.Black.copy(alpha = .10f), dark,
)

fun svPalette(theme: AppTheme): SvPalette = when (theme) {
    AppTheme.PAPER -> palette(0xFFF6F0E3, 0xFFECE3D0, 0xFFE1D5BD, 0xFF2A2420, 0xFF6A5F55, 0xFFB4512D, 0xFFFFF7EA, false)
    AppTheme.LIGHT, AppTheme.SYSTEM -> palette(0xFFF4EFE4, 0xFFEBE4D4, 0xFFE0D7C3, 0xFF1D211B, 0xFF5D6357, 0xFF4B6A48, 0xFFF4EFE4, false)
    AppTheme.DARK -> palette(0xFF121613, 0xFF1B211C, 0xFF262E27, 0xFFEFE9DC, 0xFFA4A99C, 0xFFC9D6A3, 0xFF121613, true)
    AppTheme.GRAPHITE -> palette(0xFF151517, 0xFF1F1F23, 0xFF2A2A30, 0xFFECEBF2, 0xFF9D9BA8, 0xFFB8A8F0, 0xFF151517, true)
    AppTheme.NORD -> palette(0xFF1B2129, 0xFF242C36, 0xFF2E3844, 0xFFE6EDF3, 0xFF97A4B3, 0xFFA9D2E6, 0xFF1B2129, true)
    AppTheme.DUSK -> palette(0xFF1D1520, 0xFF291F2D, 0xFF352A3A, 0xFFF3E7E2, 0xFFB09FAA, 0xFFF2B08F, 0xFF1D1520, true)
    AppTheme.PETROL -> palette(0xFF0E1B22, 0xFF15262F, 0xFF1D323D, 0xFFEEE7D6, 0xFF9AAEB3, 0xFFD9B25F, 0xFF0E1B22, true)
}

/** Material roles derived from the same tokens, for the few restyled Material components. */
fun themeColors(theme: AppTheme): ColorScheme {
    val p = svPalette(theme)
    val base = if (p.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.accent, onPrimary = p.onAccent,
        primaryContainer = p.surface2, onPrimaryContainer = p.ink,
        secondary = p.ink, onSecondary = p.bg,
        secondaryContainer = p.surface2, onSecondaryContainer = p.ink,
        tertiary = p.accent, onTertiary = p.onAccent,
        tertiaryContainer = p.surface, onTertiaryContainer = p.ink,
        background = p.bg, onBackground = p.ink, surface = p.bg, onSurface = p.ink,
        surfaceVariant = p.surface, onSurfaceVariant = p.ink2, surfaceTint = Color.Transparent,
        surfaceDim = p.bg, surfaceBright = p.surface, surfaceContainerLowest = p.bg,
        surfaceContainerLow = p.surface, surfaceContainer = p.surface,
        surfaceContainerHigh = p.surface2, surfaceContainerHighest = p.surface2,
        outline = lerp(p.ink2, p.bg, .2f), outlineVariant = lerp(p.ink2, p.bg, .7f),
        inverseSurface = p.ink, inverseOnSurface = p.bg, inversePrimary = p.accent,
        error = SvError, onError = Color.White,
        errorContainer = SvError.copy(alpha = .16f), onErrorContainer = p.ink,
        scrim = Color.Black,
    )
}

internal val LocalSv = compositionLocalOf { svPalette(AppTheme.PAPER) }

/** Every theme color animates over 600 ms when the theme changes. */
@Composable
internal fun animatedPalette(target: SvPalette, animate: Boolean): SvPalette {
    val d = if (animate) SvMotion.DurationColor else 0
    @Composable fun a(c: Color, label: String): Color {
        val v by animateColorAsState(c, tween(d, easing = SvMotion.Emphasized), label = label)
        return v
    }
    return SvPalette(
        bg = a(target.bg, "tema bg"), surface = a(target.surface, "tema surface"),
        surface2 = a(target.surface2, "tema surface2"), ink = a(target.ink, "tema ink"),
        ink2 = a(target.ink2, "tema ink2"), accent = a(target.accent, "tema accent"),
        onAccent = a(target.onAccent, "tema onAccent"), line = a(target.line, "tema line"),
        dark = target.dark,
    )
}

internal object SvFonts {
    val Display = FontFamily(Font(R.font.bricolage_display_bold, FontWeight.Bold))
    val Wordmark = FontFamily(Font(R.font.bricolage_display_extrabold, FontWeight.ExtraBold))
    val DisplayList = FontFamily(Font(R.font.bricolage_display_list, FontWeight.SemiBold))
    val Ui = FontFamily(
        Font(R.font.geist_regular, FontWeight.Normal),
        Font(R.font.geist_medium, FontWeight.Medium),
        Font(R.font.geist_semibold, FontWeight.SemiBold),
    )
    val Mono = FontFamily(
        Font(R.font.geist_mono_regular, FontWeight.Normal),
        Font(R.font.geist_mono_medium, FontWeight.Medium),
    )
}

private val TightLines = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

internal object SvType {
    fun display(size: TextUnit, lineHeight: Float = .9f, family: FontFamily = SvFonts.Display) = TextStyle(
        fontFamily = family, fontSize = size, letterSpacing = (-0.03).em,
        lineHeight = (size.value * lineHeight).sp, lineHeightStyle = TightLines,
    )
    val Wordmark = display(27.sp, 1f, SvFonts.Wordmark)
    val ListTitle = TextStyle(fontFamily = SvFonts.DisplayList, fontSize = 22.sp, letterSpacing = (-0.02).em,
        lineHeight = 21.sp, lineHeightStyle = TightLines)
    val Body = TextStyle(fontFamily = SvFonts.Ui, fontSize = 15.sp, lineHeight = 21.sp)
    val BodySmall = TextStyle(fontFamily = SvFonts.Ui, fontSize = 14.sp, lineHeight = 20.sp)
    val Meta = TextStyle(fontFamily = SvFonts.Ui, fontSize = 13.sp, lineHeight = 17.sp)
    val MetaSmall = TextStyle(fontFamily = SvFonts.Ui, fontSize = 12.sp, lineHeight = 16.sp)
    val Label = TextStyle(fontFamily = SvFonts.Ui, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp)
    val Eyebrow = TextStyle(fontFamily = SvFonts.Ui, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = .13.em, lineHeight = 14.sp)
    val EyebrowSmall = Eyebrow.copy(fontSize = 10.sp)
    val Mono = TextStyle(fontFamily = SvFonts.Mono, fontSize = 12.sp, lineHeight = 16.sp)
    val MonoSmall = Mono.copy(fontSize = 11.sp)
}
