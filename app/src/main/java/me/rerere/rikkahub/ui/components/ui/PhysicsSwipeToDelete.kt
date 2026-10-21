package me.rerere.rikkahub.ui.components.ui

import me.rerere.rikkahub.ui.theme.LocalDarkMode

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import me.rerere.rikkahub.ui.hooks.LocalListReordering
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.theme.AppShapes
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * Position of item in a group for corner radius calculation
 */
enum class ItemPosition {
    ONLY,   // Only item in group - all corners rounded
    FIRST,  // First item - top corners rounded
    MIDDLE, // Middle item - no corners rounded
    LAST    // Last item - bottom corners rounded
}

fun ItemPosition.listItemShape(): RoundedCornerShape = when (this) {
    ItemPosition.ONLY -> AppShapes.ListItem
    ItemPosition.FIRST -> AppShapes.ListItemFirst
    ItemPosition.MIDDLE -> AppShapes.ListItemMiddle
    ItemPosition.LAST -> AppShapes.ListItemLast
}

/** A single offset owner: dragging never queues snap coroutines behind a settling animation. */
@Stable
internal class SwipeOffsetState {
    var value by mutableFloatStateOf(0f)
        private set
    private var animation: Job? = null

    fun dragTo(value: Float) {
        animation?.cancel()
        animation = null
        this.value = value
    }

    fun settle(scope: CoroutineScope, target: Float, damping: Float = 0.85f, stiffness: Float = 400f) {
        animation?.cancel()
        val start = value
        animation = scope.launch {
            Animatable(start).animateTo(target, spring(dampingRatio = damping, stiffness = stiffness)) {
                this@SwipeOffsetState.value = value
            }
        }
    }
}

/**
 * Physics-based swipe-to-delete component with:
 * - Heavy drag resistance (item moves slower than finger)
 * - Magnetic unlock behavior (sticks then snaps)
 * - Position-based corner radius (top/bottom/middle)
 * - Corner radius animation on unlock (all corners become rounded)
 * - Spring snap-back animation
 * - Edge fade gradient
 * - Haptic feedback on unlock/lock
 * - Neighbor drag influence support
 *
 * @param deleteEnabled Whether delete is allowed. When false, swipe is very difficult and always springs back.
 * @param neighborOffset Offset from a neighboring item being dragged (0 = not influenced)
 * @param onDragProgress Callback reporting current drag offset and unlock state for neighbor coordination
 */
