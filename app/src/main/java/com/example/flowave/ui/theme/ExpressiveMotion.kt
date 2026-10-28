package com.example.flowave.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Shared, restrained iOS/Material 3 Expressive motion physics for FloWave. */
object ExpressiveMotion {
    const val Quick = 160
    const val Standard = 300
    const val Emphasized = 440

    // Spatial springs: fluid iOS bounce for layout and scale transitions
    fun <T> spatialFast() = spring<T>(
        dampingRatio = 0.75f,
        stiffness = Spring.StiffnessMedium, // 1500f: immediate, tactile button feedback (~180ms)
    )

    fun <T> spatialDefault() = spring<T>(
        dampingRatio = 0.8f,
        stiffness = 650f, // Snappy & fluid iOS expressive motion (~280ms)
    )

    fun <T> spatialSlow() = spring<T>(
        dampingRatio = 0.85f,
        stiffness = Spring.StiffnessMediumLow, // 400f
    )

    fun <T> spatialSpring() = spatialDefault<T>()

    // Effects springs: no overshoot (for alpha, color)
    fun <T> effectsFast() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium, // 1500f
    )

    fun <T> effectsDefault() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 700f, // Clean fade without sluggish trailing (~250ms)
    )

    fun <T> effectsSlow() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow, // 400f
    )

    fun <T> smoothSpring() = effectsDefault<T>()

    fun forwardEnter(): EnterTransition =
        fadeIn(effectsDefault()) +
            slideInHorizontally(spatialDefault()) { it / 10 } +
            scaleIn(spatialFast(), initialScale = 0.96f)

    fun forwardExit(): ExitTransition =
        fadeOut(effectsFast()) +
            scaleOut(effectsDefault(), targetScale = 0.985f)

    fun backEnter(): EnterTransition =
        fadeIn(effectsDefault()) +
            slideInHorizontally(spatialDefault()) { -it / 12 } +
            scaleIn(spatialFast(), initialScale = 0.96f)

    fun backExit(): ExitTransition =
        fadeOut(effectsFast()) +
            slideOutHorizontally(spatialDefault()) { it / 12 } +
            scaleOut(effectsDefault(), targetScale = 0.985f)
}

/**
 * Tactile spring press feedback modifier.
 * Subtly squishes the component when held and springs back dynamically on release.
 */
@Composable
fun Modifier.expressivePressScale(
    interactionSource: MutableInteractionSource,
    targetScale: Float = 0.96f,
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) targetScale else 1f,
        animationSpec = if (isPressed) ExpressiveMotion.spatialFast() else ExpressiveMotion.spatialDefault(),
        label = "expressive_press_scale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
