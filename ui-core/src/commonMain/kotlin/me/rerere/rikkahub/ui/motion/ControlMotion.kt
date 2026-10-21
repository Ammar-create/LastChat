package me.rerere.rikkahub.ui.motion

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberUpdatedState

@Stable
data class MotionPolicy(val reduceMotion: Boolean)

val LocalMotionPolicy = compositionLocalOf { MotionPolicy(reduceMotion = false) }

/** One interruptible spring for press and release; rapid taps never queue animations. */
@Composable
fun rememberControlPressValue(
    pressed: Boolean,
    pressedValue: Float,
    label: String,
): State<Float> {
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    // Changing only animationSpec does not stop an animation already headed to 1f.
    // Leave that animation group entirely when reduced motion becomes active.
    if (reduceMotion) return rememberUpdatedState(1f)
    return animateFloatAsState(
        targetValue = if (pressed) pressedValue else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = 800f,
            visibilityThreshold = 0.0005f,
        ),
        label = label,
    )
}