@Composable
fun PhysicsSwipeToDelete(
    modifier: Modifier = Modifier,
    onDelete: () -> Unit,
    deleteEnabled: Boolean = true,
    position: ItemPosition = ItemPosition.ONLY,
    groupCornerRadius: Dp = 24.dp,
    itemCornerRadius: Dp = 10.dp,
    neighborOffset: Float = 0f,
    onDragProgress: ((offset: Float, isUnlocked: Boolean) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    content: @Composable (shape: Shape) -> Unit
) {
    val density = LocalDensity.current
    val haptics = rememberPremiumHaptics()
    val scope = rememberCoroutineScope()
    val reordering = LocalListReordering.current
    val latestDragProgress = rememberUpdatedState(onDragProgress)
    val latestDragEnd = rememberUpdatedState(onDragEnd)

    // Physics parameters - when delete is disabled, make it much harder to move
    val dragFriction = if (deleteEnabled) 0.6f else 0.15f // Item moves at 60% or 15% of finger speed
    val revealDistancePx = with(density) { 140.dp.toPx() }
    val unlockThresholdPx = revealDistancePx * 0.25f // 1/4 of reveal distance = 35dp
    val magneticPullStrength = 0.3f // How strongly item "sticks" before unlock

    // Animation state
    val offsetX = remember { SwipeOffsetState() }
    var isUnlocked by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }

    // A vertical reorder must not carry an open swipe, or compete with horizontal recognition.
    LaunchedEffect(deleteEnabled, reordering) {
        if (!deleteEnabled || reordering) {
            if (isDragging || isUnlocked || offsetX.value != 0f) latestDragEnd.value?.invoke()
            offsetX.dragTo(0f)
            isUnlocked = false
            isDragging = false
        }
    }

    LaunchedEffect(offsetX) {
        snapshotFlow { Triple(offsetX.value, isUnlocked, isDragging) }.collect { (offset, unlocked, dragging) ->
            if (dragging && !unlocked) latestDragProgress.value?.invoke(offset, unlocked)
        }
    }

    // Animate neighbor offset with spring
    val animatedNeighborOffset = remember { Animatable(0f) }
    var wasNeighborInfluenced by remember { mutableStateOf(false) }

    LaunchedEffect(neighborOffset) {
        if (neighborOffset != 0f) {
            animatedNeighborOffset.snapTo(neighborOffset)
            wasNeighborInfluenced = true
        } else if (wasNeighborInfluenced) {
            // Immediately spring back - no delay conditions
            wasNeighborInfluenced = false
            animatedNeighborOffset.animateTo(
                targetValue = 0f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 1000f)
            )
        }
    }

    // Calculate unlock progress (0 = locked, 1 = unlocked) - only for own drag, not neighbor
    val unlockProgress by remember(unlockThresholdPx) {
        derivedStateOf {
            (offsetX.value.absoluteValue / unlockThresholdPx).coerceIn(0f, 1f)
        }
    }

    // Position-based corner radius with smooth animation on position change and unlock
    val groupRadiusPx = with(density) { groupCornerRadius.toPx() }
    val itemRadiusPx = with(density) { itemCornerRadius.toPx() }

    // Animate base radii when position changes
    val targetTopRadius = when (position) {
        ItemPosition.ONLY, ItemPosition.FIRST -> groupRadiusPx
        ItemPosition.MIDDLE, ItemPosition.LAST -> itemRadiusPx
    }
    val targetBottomRadius = when (position) {
        ItemPosition.ONLY, ItemPosition.LAST -> groupRadiusPx
        ItemPosition.MIDDLE, ItemPosition.FIRST -> itemRadiusPx
    }

    val animatedTopRadius by androidx.compose.animation.core.animateFloatAsState(
        targetValue = targetTopRadius,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "topRadius"
    )
    val animatedBottomRadius by androidx.compose.animation.core.animateFloatAsState(
        targetValue = targetBottomRadius,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "bottomRadius"
    )

    // Calculate shape based on animated position and unlock progress
    // Only apply unlock progress if this item is being dragged (not neighbor influence)
    val shape by remember(density, groupRadiusPx, itemRadiusPx) {
        derivedStateOf {
            // Only interpolate corners for own unlock progress (not neighbor influence)
            val ownUnlockProgress = unlockProgress

            val finalTopStart = animatedTopRadius + (groupRadiusPx - animatedTopRadius) * ownUnlockProgress
            val finalTopEnd = animatedTopRadius + (groupRadiusPx - animatedTopRadius) * ownUnlockProgress
            val finalBottomEnd = animatedBottomRadius + (groupRadiusPx - animatedBottomRadius) * ownUnlockProgress
            val finalBottomStart = animatedBottomRadius + (groupRadiusPx - animatedBottomRadius) * ownUnlockProgress

            RoundedCornerShape(
                topStart = with(density) { finalTopStart.toDp() },
                topEnd = with(density) { finalTopEnd.toDp() },
                bottomEnd = with(density) { finalBottomEnd.toDp() },
                bottomStart = with(density) { finalBottomStart.toDp() }
            )
        }
    }

    // Background color
    val backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
    val fadeColor = MaterialTheme.colorScheme.surfaceContainerLow

    Box(
        modifier = modifier
            .fillMaxWidth()
    ) {
        // Background with action buttons
        Row(
            modifier = Modifier
                .matchParentSize()
                .padding(end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Cancel button - only show when delete is enabled
            if (deleteEnabled) {
                PhysicsSwipeActionButton(
                    onClick = {
                        haptics.perform(HapticPattern.Cancel)
                        isUnlocked = false
                        offsetX.settle(scope, 0f)
                    },
                    containerColor = MaterialTheme.colorScheme.surface,
                    alpha = { (offsetX.value.absoluteValue / unlockThresholdPx).coerceIn(0f, 1f) }
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.cancel),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Delete button - only show when delete is enabled
            if (deleteEnabled) {
                PhysicsSwipeActionButton(
                    onClick = {
                        haptics.perform(HapticPattern.Error)
                        onDelete()
                    },
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    alpha = { (offsetX.value.absoluteValue / unlockThresholdPx).coerceIn(0f, 1f) }
                ) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        // Foreground content with swipe gesture
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationX = offsetX.value + animatedNeighborOffset.value
                }
                .clip(shape)
                .background(fadeColor)
                .pointerInput(deleteEnabled, reordering, revealDistancePx, dragFriction) {
                    if (reordering) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = {
                            offsetX.dragTo(offsetX.value) // Interrupt settling before reading the next delta.
                            isDragging = true
                        },
                        onDragEnd = {
                            isDragging = false
                            latestDragEnd.value?.invoke()
                            if (deleteEnabled && offsetX.value.absoluteValue > unlockThresholdPx) {
                                if (!isUnlocked) haptics.perform(HapticPattern.Pop)
                                isUnlocked = true
                                offsetX.settle(scope, -revealDistancePx)
                            } else {
                                if (isUnlocked) haptics.perform(HapticPattern.Thud)
                                isUnlocked = false
                                offsetX.settle(scope, 0f)
                            }
                        },
                        onDragCancel = {
                            isDragging = false
                            offsetX.settle(scope, if (isUnlocked) -revealDistancePx else 0f)
                            latestDragEnd.value?.invoke()
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val currentOffset = offsetX.value
                            if (dragAmount < 0 || currentOffset < 0) {
                                val friction = if (currentOffset.absoluteValue < unlockThresholdPx && !isUnlocked) {
                                    dragFriction * (1f - magneticPullStrength * (currentOffset.absoluteValue / unlockThresholdPx))
                                } else dragFriction
                                val next = (currentOffset + dragAmount * friction)
                                    .coerceIn(-revealDistancePx * 1.2f, 0f)
                                offsetX.dragTo(next)
                                val wasUnder = currentOffset.absoluteValue < unlockThresholdPx
                                val isOver = next.absoluteValue >= unlockThresholdPx
                                if (wasUnder && isOver && !isUnlocked) haptics.perform(HapticPattern.Pop)
                                else if (!wasUnder && !isOver) haptics.perform(HapticPattern.Tick)
                            }
                        },
                    )
                }
        ) {
            content(shape)
        }
    }
}

/**
 * Physics-animated action button for swipe-to-delete cancel/delete actions
 */
@Composable
private fun PhysicsSwipeActionButton(
    onClick: () -> Unit,
    containerColor: Color,
    alpha: () -> Float,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale = animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = spring(
            dampingRatio = 0.6f, // Round/Clicky Standard
            stiffness = 300f     // Round/Clicky Standard
        ),
        label = "button_scale"
    )
    val pressAlpha = animateFloatAsState(
        targetValue = if (isPressed) 0.7f else 1f,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = 300f
        ),
        label = "button_alpha"
    )

    val latestAlpha = rememberUpdatedState(alpha)
    val visible by remember { derivedStateOf { latestAlpha.value() > 0.01f } }
    Surface(
        onClick = onClick,
        enabled = visible,
        shape = CircleShape,
        color = containerColor,
        tonalElevation = 4.dp,
        interactionSource = interactionSource,
        modifier = Modifier
            .size(44.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = latestAlpha.value() * pressAlpha.value
            }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            content()
        }
    }
}
