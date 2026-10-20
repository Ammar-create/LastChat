package me.rerere.rikkahub.ui.hooks

import org.junit.Assert.*
import org.junit.Test

class PendingListOrderTest {
    data class Row(val id: String, val text: String)

    @Test fun rapidMovesUseLatestVisualOrderAndIgnoreOldAcknowledgements() {
        val state = PendingListOrder()
        val original = listOf<Any>("a", "b", "c", "d")
        state.move(original, "a", "c")
        assertEquals(listOf("b", "c", "a", "d"), state.order)
        state.move(original, "a", "d")
        state.acknowledge(listOf("b", "c", "a", "d"))
        assertEquals(listOf("b", "c", "d", "a"), state.order)
        val writes = mutableListOf<List<Any>>()
        state.flush(writes::add)
        state.flush(writes::add)
        assertEquals(listOf(listOf("b", "c", "d", "a")), writes)
        state.acknowledge(listOf("b", "c", "d", "a"))
        assertNull(state.order)
    }

    @Test fun orderMergeKeepsEditsDeletionsAndHiddenOrNewRows() {
        val latest = listOf(Row("a", "renamed"), Row("hidden", "keep position"), Row("b", "new text"), Row("added", "new"))
        val reordered = latest.reorderedByKeys(listOf("b", "deleted", "a")) { it.id }
        assertEquals(listOf("b", "hidden", "a", "added"), reordered.map { it.id })
        assertEquals("renamed", reordered[2].text)
        assertEquals("new text", reordered[0].text)
    }

    @Test fun headerKeysAndInvalidMovesDoNothingAndFailuresCanBeRetried() {
        val state = PendingListOrder()
        val keys = listOf<Any>("a", "b")
        state.move(keys, "header", "b")
        assertNull(state.order)
        state.move(keys, "a", "b")
        val failedOrder = state.order!!
        state.abandon(failedOrder)
        state.move(keys, "a", "b")
        val writes = mutableListOf<List<Any>>()
        state.flush(writes::add)
        assertEquals(listOf(failedOrder), writes)
    }
}
