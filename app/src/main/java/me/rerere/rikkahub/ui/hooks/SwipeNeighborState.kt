package me.rerere.rikkahub.ui.hooks

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** State is read in row scopes only; swiping must not recompose the whole settings page. */
@Stable
internal class SwipeNeighborState(private val detachThreshold: Float) {
    private var activeKey by mutableStateOf<Any?>(null)
    private var offset by mutableFloatStateOf(0f)
    private var detached by mutableStateOf(false)

    fun update(key: Any, value: Float, unlocked: Boolean) {
        if (key != activeKey) detached = false
        activeKey = key
        offset = value
        if (unlocked || abs(value) >= detachThreshold) detached = true
    }

    fun end(key: Any) {
        if (activeKey == key) {
            activeKey = null
            offset = 0f
            detached = false
        }
    }

    fun <T> offsetFor(index: Int, items: List<T>, key: (T) -> Any): Float {
        val active = activeKey ?: return 0f
        if (detached) return 0f
        val anchorIndex = items.indexOfFirst { key(it) == active }
        if (anchorIndex < 0) return 0f
        return when (abs(index - anchorIndex)) {
            1 -> offset * 0.35f
            2 -> offset * 0.12f
            else -> 0f
        }
    }
}

@Composable
internal fun rememberSwipeNeighborState(): SwipeNeighborState {
    val threshold = with(LocalDensity.current) { 35.dp.toPx() }
    return remember(threshold) { SwipeNeighborState(threshold) }
}
