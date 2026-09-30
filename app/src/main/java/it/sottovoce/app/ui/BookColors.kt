package it.sottovoce.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.palette.graphics.Palette
import it.sottovoce.app.data.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Shape of the decorative c3 motif on a generated cover (all values in % of the cover width). */
@Immutable
internal data class CoverMotif(val top: Float, val left: Float, val width: Float, val height: Float, val shape: MotifShape)
internal enum class MotifShape { CIRCLE, BAND, QUARTER, ARCH, ROUNDED }

/** Every book has c1 (background), c2 (ink) and c3 (motif). */
@Immutable
internal data class BookColors(val c1: Color, val c2: Color, val c3: Color, val motif: CoverMotif)

private val HashedPalettes = listOf(
    Triple(0xFF1D3847, 0xFFEADFC8, 0xFFC65F37),
    Triple(0xFFD8C7A3, 0xFF3A2A1E, 0xFF7A8F5A),
    Triple(0xFF2B2A2E, 0xFFD7E0C9, 0xFF8FA86A),
    Triple(0xFFB8532F, 0xFFF5E6CF, 0xFF2D1B12),
    Triple(0xFF5A1E24, 0xFFF0D9C8, 0xFF2A0D10),
    Triple(0xFFE6D3CF, 0xFF4A2F3A, 0xFFB98A9A),
    Triple(0xFF23403A, 0xFFE8D49A, 0xFFCAA04A),
    Triple(0xFFC9B9D6, 0xFF2C2238, 0xFF6C5A8A),
    Triple(0xFFE3C7A8, 0xFF3B2618, 0xFFA8674A),
    Triple(0xFF3E3552, 0xFFE9DDF0, 0xFF9D86C2),
    Triple(0xFFE2D6B8, 0xFF1F2A2E, 0xFF3F7F86),
)

private val Motifs = listOf(
    CoverMotif(56f, 50f, 64f, 64f, MotifShape.CIRCLE),
    CoverMotif(-10f, -10f, 120f, 34f, MotifShape.BAND),
    CoverMotif(38f, -20f, 70f, 70f, MotifShape.CIRCLE),
    CoverMotif(62f, -10f, 120f, 60f, MotifShape.ARCH),
    CoverMotif(20f, 58f, 30f, 90f, MotifShape.ROUNDED),
    CoverMotif(12f, 12f, 34f, 34f, MotifShape.CIRCLE),
    CoverMotif(-30f, 40f, 90f, 90f, MotifShape.CIRCLE),
    CoverMotif(40f, 40f, 80f, 80f, MotifShape.QUARTER),
)

private fun stableHash(value: String): Int {
    var h = 0x811C9DC5.toInt()
    value.forEach { h = (h xor it.code) * 0x01000193 }
    return h and Int.MAX_VALUE
}

internal fun hashedBookColors(seed: String): BookColors {
    val h = stableHash(seed)
    val (c1, c2, c3) = HashedPalettes[h % HashedPalettes.size]
    return BookColors(Color(c1), Color(c2), Color(c3), Motifs[(h / 7) % Motifs.size])
}

internal fun contrast(a: Color, b: Color): Float {
    val x = a.luminance(); val y = b.luminance()
    return (maxOf(x, y) + .05f) / (minOf(x, y) + .05f)
}

private val Ivory = Color(0xFFF4ECDD)
private val Ink = Color(0xFF1C1814)

/** Derives c1/c2/c3 from embedded art; c2 always keeps readable contrast on c1. */
internal fun colorsFromPalette(palette: Palette, motif: CoverMotif): BookColors? {
    val base = palette.darkVibrantSwatch ?: palette.darkMutedSwatch ?: palette.dominantSwatch ?: return null
    var c1 = Color(base.rgb)
    // Very bright or very saturated backgrounds are tamed so white-on-colour UI stays calm.
    if (c1.luminance() > .55f) c1 = lerp(c1, Ink, .15f)
    val darkBg = c1.luminance() < .4f
    val tint = (if (darkBg) palette.lightMutedSwatch ?: palette.lightVibrantSwatch else palette.darkMutedSwatch)?.rgb?.let(::Color)
    var c2 = if (darkBg) Ivory else Ink
    if (tint != null) {
        val tinted = lerp(c2, tint, .25f)
        if (contrast(tinted, c1) >= 4.5f) c2 = tinted
    }
    if (contrast(c2, c1) < 4.5f) c2 = if (contrast(Ivory, c1) > contrast(Ink, c1)) Ivory else Ink
    val accent = (palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.mutedSwatch)?.rgb?.let(::Color)
    val c3 = accent?.takeIf { contrast(it, c1) > 1.3f } ?: lerp(c1, c2, .35f)
    return BookColors(c1, c2, c3, motif)
}

private data class PaletteKey(val path: String, val modified: Long)
private val PaletteCache = ConcurrentHashMap<PaletteKey, BookColors>()

private fun paletteKey(path: String?): PaletteKey? = path?.takeIf { it.isNotBlank() }?.let {
    val file = File(it)
    if (file.isFile) PaletteKey(it, file.lastModified()) else null
}

/**
 * Colours for a book: the cached art palette when the cover has been analysed,
 * otherwise a stable hashed palette. Analysis runs once per cover off the main thread.
 */
@Composable
internal fun rememberBookColors(book: Book): BookColors {
    val fallback = hashedBookColors(book.id)
    val path = book.coverPath
    val colors by produceState(initialValue = path?.let { p -> PaletteCache.entries.firstOrNull { it.key.path == p }?.value } ?: fallback,
        path, book.id) {
        val key = withContext(Dispatchers.IO) { paletteKey(path) } ?: run { value = fallback; return@produceState }
        PaletteCache[key]?.let { value = it; return@produceState }
        val bitmap = loadCoverBitmap(path, 160, 160) ?: run { value = fallback; return@produceState }
        val derived = withContext(Dispatchers.Default) {
            runCatching {
                val bmp = bitmap.asAndroidBitmap()
                val software = if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
                colorsFromPalette(Palette.from(software).maximumColorCount(16).generate(), fallback.motif)
            }.getOrNull()
        } ?: fallback
        PaletteCache[key] = derived
        value = derived
    }
    return colors
}
