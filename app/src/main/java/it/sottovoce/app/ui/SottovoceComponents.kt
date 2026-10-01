package it.sottovoce.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import it.sottovoce.app.data.Book
import kotlin.math.abs
import kotlin.math.sin

/**
 * Square cover. Embedded art is shown full-bleed; otherwise a typographic cover
 * is generated from the book colours: c1 fill, c3 motif, author eyebrow, big title.
 */
@Composable
internal fun BookCover(book: Book, colors: BookColors, modifier: Modifier = Modifier, radius: Dp = 16.dp,
    showTitle: Boolean = true) {
    val shape = svRounded(radius)
    BoxWithConstraints(modifier.clip(shape).background(colors.c1)) {
        val w = maxWidth
        val density = LocalDensity.current
        CoverImage(book.coverPath, "Copertina di ${book.title}", Modifier.fillMaxSize()) {
            val m = colors.motif
            val motifShape = when (m.shape) {
                MotifShape.CIRCLE -> CircleShape
                MotifShape.BAND -> RoundedCornerShape(0.dp)
                MotifShape.QUARTER -> RoundedCornerShape(topStart = w * (m.width / 100f))
                MotifShape.ARCH -> RoundedCornerShape(topStart = w * (m.width / 200f), topEnd = w * (m.width / 200f))
                MotifShape.ROUNDED -> RoundedCornerShape(w * .15f)
            }
            Box(Modifier.offset(x = w * (m.left / 100f), y = w * (m.top / 100f))
                .size(w * (m.width / 100f), w * (m.height / 100f)).clip(motifShape).background(colors.c3))
            if (showTitle && w >= 40.dp) {
                val inset = w * .08f
                val authorSize = with(density) { (w * .054f).toSp() }
                val titleSize = with(density) {
                    (w * when { book.title.length > 20 -> .11f; book.title.length > 11 -> .135f; else -> .17f }).toSp()
                }
                if (book.author.isNotBlank() && w >= 90.dp) Text(book.author.uppercase(), Modifier.padding(start = inset, top = inset, end = inset),
                    color = colors.c2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = SvType.coverAuthor(authorSize))
                Text(book.title, Modifier.align(Alignment.BottomStart).padding(start = inset, end = inset, bottom = inset * .8f),
                    color = colors.c2, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    style = SvType.display(titleSize, .88f))
            }
        }
    }
}

/** Deterministic decorative bar heights, 0..1, seeded per chapter. */
internal fun waveHeights(count: Int, seed: Int): List<Float> = List(count) { i ->
    val v = 10 + abs(sin(i * 1.7 + seed * 3)) * 24 + abs(sin(i * .37 + seed)) * 14
    (v / 48.0).toFloat().coerceIn(.18f, 1f)
}

/**
 * Decorative waveform. While [playing], bars breathe slowly and the ones under the
 * playhead move like a voice; when paused the motion freezes where it is.
 */
@Composable
internal fun Waveform(bars: Int, seed: Int, progress: Float, color: Color, modifier: Modifier = Modifier,
    gap: Dp = 2.dp, radius: Dp = 2.dp, scaleY: Float = 1f, playing: Boolean = false) {
    val heights = remember(bars, seed) { waveHeights(bars, seed) }
    val animate = LocalMotionPolicy.current.animationsEnabled
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(playing, animate) {
        if (!playing || !animate) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (true) withFrameMillis { now -> clock.longValue += now - last; last = now }
    }
    Canvas(modifier.clearAndSetSemantics { }) {
        val t = clock.longValue / 1000f
        val head = (progress * bars).toInt()
        val gapPx = gap.toPx()
        val barW = ((size.width - gapPx * (bars - 1)) / bars).coerceAtLeast(1f)
        val r = CornerRadius(if (SvLook.style.sharp) 0f else radius.toPx().coerceAtMost(barW / 2))
        heights.forEachIndexed { i, h ->
            val motion = when {
                !animate -> 1f
                abs(i - head) <= 1 -> loopKeyframes(t + i * .21f, .8f + (i % 3) * .15f, VoiceFrames)
                else -> loopKeyframes(t + (bars - i) * .13f, 3.2f, BreatheFrames)
            }
            val bh = (size.height * h * scaleY * motion).coerceAtMost(size.height)
            val played = (i + .5f) / bars < progress
            drawRoundRect(color.copy(alpha = color.alpha * if (played) 1f else .28f),
                topLeft = Offset(i * (barW + gapPx), (size.height - bh) / 2f), size = Size(barW, bh), cornerRadius = r)
        }
    }
}

