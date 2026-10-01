package it.sottovoce.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import it.sottovoce.app.R
import it.sottovoce.app.data.AppTheme

/**
 * Sottovoce design tokens. Theme colors apply to the library, sub-screens and
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
    AppTheme.LIGHT -> palette(0xFFF4EFE4, 0xFFEBE4D4, 0xFFE0D7C3, 0xFF1D211B, 0xFF5D6357, 0xFF4B6A48, 0xFFF4EFE4, false)
    AppTheme.MATCHA -> palette(0xFFEEF1E4, 0xFFE1E7D2, 0xFFD2DBBF, 0xFF1F2B1C, 0xFF566350, 0xFF59783A, 0xFFF4F7EA, false)
    AppTheme.SORBET -> palette(0xFFFBEEE6, 0xFFF6DFD8, 0xFFEFCFD0, 0xFF3A2440, 0xFF76607B, 0xFFC34680, 0xFFFFFFFF, false)
    AppTheme.PORCELAIN -> palette(0xFFF3F5F8, 0xFFE6EBF2, 0xFFD8E0EB, 0xFF14213D, 0xFF55617A, 0xFF1F4FB8, 0xFFF3F5F8, false)
    AppTheme.HERBARIUM -> palette(0xFFF1EAD8, 0xFFE6DCC3, 0xFFD9CCAD, 0xFF2F3322, 0xFF66654B, 0xFF8A5A2B, 0xFFF9F3E3, false)
    AppTheme.SWISS -> palette(0xFFFFFFFF, 0xFFF0F0F0, 0xFFE2E2E2, 0xFF000000, 0xFF5E5E5E, 0xFFE30613, 0xFFFFFFFF, false)
    AppTheme.GAZETTE -> palette(0xFFECE8DD, 0xFFE2DDCF, 0xFFD4CEBD, 0xFF111111, 0xFF524D44, 0xFF111111, 0xFFECE8DD, false)
    AppTheme.RISO -> palette(0xFFF7E6DE, 0xFFF1D6CB, 0xFFE9C3B5, 0xFF1D3EA0, 0xFF4D61A6, 0xFFDC2A62, 0xFFFFFFFF, false)
    AppTheme.NOTEBOOK -> palette(0xFFFBFAF3, 0xFFF1EFE3, 0xFFE6E3D3, 0xFF1E2A4A, 0xFF59627C, 0xFFC9403A, 0xFFFFFFFF, false)
    AppTheme.DARK -> palette(0xFF121613, 0xFF1B211C, 0xFF262E27, 0xFFEFE9DC, 0xFFA4A99C, 0xFFC9D6A3, 0xFF121613, true)
    AppTheme.GRAPHITE -> palette(0xFF151517, 0xFF1F1F23, 0xFF2A2A30, 0xFFECEBF2, 0xFF9D9BA8, 0xFFB8A8F0, 0xFF151517, true)
    AppTheme.NORD -> palette(0xFF1B2129, 0xFF242C36, 0xFF2E3844, 0xFFE6EDF3, 0xFF97A4B3, 0xFFA9D2E6, 0xFF1B2129, true)
    AppTheme.DUSK -> palette(0xFF1D1520, 0xFF291F2D, 0xFF352A3A, 0xFFF3E7E2, 0xFFB09FAA, 0xFFF2B08F, 0xFF1D1520, true)
    AppTheme.PETROL -> palette(0xFF0E1B22, 0xFF15262F, 0xFF1D323D, 0xFFEEE7D6, 0xFF9AAEB3, 0xFFD9B25F, 0xFF0E1B22, true)
    AppTheme.VOID -> palette(0xFF000000, 0xFF0F0F0F, 0xFF1C1C1C, 0xFFF5F4EE, 0xFF8D8C86, 0xFFF5F4EE, 0xFF000000, true)
    AppTheme.VELVET -> palette(0xFF1E0A10, 0xFF2C1018, 0xFF3A1620, 0xFFF3E1C4, 0xFFB3967F, 0xFFD4A64A, 0xFF1E0A10, true)
    AppTheme.BLUEPRINT -> palette(0xFF0F3A6B, 0xFF13467F, 0xFF1A5394, 0xFFEEF5FF, 0xFFA9C4E6, 0xFFFFFFFF, 0xFF0F3A6B, true)
    AppTheme.PHOSPHOR -> palette(0xFF030A05, 0xFF0A1A0E, 0xFF11281A, 0xFF7DFF9B, 0xFF4CC06C, 0xFFC4FFD2, 0xFF030A05, true)
    AppTheme.DARKROOM -> palette(0xFF0B0101, 0xFF1A0504, 0xFF270806, 0xFFFF4A3A, 0xFFD84A38, 0xFFFF7B6B, 0xFF0B0101, true)
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
    val Ui = FontFamily(
        Font(R.font.geist_regular, FontWeight.Normal),
        Font(R.font.geist_medium, FontWeight.Medium),
        Font(R.font.geist_semibold, FontWeight.SemiBold),
    )
    val Mono = FontFamily(
        Font(R.font.geist_mono_regular, FontWeight.Normal),
        Font(R.font.geist_mono_medium, FontWeight.Medium),
    )
    val WideBlack = FontFamily(Font(R.font.theme_bricolage_wide_black, FontWeight.ExtraBold))
    val Bodoni = FontFamily(Font(R.font.theme_bodoni, FontWeight.SemiBold))
    val InstrumentSerif = FontFamily(Font(R.font.theme_instrument_serif, FontWeight.Normal))
    val GeistBold = FontFamily(Font(R.font.geist_bold, FontWeight.Bold))
    val Newsreader = FontFamily(Font(R.font.theme_newsreader, FontWeight.SemiBold))
    val Unbounded = FontFamily(Font(R.font.theme_unbounded, FontWeight.Bold))
    val Caveat = FontFamily(Font(R.font.theme_caveat, FontWeight.Bold))
    val SpaceMono = FontFamily(Font(R.font.theme_space_mono, FontWeight.Bold))
    val Vt323 = FontFamily(Font(R.font.theme_vt323, FontWeight.Normal))
}

/** Full-screen texture some themes lay over everything. */
internal enum class SvFx { NONE, GRAIN, HALFTONE, RULED, GRID, SCANLINES, SAFELIGHT }

