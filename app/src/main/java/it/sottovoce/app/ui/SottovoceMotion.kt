package it.sottovoce.app.ui

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Shared timing, easing and spring values for Sottovoce motion. */
internal object SottovoceMotionTokens {
    const val DurationQuick = 120
    const val DurationShort = 180
    const val DurationMedium = 260
    const val DurationLong = 360
    const val DurationProgress = 500

    /**
     * Horizontal offset fraction used by navigation transitions and by the
     * back affordance in the top bar. Forward motion always comes from the
     * leading edge (+it), back motion mirrors it (-it); the back icon in the
     * top bar uses the same fraction so that arriving and leaving feel like
     * a single coherent gesture rather than a generic fade.
     */
    const val HorizontalOffsetFraction = 10
    const val BackIconOffsetFraction = 3

    const val PressedScale = 0.985f
    const val ProminentPressedScale = 0.975f
    const val IconIncomingScale = 0.82f
    const val IconOutgoingScale = 1.08f

    val StandardEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val AccelerateEasing: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    val PressSpring: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    fun <T> spatialSpring(): SpringSpec<T> = spring(
        dampingRatio = 0.86f,
        stiffness = Spring.StiffnessMediumLow,
    )
}

/**
 * Runtime motion policy. A duration of zero gives callers an instant equivalent
 * when Android animations are disabled.
 */
@Immutable
internal data class MotionPolicy(
    val animationsEnabled: Boolean,
) {
    fun durationMillis(naturalDurationMillis: Int): Int =
        if (animationsEnabled) naturalDurationMillis else 0
}

internal val LocalMotionPolicy = staticCompositionLocalOf {
    MotionPolicy(animationsEnabled = ValueAnimator.areAnimatorsEnabled())
}

@OptIn(ExperimentalSharedTransitionApi::class)
internal val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

internal val LocalAnimatedVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** Rechecks the Android animator setting whenever the activity resumes. */
@Composable
internal fun rememberMotionPolicy(): MotionPolicy {
    val lifecycleOwner = LocalLifecycleOwner.current
    var animationsEnabled by remember {
        mutableStateOf(ValueAnimator.areAnimatorsEnabled())
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                animationsEnabled = ValueAnimator.areAnimatorsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        animationsEnabled = ValueAnimator.areAnimatorsEnabled()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return remember(animationsEnabled) { MotionPolicy(animationsEnabled) }
}

@Composable
internal fun ProvideSottovoceMotion(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMotionPolicy provides rememberMotionPolicy(), content = content)
}

/** Connects an element to its matching source/destination when both scopes exist. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.sottovoceSharedElement(key: String?): Modifier {
    if (key == null || !LocalMotionPolicy.current.animationsEnabled) return this
    val shared = LocalSharedTransitionScope.current ?: return this
    val visibility = LocalAnimatedVisibilityScope.current ?: return this
    val duration = LocalMotionPolicy.current.durationMillis(SottovoceMotionTokens.DurationLong)
    return with(shared) {
        this@sottovoceSharedElement.sharedElement(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = visibility,
            boundsTransform = BoundsTransform { _, _ ->
                tween(durationMillis = duration, easing = SottovoceMotionTokens.StandardEasing)
            },
        )
    }
}

/** Container transform for series/statistics surfaces whose inner content differs. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.sottovoceSharedBounds(key: String?): Modifier {
    if (key == null || !LocalMotionPolicy.current.animationsEnabled) return this
    val shared = LocalSharedTransitionScope.current ?: return this
    val visibility = LocalAnimatedVisibilityScope.current ?: return this
    val duration = LocalMotionPolicy.current.durationMillis(SottovoceMotionTokens.DurationLong)
    return with(shared) {
        this@sottovoceSharedBounds.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = visibility,
            boundsTransform = BoundsTransform { _, _ ->
                tween(durationMillis = duration, easing = SottovoceMotionTokens.StandardEasing)
            },
        )
    }
}

/**
 * Click feedback for cards and rows: a small, non-bouncy compression plus the
 * current Material indication. It intentionally owns the click interaction so
 * the visual state and semantics cannot drift apart.
 */
internal fun Modifier.motionClickable(
    enabled: Boolean = true,
    pressedScale: Float = SottovoceMotionTokens.PressedScale,
    onClickLabel: String? = null,
    role: Role? = Role.Button,
    onClick: () -> Unit,
): Modifier = composed {
    val policy = LocalMotionPolicy.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val targetScale = if (enabled && pressed && policy.animationsEnabled) pressedScale else 1f
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = SottovoceMotionTokens.PressSpring,
        label = "pressione discreta",
    )

    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.clickable(
        interactionSource = interactionSource,
        indication = LocalIndication.current,
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        onClick = onClick,
    )
}

@Composable
internal fun <State> AnimatedStateIcon(
    state: State,
    icon: (State) -> ImageVector,
    contentDescription: (State) -> String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    label: String = "icona di stato",
) {
    val policy = LocalMotionPolicy.current
    AnimatedContent(
        targetState = state,
        modifier = modifier,
        transitionSpec = { stateIconTransform(policy) },
        contentAlignment = Alignment.Center,
        label = label,
    ) { targetState ->
        Icon(
            imageVector = icon(targetState),
            contentDescription = contentDescription(targetState),
            tint = tint,
        )
    }
}

@Composable
internal fun AnimatedPlayPauseIcon(
    playing: Boolean,
    modifier: Modifier = Modifier,
    playContentDescription: String = "Riprendi ascolto",
    pauseContentDescription: String = "Pausa",
    tint: Color = LocalContentColor.current,
) {
    AnimatedStateIcon(
        state = playing,
        icon = { isPlaying -> if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow },
        contentDescription = { isPlaying ->
            if (isPlaying) pauseContentDescription else playContentDescription
        },
        modifier = modifier,
        tint = tint,
        label = "play pausa",
    )
}

private fun stateIconTransform(policy: MotionPolicy): ContentTransform =
    EnterTransition.None togetherWith ExitTransition.None

/** The cover leads; controls settle beneath it without changing the list's layout. */
@Composable
internal fun Modifier.bookDetailsReveal(): Modifier {
    val scope = LocalAnimatedVisibilityScope.current ?: return this
    val policy = LocalMotionPolicy.current
    return with(scope) {
        this@bookDetailsReveal.animateEnterExit(
            enter = fadeIn(tween(policy.durationMillis(180), delayMillis = policy.durationMillis(180))) +
                androidx.compose.animation.slideInVertically(tween(policy.durationMillis(220), delayMillis = policy.durationMillis(140))) { it / 12 },
            exit = fadeOut(tween(policy.durationMillis(100))),
        )
    }
}