private val VoiceFrames = listOf(0f to 1f, .35f to .62f, .7f to 1.12f, 1f to 1f)
private val BreatheFrames = listOf(0f to 1f, .5f to .8f, 1f to 1f)

/** CSS-like keyframe loop with ease-in-out between stops. */
private fun loopKeyframes(time: Float, period: Float, frames: List<Pair<Float, Float>>): Float {
    val p = (time / period) % 1f
    val i = frames.indexOfLast { it.first <= p }.coerceIn(0, frames.size - 2)
    val (a, va) = frames[i]; val (b, vb) = frames[i + 1]
    val x = ((p - a) / (b - a)).coerceIn(0f, 1f)
    return va + (vb - va) * (x * x * (3 - 2 * x))
}

/** Three-bar equaliser; it moves only while playing. */
@Composable
internal fun Equalizer(playing: Boolean, color: Color, modifier: Modifier = Modifier.size(12.dp)) {
    val animate = playing && LocalMotionPolicy.current.animationsEnabled
    val transition = rememberInfiniteTransition(label = "equalizzatore")
    val phases = listOf(420, 560, 360).mapIndexed { i, d ->
        transition.animateFloat(.35f, 1f, infiniteRepeatable(tween(d, easing = LinearEasing), RepeatMode.Reverse), label = "barra $i")
    }
    Canvas(modifier.clearAndSetSemantics { }) {
        val w = size.width / 5f
        phases.forEachIndexed { i, state ->
            val h = size.height * if (animate) state.value else listOf(.5f, .8f, .4f)[i]
            drawRoundRect(color, Offset(i * 2 * w, size.height - h), Size(w, h), CornerRadius(w / 2))
        }
    }
}

/** Play/pause with the v2 morph: circle when paused → rounded square while playing, icons cross-fade + scale (+ rotate). */
@Composable
internal fun PlayMorphButton(playing: Boolean, size: Dp, container: Color, content: Color, playingRadius: Dp,
    modifier: Modifier = Modifier, rotate: Boolean = false, iconSize: Dp = size * .42f, onClick: () -> Unit) {
    val policy = LocalMotionPolicy.current
    val radius by animateDpAsState(if (playing) playingRadius else size / 2, policy.overshoot(SvMotion.DurationPlayMorph), label = "forma play")
    val t by animateFloatAsState(if (playing) 1f else 0f, policy.emphasized(SvMotion.DurationPlayMorph), label = "icona play")
    Box(modifier.size(size).clip(svRounded(radius)).background(container)
        .semantics { contentDescription = if (playing) "Pausa" else "Riproduci" }
        .motionClickable(pressedScale = .92f, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(iconSize).graphicsLayer {
            alpha = 1f - t; val s = 1f - .6f * t; scaleX = s; scaleY = s; if (rotate) rotationZ = -90f * t
        }, tint = content)
        Icon(Icons.Rounded.Pause, null, Modifier.size(iconSize).graphicsLayer {
            alpha = t; val s = .4f + .6f * t; scaleX = s; scaleY = s; if (rotate) rotationZ = 90f * (1f - t)
        }, tint = content)
    }
}