/**
 * Everything a v3 theme changes beyond colours: display typeface and tracking, body
 * typeface, square corners, text glow, a surface texture and, for some themes,
 * the palettes used to reprint book colours.
 */
@Immutable
internal data class SvStyle(
    val display: FontFamily = SvFonts.Display,
    val displaySpacing: Float = -.03f,
    val wordmark: FontFamily = SvFonts.Wordmark,
    val body: FontFamily = SvFonts.Ui,
    val sharp: Boolean = false,
    val glow: Color? = null,
    val glowRadius: Dp = 0.dp,
    val fx: SvFx = SvFx.NONE,
    /** Texture strength; grain darkens light themes and lightens dark ones. */
    val fxAlpha: Float = 0f,
    val coverPalettes: List<Triple<Long, Long, Long>>? = null,
)

internal fun svStyle(theme: AppTheme): SvStyle = when (theme) {
    AppTheme.SORBET -> SvStyle(display = SvFonts.WideBlack, wordmark = SvFonts.WideBlack)
    AppTheme.PORCELAIN -> SvStyle(display = SvFonts.Bodoni, displaySpacing = -.02f, wordmark = SvFonts.Bodoni)
    AppTheme.HERBARIUM -> SvStyle(display = SvFonts.InstrumentSerif, displaySpacing = -.01f, wordmark = SvFonts.InstrumentSerif,
        fx = SvFx.GRAIN, fxAlpha = .22f)
    AppTheme.SWISS -> SvStyle(display = SvFonts.GeistBold, displaySpacing = -.06f, wordmark = SvFonts.GeistBold, sharp = true)
    AppTheme.GAZETTE -> SvStyle(display = SvFonts.Newsreader, displaySpacing = -.025f, wordmark = SvFonts.Newsreader, sharp = true,
        fx = SvFx.HALFTONE, fxAlpha = .7f, coverPalettes = listOf(
            Triple(0xFF1B1B1B, 0xFFECE8DD, 0xFF6D6A62), Triple(0xFFC9C3B3, 0xFF111111, 0xFF555048),
            Triple(0xFF55524B, 0xFFF2EFE6, 0xFF1B1B1B), Triple(0xFFECE8DD, 0xFF111111, 0xFFA8A293)))
    AppTheme.RISO -> SvStyle(display = SvFonts.Unbounded, displaySpacing = -.05f, wordmark = SvFonts.Unbounded,
        fx = SvFx.GRAIN, fxAlpha = .35f, coverPalettes = listOf(
            Triple(0xFFFF4778, 0xFF1D3EA0, 0xFFFFD23F), Triple(0xFF1D3EA0, 0xFFF7E6DE, 0xFFFF4778),
            Triple(0xFFFFD23F, 0xFF1D3EA0, 0xFFFF4778), Triple(0xFF22A884, 0xFFF7E6DE, 0xFF1D3EA0)))
    AppTheme.NOTEBOOK -> SvStyle(display = SvFonts.Caveat, displaySpacing = 0f, wordmark = SvFonts.Caveat, fx = SvFx.RULED, fxAlpha = 1f)
    AppTheme.VOID -> SvStyle(display = SvFonts.InstrumentSerif, displaySpacing = -.02f, wordmark = SvFonts.InstrumentSerif)
    AppTheme.VELVET -> SvStyle(display = SvFonts.Bodoni, displaySpacing = -.02f, wordmark = SvFonts.Bodoni, fx = SvFx.GRAIN, fxAlpha = .09f)
    AppTheme.BLUEPRINT -> SvStyle(display = SvFonts.SpaceMono, displaySpacing = -.05f, wordmark = SvFonts.SpaceMono, sharp = true,
        fx = SvFx.GRID, fxAlpha = 1f, coverPalettes = listOf(
            Triple(0xFF13467F, 0xFFEEF5FF, 0xFF2C6BB3), Triple(0xFFEEF5FF, 0xFF0F3A6B, 0xFF9CBBE0), Triple(0xFF0B2F58, 0xFFFFFFFF, 0xFF1A5394)))
    AppTheme.PHOSPHOR -> SvStyle(display = SvFonts.Vt323, displaySpacing = 0f, wordmark = SvFonts.Vt323, body = SvFonts.Mono, sharp = true,
        glow = Color(0x8C7DFF9B), glowRadius = 6.dp, fx = SvFx.SCANLINES, fxAlpha = 1f, coverPalettes = listOf(
            Triple(0xFF0A1A0E, 0xFF7DFF9B, 0xFF1D5A2B), Triple(0xFF11281A, 0xFFC4FFD2, 0xFF45B866), Triple(0xFF7DFF9B, 0xFF030A05, 0xFF2F8A47)))
    AppTheme.DARKROOM -> SvStyle(glow = Color(0x59FF321E), glowRadius = 10.dp, fx = SvFx.SAFELIGHT, fxAlpha = 1f, coverPalettes = listOf(
        Triple(0xFF1A0504, 0xFFFF4A3A, 0xFF5A0E08), Triple(0xFF3A0A06, 0xFFFF7B6B, 0xFF120202), Triple(0xFFFF4A3A, 0xFF0B0101, 0xFF8A1A12)))
    else -> SvStyle()
}

