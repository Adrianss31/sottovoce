package it.sottovoce.app.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import androidx.palette.graphics.Palette
import it.sottovoce.app.R
import it.sottovoce.app.data.AppTheme
import it.sottovoce.app.data.Book
import it.sottovoce.app.ui.BookColors
import it.sottovoce.app.ui.MotifShape
import it.sottovoce.app.ui.colorsFromPalette
import it.sottovoce.app.ui.hashedBookColors
import it.sottovoce.app.ui.svStyle
import it.sottovoce.app.ui.themedBookColors
import it.sottovoce.app.ui.waveHeights
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * Bitmaps for the home-screen widgets. RemoteViews cannot use the app's typefaces
 * or draw shapes, so covers, display titles, waveforms, the chapter ring and the
 * skip icons are drawn here at up to 2× density and placed in ImageViews.
 */
internal class WidgetArt(private val context: Context) {
    /** Pixels per dp, capped so bitmaps stay small enough for frequent updates. */
    val scale: Float = min(context.resources.displayMetrics.density, 2f)
    private fun px(dp: Float) = dp * scale

    fun displayTypeface(theme: AppTheme): Typeface = typefaces.getOrPut(theme) {
        val res = when (theme) {
            AppTheme.SORBET -> R.font.theme_bricolage_wide_black
            AppTheme.PORCELAIN, AppTheme.VELVET -> R.font.theme_bodoni
            AppTheme.HERBARIUM, AppTheme.VOID -> R.font.theme_instrument_serif
            AppTheme.SWISS -> R.font.geist_bold
            AppTheme.GAZETTE -> R.font.theme_newsreader
            AppTheme.RISO -> R.font.theme_unbounded
            AppTheme.NOTEBOOK -> R.font.theme_caveat
            AppTheme.BLUEPRINT -> R.font.theme_space_mono
            AppTheme.PHOSPHOR -> R.font.theme_vt323
            else -> R.font.bricolage_display_bold
        }
        runCatching { ResourcesCompat.getFont(context, res) }.getOrNull() ?: Typeface.DEFAULT_BOLD
    }
    private val ui: Typeface by lazy { runCatching { ResourcesCompat.getFont(context, R.font.geist_semibold) }.getOrNull() ?: Typeface.DEFAULT_BOLD }
    private val uiMedium: Typeface by lazy { runCatching { ResourcesCompat.getFont(context, R.font.geist_medium) }.getOrNull() ?: Typeface.DEFAULT }

