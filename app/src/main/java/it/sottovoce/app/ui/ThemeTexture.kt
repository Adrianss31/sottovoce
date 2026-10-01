package it.sottovoce.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The surface texture of the themes that have one, laid over the whole app. It is
 * drawn with plain alpha (no blend modes) so it looks the same on every device:
 * grain darkens light themes and lightens dark ones, like multiply and screen.
 */
@Composable
internal fun ThemeTexture(fx: SvFx, strength: Float, dark: Boolean, modifier: Modifier = Modifier) {
    if (fx == SvFx.NONE || strength <= 0f) return
    val density = LocalDensity.current
    val brush = remember(fx, strength, dark, density) {
        when (fx) {
            SvFx.GRAIN -> ShaderBrush(ImageShader(grain(strength, dark), TileMode.Repeated, TileMode.Repeated))
            SvFx.HALFTONE -> ShaderBrush(ImageShader(halftone(with(density) { 5.dp.toPx() }, with(density) { 1.15.dp.toPx() },
                Color.Black.copy(alpha = .18f * strength)), TileMode.Repeated, TileMode.Repeated))
            else -> null
        }
    }
    // Its own layer: recorded once, not redrawn when the content below animates.
    Canvas(modifier.fillMaxSize().graphicsLayer { }.clearAndSetSemantics { }) {
        brush?.let { drawRect(it) }
        when (fx) {
            SvFx.RULED -> {
                val step = 28.dp.toPx(); val line = 1.dp.toPx()
                var y = step - line
                while (y < size.height) { drawRect(Color(0x33466EBE), Offset(0f, y), Size(size.width, line)); y += step }
                drawRect(Color(0x66D0453A), Offset(30.dp.toPx(), 0f), Size(1.5.dp.toPx(), size.height))
            }
            SvFx.GRID -> {
                val step = 22.dp.toPx(); val line = 1.dp.toPx(); val color = Color.White.copy(alpha = .08f)
                var y = 0f
                while (y < size.height) { drawRect(color, Offset(0f, y), Size(size.width, line)); y += step }
                var x = 0f
                while (x < size.width) { drawRect(color, Offset(x, 0f), Size(line, size.height)); x += step }
            }
            SvFx.SCANLINES -> {
                val step = 3.dp.toPx(); val line = 1.dp.toPx(); val color = Color.Black.copy(alpha = .35f)
                var y = 0f
                while (y < size.height) { drawRect(color, Offset(0f, y), Size(size.width, line)); y += step }
                vignette(.55f, .6f)
            }
            SvFx.SAFELIGHT -> {
                val c = Offset(size.width / 2f, size.height * .35f)
                drawRect(Brush.radialGradient(0f to Color(0x1AFF2814), .6f to Color.Transparent,
                    center = c, radius = hypot(size.width / 2f, size.height * .65f)))
                vignette(.5f, .65f)
            }
            else -> {}
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.vignette(clear: Float, edge: Float) {
    drawRect(Brush.radialGradient(clear to Color.Transparent, 1f to Color.Black.copy(alpha = edge),
        center = center, radius = hypot(size.width / 2f, size.height / 2f)))
}

/** Desaturated noise tile: black specks on light themes, white ones on dark themes. */
private fun grain(strength: Float, dark: Boolean): ImageBitmap {
    val side = 128
    val random = Random(7)
    val pixels = IntArray(side * side) {
        val g = .3f + random.nextFloat() * .4f
        val alpha = ((if (dark) g else 1f - g) * strength * 255).roundToInt().coerceIn(0, 255)
        (alpha shl 24) or if (dark) 0xFFFFFF else 0
    }
    return android.graphics.Bitmap.createBitmap(pixels, side, side, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** One halftone cell: a small dot centred in a square tile. */
private fun halftone(tile: Float, radius: Float, color: Color): ImageBitmap {
    val side = tile.roundToInt().coerceAtLeast(2)
    val image = ImageBitmap(side, side)
    androidx.compose.ui.graphics.Canvas(image).drawCircle(Offset(side / 2f, side / 2f), radius, Paint().apply { this.color = color })
    return image
}