/**
 * The style of the theme on screen. Kept as global snapshot state so text styles and
 * shapes read it directly (and recompose when it changes) without threading a
 * composition local through every helper.
 */
internal object SvLook {
    var style by mutableStateOf(SvStyle())
    /** Pixels per dp, for the text glow radius. */
    var density by mutableStateOf(2.625f)
}

/** Rounded corners, or square ones in the "sharp" themes. */
internal fun svRounded(radius: Dp): Shape = if (SvLook.style.sharp) RectangleShape else RoundedCornerShape(radius)
internal fun svRounded(topStart: Dp = 0.dp, topEnd: Dp = 0.dp, bottomEnd: Dp = 0.dp, bottomStart: Dp = 0.dp): Shape =
    if (SvLook.style.sharp) RectangleShape else RoundedCornerShape(topStart, topEnd, bottomEnd, bottomStart)
internal val SvCircle: Shape get() = if (SvLook.style.sharp) RectangleShape else CircleShape

private val TightLines = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

internal object SvType {
    private val s get() = SvLook.style
    private val shadow: Shadow? get() = s.glow?.let { Shadow(it, Offset.Zero, s.glowRadius.value * SvLook.density) }
    private fun ui(size: TextUnit, lineHeight: TextUnit, weight: FontWeight? = null, spacing: TextUnit = TextUnit.Unspecified) =
        TextStyle(fontFamily = s.body, fontSize = size, lineHeight = lineHeight, fontWeight = weight, letterSpacing = spacing, shadow = shadow)

    fun display(size: TextUnit, lineHeight: Float = .9f, family: FontFamily? = null) = TextStyle(
        fontFamily = family ?: s.display, fontSize = size, letterSpacing = s.displaySpacing.em,
        lineHeight = (size.value * lineHeight).sp, lineHeightStyle = TightLines, shadow = shadow,
    )
    val Wordmark: TextStyle get() = display(27.sp, 1f, s.wordmark)
    val ListTitle: TextStyle get() = display(22.sp, .96f)
    val Body: TextStyle get() = ui(15.sp, 21.sp)
    val BodySmall: TextStyle get() = ui(14.sp, 20.sp)
    val Meta: TextStyle get() = ui(13.sp, 17.sp)
    val MetaSmall: TextStyle get() = ui(12.sp, 16.sp)
    val Label: TextStyle get() = ui(14.sp, 18.sp, FontWeight.SemiBold)
    val Eyebrow: TextStyle get() = ui(11.sp, 14.sp, FontWeight.SemiBold, .13.em)
    val EyebrowSmall: TextStyle get() = Eyebrow.copy(fontSize = 10.sp)
    val Mono: TextStyle get() = TextStyle(fontFamily = SvFonts.Mono, fontSize = 12.sp, lineHeight = 16.sp, shadow = shadow)
    val MonoSmall: TextStyle get() = Mono.copy(fontSize = 11.sp)
    /** Author line on generated covers. */
    fun coverAuthor(size: TextUnit) = TextStyle(fontFamily = s.body, fontWeight = FontWeight.Medium, fontSize = size, letterSpacing = .16.em)
}
