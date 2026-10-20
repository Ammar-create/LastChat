package me.rerere.rikkahub.ui.hooks

import androidx.compose.runtime.*
import kotlinx.coroutines.delay

/** Reorder only the requested IDs in the latest list; keep hidden/new rows and edited objects. */
internal fun <T, K> List<T>.reorderedByKeys(order: List<K>, key: (T) -> K): List<T> {
    val byId = associateBy(key)
    val seen = hashSetOf<K>()
    val ordered = order.mapNotNull { id -> if (seen.add(id)) byId[id] else null }
    val included = ordered.map(key).toSet()
    val iterator = ordered.iterator()
    return map { if (key(it) in included) iterator.next() else it }
}

internal class PendingListOrder {
    var order by mutableStateOf<List<Any>?>(null)
        private set
    private var committed: List<Any>? = null

    fun move(keys: List<Any>, from: Any, to: Any) {
        val current = keys.reorderedByKeys(order.orEmpty()) { it }
        val fromIndex = current.indexOf(from)
        val toIndex = current.indexOf(to)
        if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return
        order = current.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
    }

    fun acknowledge(keys: List<Any>) {
        val requested = order ?: return
        if (keys.reorderedByKeys(requested) { it } == keys) {
            order = null
            committed = null
        }
    }

    fun flush(commit: (List<Any>) -> Unit) {
        val requested = order ?: return
        if (requested != committed) {
            commit(requested)
            committed = requested
        }
    }

    fun abandon(requested: List<Any>) {
        if (order == requested) {
            order = null
            committed = null
        }
    }
}

internal class ReorderableItems<T>(val items: List<T>, val move: (Any, Any) -> Unit)

/** Immediate drag feedback, debounced ordered persistence, and no rollback while an ack is in flight. */
@Composable
internal fun <T> rememberReorderableItems(
    source: List<T>,
    key: (T) -> Any,
    eagerCommit: Boolean = false,
    onCommit: (List<Any>) -> Unit,
): ReorderableItems<T> {
    val state = remember { PendingListOrder() }
    val latestSource by rememberUpdatedState(source)
    val latestKey by rememberUpdatedState(key)
    val latestCommit by rememberUpdatedState(onCommit)
    val keys = source.map(key)
    LaunchedEffect(keys) { state.acknowledge(keys) }
    LaunchedEffect(state.order) {
        val requested = state.order ?: return@LaunchedEffect
        delay(120)
        state.flush(latestCommit)
        // Don't leave a permanently pinned local order after a rejected/failed write.
        delay(5_000)
        state.abandon(requested)
    }
    DisposableEffect(state) { onDispose { state.flush(latestCommit) } }
    return ReorderableItems(source.reorderedByKeys(state.order.orEmpty(), key)) { from, to ->
        state.move(latestSource.map(latestKey), from, to)
        // Provider forms already debounce disk writes. Update their pending draft before any
        // parent lifecycle flush, including when the page closes in this same frame.
        if (eagerCommit) state.flush(latestCommit)
    }
}