    /** The cover art, decoded small and cached by file and modification time. */
    fun art(book: Book): Bitmap? {
        val path = book.coverPath?.takeIf { it.isNotBlank() } ?: return null
        val file = File(path)
        if (!file.isFile) return null
        val key = "$path:${file.lastModified()}"
        arts[key]?.let { return it }
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 600 || bounds.outHeight / sample > 600) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()?.also { arts[key] = it }
    }

    /** The book's c1/c2/c3, as in the app: theme inks, art palette, or a stable hashed palette. */
    fun colors(book: Book, theme: AppTheme): BookColors {
        svStyle(theme).coverPalettes?.let { return themedBookColors(book.id, it) }
        val fallback = hashedBookColors(book.id)
        val art = art(book) ?: return fallback
        return paletteColors.getOrPut(book.coverPath + ":" + art.generationId) {
            runCatching {
                val small = Bitmap.createScaledBitmap(art, 160, 160 * art.height / art.width.coerceAtLeast(1), true)
                colorsFromPalette(Palette.from(small).maximumColorCount(16).generate(), fallback.motif)
            }.getOrNull() ?: fallback
        }
    }

    /**
     * A cover: the art cropped to fill, or a typographic cover (c1 fill, c3 motif,
     * author eyebrow, display title at the bottom).
     */
    fun cover(book: Book, colors: BookColors, theme: AppTheme, widthDp: Float, heightDp: Float, radiusDp: Float,
        authorSp: Float, titleSp: Float, titleMaxWidthDp: Float? = null, typographic: Boolean = true, insetDp: Float = 10f,
        topRight: String? = null): Bitmap {
        val w = px(widthDp).toInt().coerceAtLeast(1); val h = px(heightDp).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val clip = Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), px(radiusDp), px(radiusDp), Path.Direction.CW) }
        canvas.clipPath(clip)
        val art = art(book)
        if (art != null) {
            val s = maxOf(w.toFloat() / art.width, h.toFloat() / art.height)
            val dw = art.width * s; val dh = art.height * s
            canvas.drawBitmap(art, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
            // Real art already carries its title and author: nothing is printed over it.
            return bitmap
        } else {
            canvas.drawColor(colors.c1.toArgb())
            val m = colors.motif
            val side = w.toFloat()
            val r = RectF(side * m.left / 100f, side * m.top / 100f, side * (m.left + m.width) / 100f, side * (m.top + m.height) / 100f)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.c3.toArgb() }
            when (m.shape) {
                MotifShape.CIRCLE -> canvas.drawOval(r, paint)
                MotifShape.BAND -> canvas.drawRect(r, paint)
                MotifShape.ROUNDED -> canvas.drawRoundRect(r, side * .15f, side * .15f, paint)
                MotifShape.QUARTER -> canvas.drawPath(roundedPath(r, r.width(), 0f), paint)
                MotifShape.ARCH -> canvas.drawPath(roundedPath(r, r.width() / 2, r.width() / 2), paint)
            }
        }
        if (!typographic) return bitmap
        val inset = px(insetDp)
        val ink = colors.c2.toArgb()
        var authorRoom = w - inset * 2
        if (topRight != null) {
            val corner = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.MONOSPACE; textSize = px(authorSp); color = ink; letterSpacing = .08f; textAlign = Paint.Align.RIGHT
            }
            canvas.drawText(topRight, w - inset, inset - corner.ascent(), corner)
            authorRoom -= corner.measureText(topRight) + px(6f)
        }
        if (book.author.isNotBlank()) {
            val author = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = ui; textSize = px(authorSp); color = ink; letterSpacing = .16f }
            val text = TextUtils.ellipsize(book.author.uppercase(), author, authorRoom.coerceAtLeast(1f), TextUtils.TruncateAt.END).toString()
            canvas.drawText(text, inset, inset - author.ascent(), author)
        }
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = displayTypeface(theme); textSize = px(titleSp); color = ink; letterSpacing = -.03f
        }
        val maxWidth = (titleMaxWidthDp?.let { px(it) } ?: (w - inset * 2)).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(book.title, 0, book.title.length, title, maxWidth)
            .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0f, .9f).setIncludePad(false).build()
        canvas.save()
        canvas.translate(inset, h - inset - layout.height)
        layout.draw(canvas)
        canvas.restore()
        return bitmap
    }

    /**
     * The whole 2×2: generated cover with author and the title raised above
     * the chapter line and the play button, or the real art with a bottom veil.
     */
    fun square(book: Book, colors: BookColors, theme: AppTheme, widthDp: Float, heightDp: Float): Bitmap {
        val bitmap = cover(book, colors, theme, widthDp, heightDp, 24f, 9f, 24f, typographic = false)
        val canvas = Canvas(bitmap)
        if (art(book) != null) {
            // Only a veil at the bottom, so the chapter line and the play ring stay readable over the art.
            val h = bitmap.height.toFloat()
            canvas.drawRect(0f, h * .45f, bitmap.width.toFloat(), h, Paint().apply {
                shader = android.graphics.LinearGradient(0f, h * .45f, 0f, h, 0, colors.c1.copy(alpha = .85f).toArgb(),
                    android.graphics.Shader.TileMode.CLAMP)
            })
            return bitmap
        }
        val inset = px(14f)
        val ink = colors.c2.toArgb()
        if (book.author.isNotBlank()) {
            val author = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = ui; textSize = px(9f); color = ink; letterSpacing = .16f }
            val text = TextUtils.ellipsize(book.author.uppercase(), author, bitmap.width - inset * 2 - px(18f), TextUtils.TruncateAt.END).toString()
            canvas.drawText(text, inset, inset + px(5f) - (author.ascent() + author.descent()) / 2, author)
        }
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = displayTypeface(theme); textSize = px(24f); color = ink; letterSpacing = -.03f }
        val layout = StaticLayout.Builder.obtain(book.title, 0, book.title.length, title, px(widthDp - 28f).toInt().coerceAtLeast(1))
            .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0f, .9f).setIncludePad(false).build()
        canvas.save()
        canvas.translate(inset, bitmap.height - inset - px(50f) - layout.height)
        layout.draw(canvas)
        canvas.restore()
        return bitmap
    }

    /** One line of display type, ellipsized to the space the widget really has. */
    fun displayLine(text: String, theme: AppTheme, sizeSp: Float, color: Int, widthDp: Float, heightDp: Float): Bitmap {
        val w = px(widthDp).toInt().coerceAtLeast(1); val h = px(heightDp).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = displayTypeface(theme); textSize = px(sizeSp); this.color = color; letterSpacing = -.025f }
        val line = TextUtils.ellipsize(text, paint, w.toFloat(), TextUtils.TruncateAt.END).toString()
        val fm = paint.fontMetrics
        Canvas(bitmap).drawText(line, 0f, (h - (fm.descent - fm.ascent)) / 2f - fm.ascent, paint)
        return bitmap
    }

    /** Waveform bars: played in ink, the playhead bar in the accent, the rest at 28%. */
    fun waveform(bars: Int, seed: Int, progress: Float, ink: Int, accent: Int, widthDp: Float, heightDp: Float, phase: Int): Bitmap {
        val w = px(widthDp).toInt().coerceAtLeast(1); val h = px(heightDp).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val gap = px(2f)
        val barW = ((w - gap * (bars - 1)) / bars).coerceAtLeast(1f)
        val head = (progress * bars).toInt().coerceIn(0, bars - 1)
        val heights = waveHeights(bars, seed)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        heights.forEachIndexed { i, base ->
            // A little life around the playhead, different at every update.
            val near = (1f - abs(i - head) / 5f).coerceAtLeast(0f)
            val v = (base * (1f + near * .3f * abs(sin((phase + i) * 1.7f)))).coerceIn(.18f, 1f)
            val bh = h * v
            paint.color = if (i == head) accent else ink
            paint.alpha = if (i <= head) 255 else (.28f * 255).toInt()
            val x = i * (barW + gap)
            canvas.drawRoundRect(RectF(x, (h - bh) / 2, x + barW, (h + bh) / 2), px(1f), px(1f), paint)
        }
        return bitmap
    }

    /** Three-bar equaliser, frozen at a different height for each update while playing. */
    fun equalizer(color: Int, playing: Boolean, phase: Int, heightDp: Float): Bitmap {
        val h = px(heightDp).toInt().coerceAtLeast(3); val bar = px(2f); val gap = px(2f)
        val bitmap = Bitmap.createBitmap((bar * 3 + gap * 2).toInt() + 1, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val levels = if (playing) List(3) { .3f + .7f * abs(sin(phase * (4f + it * 1.3f) + it)) } else listOf(.4f, .7f, .3f)
        levels.forEachIndexed { i, f ->
            val x = i * (bar + gap)
            canvas.drawRoundRect(RectF(x, h * (1 - f), x + bar, h.toFloat()), bar / 2, bar / 2, paint)
        }
        return bitmap
    }

    /** Round play/pause button; with [ringProgress] it also carries the chapter ring (2×2). */
    fun playButton(sizeDp: Float, background: Int, foreground: Int, playing: Boolean, iconDp: Float, ringProgress: Float? = null): Bitmap {
        val s = px(sizeDp).toInt()
        val bitmap = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
        canvas.drawOval(RectF(0f, 0f, s.toFloat(), s.toFloat()), paint)
        if (ringProgress != null) {
            val stroke = px(2.5f)
            val r = RectF(px(2f), px(2f), s - px(2f), s - px(2f))
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = foreground; alpha = (.18f * 255).toInt() }
            canvas.drawOval(r, ring)
            ring.alpha = 255; ring.strokeCap = Paint.Cap.ROUND
            canvas.drawArc(r, -90f, 360f * ringProgress.coerceIn(0f, 1f), false, ring)
        }
        val icon = px(iconDp)
        canvas.translate((s - icon) / 2, (s - icon) / 2)
        val u = icon / 14f
        paint.color = foreground
        if (playing) {
            canvas.drawRoundRect(RectF(2.5f * u, 1.5f * u, 5.5f * u, 12.5f * u), u, u, paint)
            canvas.drawRoundRect(RectF(8.5f * u, 1.5f * u, 11.5f * u, 12.5f * u), u, u, paint)
        } else {
            canvas.drawPath(Path().apply { moveTo(3.5f * u, 1.6f * u); lineTo(12.7f * u, 7f * u); lineTo(3.5f * u, 12.4f * u); close() }, paint)
        }
        return bitmap
    }

    /** Circular arrow with the number of seconds, mirrored for forward skips. */
    fun skipIcon(seconds: Int, forward: Boolean, color: Int, sizeDp: Float): Bitmap {
        val s = px(sizeDp).toInt()
        val bitmap = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val u = s / 24f
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.6f * u; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; this.color = color
        }
        canvas.save()
        if (forward) canvas.scale(-1f, 1f, s / 2f, s / 2f)
        // Arc from 9 o'clock round to about 10:30, as in the design's "M4 12a8 8 0 1 0 2.4-5.7".
        canvas.drawArc(RectF(4f * u, 4f * u, 20f * u, 20f * u), 180f, -315f, false, stroke)
        canvas.drawPath(Path().apply { moveTo(4f * u, 3.5f * u); lineTo(4f * u, 7.1f * u); lineTo(7.6f * u, 7.1f * u) }, stroke)
        canvas.restore()
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = ui; textSize = 7.5f * u; this.color = color; textAlign = Paint.Align.CENTER }
        canvas.drawText("$seconds", 12f * u, 15.2f * u, text)
        return bitmap
    }

    private fun roundedPath(r: RectF, topStart: Float, topEnd: Float) = Path().apply {
        addRoundRect(r, floatArrayOf(topStart, topStart, topEnd, topEnd, 0f, 0f, 0f, 0f), Path.Direction.CW)
    }

    private companion object {
        val typefaces = ConcurrentHashMap<AppTheme, Typeface>()
        val arts = object : LinkedHashMap<String, Bitmap>(4, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 4
        }.let { java.util.Collections.synchronizedMap(it) }
        val paletteColors = ConcurrentHashMap<String, BookColors>()
    }
}