@Composable
internal fun ProgressRing(progress: Float, size: Dp, color: Color, track: Color, modifier: Modifier = Modifier,
    stroke: Dp = 3.dp, label: String? = null, labelStyle: TextStyle = SvType.MonoSmall.copy(fontSize = 10.sp)) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            val arc = Size(this.size.width - s, this.size.height - s)
            drawArc(track, 0f, 360f, false, Offset(s / 2, s / 2), arc, style = Stroke(s))
            drawArc(color, -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(s / 2, s / 2), arc, style = Stroke(s, cap = StrokeCap.Round))
        }
        label?.let { Text(it, style = labelStyle, color = color) }
    }
}

/** 46×28 switch with an overshooting knob. */
@Composable
internal fun SvSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val sv = LocalSv.current
    val policy = LocalMotionPolicy.current
    val x by animateDpAsState(if (checked) 18.dp else 0.dp, policy.overshoot(420), label = "interruttore")
    val bg by androidx.compose.animation.animateColorAsState(if (checked) sv.accent else sv.surface2, label = "sfondo interruttore")
    val knob by androidx.compose.animation.animateColorAsState(if (checked) sv.onAccent else sv.ink2, label = "pomello")
    Box(modifier.size(46.dp, 28.dp).clip(SvCircle).background(bg).padding(4.dp).clearAndSetSemantics { }) {
        Box(Modifier.offset(x = x).size(20.dp).clip(SvCircle).background(knob))
    }
}

@Composable
internal fun IconCircle(icon: ImageVector, contentDescription: String?, size: Dp, tint: Color, modifier: Modifier = Modifier,
    background: Color = Color.Transparent, border: Color? = null, iconSize: Dp = size * .46f, pressedRotation: Float = 0f,
    enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.size(size).clip(SvCircle).background(background)
        .then(if (border != null) Modifier.border(1.5.dp, border, SvCircle) else Modifier)
        .motionClickable(enabled = enabled, pressedScale = .9f, pressedRotation = pressedRotation, onClickLabel = contentDescription, onClick = onClick)
        .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(iconSize), tint = tint.copy(alpha = if (enabled) tint.alpha else tint.alpha * .4f))
    }
}

@Composable
internal fun PillButton(text: String, background: Color, content: Color, modifier: Modifier = Modifier, height: Dp = 48.dp,
    border: Color? = null, icon: ImageVector? = null, style: TextStyle = SvType.Label, enabled: Boolean = true, onClick: () -> Unit) {
    Row(modifier.height(height).clip(SvCircle).background(background)
        .then(if (border != null) Modifier.border(1.5.dp, border, SvCircle) else Modifier)
        .motionClickable(enabled = enabled, pressedScale = .97f, onClick = onClick)
        .graphicsLayer { alpha = if (enabled) 1f else .45f }
        .padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        icon?.let { Icon(it, null, Modifier.size(20.dp), tint = content) }
        Text(text, style = style, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Filled segment bar: one segment per chapter, width proportional to duration. */
@Composable
internal fun ChapterMap(durations: List<Long>, fills: List<Float>, color: Color, modifier: Modifier = Modifier) {
    Row(modifier.height(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        durations.forEachIndexed { i, d ->
            Box(Modifier.weight(d.coerceAtLeast(1).toFloat()).fillMaxHeight().clip(SvCircle).background(color.copy(alpha = .18f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fills.getOrElse(i) { 0f }.coerceIn(0f, 1f)).background(color))
            }
        }
    }
}

@Composable
internal fun Eyebrow(text: String, color: Color, modifier: Modifier = Modifier, small: Boolean = false) {
    Text(text.uppercase(), modifier, style = if (small) SvType.EyebrowSmall else SvType.Eyebrow, color = color,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun CrossfadeText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, maxLines: Int = 1) {
    AnimatedContent(text, modifier, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "testo") {
        Text(it, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

internal fun Modifier.coverShadow(radius: Dp, elevation: Dp = 12.dp): Modifier =
    this.shadow(elevation, svRounded(radius), clip = false)

/** Human duration like "6h 20m" / "42m". */
internal fun dur(ms: Long): String {
    val minutesTotal = Math.round(ms.coerceAtLeast(0) / 60_000.0)
    val h = minutesTotal / 60; val m = minutesTotal % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
