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
        try { state.flush { error("write rejected") } } catch (_: IllegalStateException) { }
        assertEquals(failedOrder, state.order)
        val writes = mutableListOf<List<Any>>()
        state.flush(writes::add)
        assertEquals(listOf(failedOrder), writes)
    }
    @Test fun matchingUnsubmittedSnapshotCannotAcknowledgeAnUndo() {
        val state = PendingListOrder()
        val original = listOf<Any>("a", "b", "c")
        val writes = mutableListOf<List<Any>>()
        state.move(original, "a", "c")
        state.flush(writes::add)
        state.move(original, "a", "b")
        assertEquals(original, state.order)
        state.acknowledge(original)
        assertEquals(original, state.order)
        state.flush(writes::add)
        state.acknowledge(writes.first())
        assertEquals(original, state.order)
        state.acknowledge(writes.last())
        assertNull(state.order)
        assertEquals(listOf(listOf("b", "c", "a"), original), writes)
    }

    @Test fun acknowledgementDuringGestureDoesNotDiscardTheVisualOrder() {
        val state = PendingListOrder()
        val original = listOf<Any>("a", "b", "c")
        state.beginDrag()
        state.move(original, "a", "c")
        state.flush { state.acknowledge(it) } // Lifecycle flush while the pointer is still down.
        assertEquals(listOf("b", "c", "a"), state.order)
        state.finishDrag(listOf("b", "c", "a"))
        assertNull(state.order)
    }

    @Test fun synchronousDraftAcknowledgementCannotLeaveAnObsoleteCommittedMarker() {
        val state = PendingListOrder()
        val original = listOf<Any>("a", "b", "c")
        state.move(original, "a", "c")
        state.flush { state.acknowledge(it) }
        assertNull(state.order)
        state.move(listOf("b", "c", "a"), "a", "b")
        val writes = mutableListOf<List<Any>>()
        state.flush(writes::add)
        assertEquals(listOf(original), writes)
    }

    @Test fun returningToTheSourceBeforeAnySaveDoesNotWrite() {
        val state = PendingListOrder()
        val source = listOf<Any>("a", "b", "c")
        state.beginDrag()
        state.move(source, "a", "c")
        state.move(source, "a", "b")
        state.finishDrag(source)
        state.flush { fail("A drag returning to its start must be a no-op") }
        assertNull(state.order)
    }

}
