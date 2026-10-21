package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import me.rerere.rikkahub.ui.motion.LocalMotionPolicy
import me.rerere.rikkahub.ui.motion.rememberControlPressValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ToggleSurface(
    checked: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(50),
    checkedColor: Color = MaterialTheme.colorScheme.primaryContainer,
    uncheckedColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val haptics = rememberPremiumHaptics()
    val reduceMotion = LocalMotionPolicy.current.reduceMotion

    val backgroundTarget = if (checked) checkedColor else uncheckedColor
    val contentTarget = if (checked) contentColor else MaterialTheme.colorScheme.onSurface
    val animatedBackgroundColor by if (reduceMotion) rememberUpdatedState(backgroundTarget) else animateColorAsState(
        targetValue = backgroundTarget,
        animationSpec = spring(dampingRatio = 1f, stiffness = 500f),
        label = "toggle_bg_color",
    )
    val animatedContentColor by if (reduceMotion) rememberUpdatedState(contentTarget) else animateColorAsState(
        targetValue = contentTarget,
        animationSpec = spring(dampingRatio = 1f, stiffness = 500f),
        label = "toggle_content_color",
    )

    // Scale and alpha share one interruption/reduced-motion policy.
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by rememberControlPressValue(isPressed, 0.94f, "toggle_scale")
    val alpha by rememberControlPressValue(isPressed, 0.8f, "toggle_alpha")

    Surface(
        color = animatedBackgroundColor,
        contentColor = animatedContentColor,
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .clip(shape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    haptics.perform(HapticPattern.Pop)
                    onClick()
                },
                onLongClick = onLongClick?.let { action ->
                    {
                        haptics.perform(HapticPattern.Thud)
                        action()
                    }
                }
            ),
        shape = shape,
        tonalElevation = if (checked) 8.dp else 0.dp
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge) {
            content()
        }
    }
}

