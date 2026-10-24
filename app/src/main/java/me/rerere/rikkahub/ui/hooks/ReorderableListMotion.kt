package me.rerere.rikkahub.ui.hooks

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.rerere.rikkahub.ui.motion.LocalMotionPolicy
import me.rerere.rikkahub.ui.theme.AppShapes
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState

internal val LocalListReordering = compositionLocalOf { false }

/** Keep the persistence lane out of the drag loop, including long edge-scroll gestures. */
@Composable
internal fun <T> rememberAppReorderableLazyListState(
    lazyListState: LazyListState,
    items: ReorderableItems<T>,
    scrollThresholdPadding: PaddingValues = PaddingValues(0.dp),
    enabled: Boolean = true,
): ReorderableLazyListState {
    val latestItems = rememberUpdatedState(items)
    val layoutDirection = LocalLayoutDirection.current
    // The library has one symmetric threshold. Adding 64dp at the top gives a
    // 96dp upward zone with the 32dp threshold below, while keeping the downward
    // zone at 32dp. Preserve each screen's toolbar and bottom-control insets.
    val edgePadding = remember(scrollThresholdPadding, layoutDirection) {
        PaddingValues(
            start = scrollThresholdPadding.calculateStartPadding(layoutDirection),
            top = scrollThresholdPadding.calculateTopPadding() + 64.dp,
            end = scrollThresholdPadding.calculateEndPadding(layoutDirection),
            bottom = scrollThresholdPadding.calculateBottomPadding(),
        )
    }
    // Calvin recreates its controller when padding changes. Toolbar collapse/insets must not
    // replace it under the finger, or during the dropped row's settling animation.
    var appliedPadding by remember(lazyListState) { mutableStateOf(edgePadding) }
    val state = rememberReorderableLazyListState(
        lazyListState,
        scrollThresholdPadding = appliedPadding,
        scrollThreshold = 32.dp,
    ) { from, to ->
        if (enabled) {
            items.pending.beginDrag()
            items.move(from.key, to.key)
        }
    }
    LaunchedEffect(edgePadding, state.isAnyItemDragging) {
        if (!state.isAnyItemDragging && appliedPadding != edgePadding) {
            delay(1_000)
            appliedPadding = edgePadding
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.isAnyItemDragging }.collect { dragging ->
            if (dragging) latestItems.value.pending.beginDrag()
            else latestItems.value.finishDrag()
        }
    }
    return state
}

/** One placement animation and a layer-only lift; no abrupt size changes or bouncing neighbors. */
@Composable
internal fun LazyItemScope.AppReorderableItem(
    state: ReorderableLazyListState,
    key: Any,
    content: @Composable ReorderableCollectionItemScope.(isDragging: Boolean) -> Unit,
) {
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    ReorderableItem(
        state = state,
        key = key,
        animateItemModifier = if (reduceMotion) Modifier else Modifier.animateItem(
            fadeInSpec = null,
            fadeOutSpec = null,
            placementSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 700f),
        ),
    ) { isDragging ->
        val lift = animateFloatAsState(
            targetValue = if (isDragging && !reduceMotion) 1f else 0f,
            animationSpec = if (reduceMotion) snap() else spring(
                dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 700f, visibilityThreshold = 0.0005f,
            ),
            label = "reorderLift",
        )
        CompositionLocalProvider(LocalListReordering provides state.isAnyItemDragging) {
            Box(Modifier.graphicsLayer {
                // Scaling the handle's ancestors also scales its pointer deltas. Elevation
                // keeps both row size and touch coordinates fixed throughout the gesture.
                shadowElevation = 4.dp.toPx() * lift.value
                shape = AppShapes.ListItem
            }) { content(isDragging) }
        }
    }
}
