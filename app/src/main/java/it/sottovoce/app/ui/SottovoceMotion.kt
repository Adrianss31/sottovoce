package it.sottovoce.app.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Motion tokens from the v2 handoff. */
internal object SvMotion {
    /** cubic-bezier(.32,.72,0,1): navigation, sheets, shared elements, indicators. */
    val Emphasized: Easing = CubicBezierEasing(.32f, .72f, 0f, 1f)
    /** cubic-bezier(.34,1.56,.64,1): toggles, mini-play pop, waveform scale. */
    val Overshoot: Easing = CubicBezierEasing(.34f, 1.56f, .64f, 1f)

    const val DurationCoverOpen = 600
    const val DurationCoverClose = 520
    const val DurationSubScreen = 550
    const val DurationSheet = 550
    const val DurationScrim = 450
    const val DurationIndicator = 450
    const val DurationPlayMorph = 520
    const val DurationColor = 600
    const val DurationPress = 200
    const val DurationPill = 700

    fun <T> overshootSpring() = spring<T>(dampingRatio = .6f, stiffness = 400f)
    fun <T> pressSpring() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
}

/** A duration of zero gives callers an instant equivalent when Android animations are disabled. */
@Immutable
internal data class MotionPolicy(val animationsEnabled: Boolean) {
    fun durationMillis(natural: Int): Int = if (animationsEnabled) natural else 0
    fun <T> emphasized(duration: Int, delay: Int = 0): FiniteAnimationSpec<T> =
        if (animationsEnabled) tween(duration, delay, SvMotion.Emphasized) else snap()
    fun <T> overshoot(duration: Int): FiniteAnimationSpec<T> =
        if (animationsEnabled) tween(duration, easing = SvMotion.Overshoot) else snap()
    fun <T> spring(): FiniteAnimationSpec<T> = if (animationsEnabled) SvMotion.overshootSpring() else snap()
}

internal val LocalMotionPolicy = staticCompositionLocalOf { MotionPolicy(ValueAnimator.areAnimatorsEnabled()) }

/** Rechecks the Android "remove animations" setting whenever the activity resumes. */
@Composable
internal fun rememberMotionPolicy(): MotionPolicy {
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) enabled = ValueAnimator.areAnimatorsEnabled()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        enabled = ValueAnimator.areAnimatorsEnabled()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return remember(enabled) { MotionPolicy(enabled) }
}

@Composable
internal fun ProvideSottovoceMotion(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMotionPolicy provides rememberMotionPolicy(), content = content)
}

/**
 * Press feedback used everywhere in v2: the element scales down while pressed
 * (no ripple), with an optional rotation for skip buttons.
 */
internal fun Modifier.motionClickable(
    enabled: Boolean = true,
    pressedScale: Float = .96f,
    pressedRotation: Float = 0f,
    onClickLabel: String? = null,
    role: Role? = Role.Button,
    onClick: () -> Unit,
): Modifier = composed {
    val policy = LocalMotionPolicy.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val active = enabled && pressed && policy.animationsEnabled
    val scale by animateFloatAsState(if (active) pressedScale else 1f,
        tween(SvMotion.DurationPress, easing = SvMotion.Emphasized), label = "pressione")
    val rotation by animateFloatAsState(if (active) pressedRotation else 0f,
        tween(SvMotion.DurationPress, easing = SvMotion.Emphasized), label = "rotazione pressione")
    graphicsLayer { scaleX = scale; scaleY = scale; rotationZ = rotation }
        .clickable(interactionSource = source, indication = null, enabled = enabled,
            onClickLabel = onClickLabel, role = role, onClick = onClick)
}
