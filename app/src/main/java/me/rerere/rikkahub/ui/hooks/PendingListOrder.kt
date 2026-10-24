package me.rerere.rikkahub.ui.hooks

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/** Reorder only the requested IDs in the latest list; keep hidden/new rows and edited objects. */
internal fun <T, K> List<T>.reorderedByKeys(order: List<K>, key: (T) -> K): List<T> {
    if (order.isEmpty() || size < 2) return this
    val byId = associateBy(key)
    val seen = hashSetOf<K>()
    val ordered = order.mapNotNull { id -> if (seen.add(id)) byId[id] else null }
    val included = ordered.map(key).toSet()
    val iterator = ordered.iterator()
    return map { if (key(it) in included) iterator.next() else it }
}

@Stable
internal class PendingListOrder {
    var order by mutableStateOf<List<Any>?>(null)
        private set
    var dragging by mutableStateOf(false)
        private set
    private var committed: List<Any>? = null

    fun beginDrag() { dragging = true }

    fun move(keys: List<Any>, from: Any, to: Any) {
        val current = keys.reorderedByKeys(order.orEmpty()) { it }
        val fromIndex = current.indexOf(from)
        val toIndex = current.indexOf(to)
        if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return
        order = current.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
    }

    fun acknowledge(keys: List<Any>) {
        val requested = order ?: return
        // A matching pre-save snapshot (e.g. dragging back to the original position) isn't an
        // acknowledgement. An older write may still be on its way. Keep the local order until
        // the latest submitted order reaches the source, and never clear it during a gesture.
        if (!dragging && committed == requested && keys.reorderedByKeys(requested) { it } == keys) {
            order = null
            committed = null
        }
    }

    fun finishDrag(keys: List<Any>) {
        dragging = false
        acknowledge(keys)
        if (committed == null && keys.reorderedByKeys(order.orEmpty()) { it } == keys) order = null
    }

    /** Also used at lifecycle boundaries, when an unfinished drag still needs saving. */
    fun flush(commit: (List<Any>) -> Unit) {
        val requested = order ?: return
        if (requested != committed) {
            // Mark first: a synchronous draft update can acknowledge inside the callback.
            val previous = committed
            committed = requested
            try {
                commit(requested)
            } catch (error: Throwable) {
                committed = previous
                throw error
            }
        }
    }
}

@Stable
internal class ReorderableItems<T>(
    private val visibleItems: State<List<T>>,
    internal val pending: PendingListOrder,
    val move: (Any, Any) -> Unit,
    internal val finishDrag: () -> Unit,
) {
    val items: List<T> get() = visibleItems.value
}

/** Local feedback during the gesture, one ordered save after dropping, and lifecycle flushing. */
@Composable
internal fun <T> rememberReorderableItems(
    source: List<T>,
    key: (T) -> Any,
    eagerCommit: Boolean = false,
    onCommit: (List<Any>) -> Unit,
): ReorderableItems<T> {
    val state = remember { PendingListOrder() }
    val latestSource = rememberUpdatedState(source)
    val latestKey = rememberUpdatedState(key)
    val latestCommit = rememberUpdatedState(onCommit)
    val eager = rememberUpdatedState(eagerCommit)
    val keys = remember(source) { source.map(key) }
    LaunchedEffect(keys) { state.acknowledge(keys) }
    LaunchedEffect(state.order, state.dragging) {
        if (state.order == null || state.dragging) return@LaunchedEffect
        if (!eagerCommit) delay(120)
        state.flush(latestCommit.value)
        // No timer resets a still-unacknowledged order: a slow write must not make rows jump.
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(state, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) state.flush(latestCommit.value)
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            state.flush(latestCommit.value)
        }
    }
    // Publish the current source and local order together, including additions and field edits
    // that arrive before persistence acknowledges the order.
    val visible = rememberUpdatedState(remember(source, state.order) {
        source.reorderedByKeys(state.order.orEmpty(), key)
    })
    return remember(state) {
        ReorderableItems(visible, state, move = { from, to ->
            state.move(latestSource.value.map(latestKey.value), from, to)
            if (eager.value && !state.dragging) state.flush(latestCommit.value)
        }, finishDrag = {
            state.finishDrag(latestSource.value.map(latestKey.value))
            if (eager.value) state.flush(latestCommit.value)
        })
    }
}
