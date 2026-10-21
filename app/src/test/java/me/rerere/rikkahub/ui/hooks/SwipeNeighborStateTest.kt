package me.rerere.rikkahub.ui.hooks

import org.junit.Assert.*
import org.junit.Test

class SwipeNeighborStateTest {
    @Test fun neighborsFollowTheCurrentStableKeyAndIgnoreLateEndsFromAnotherRow() {
        val state = SwipeNeighborState(35f)
        val source = listOf("a", "b", "c", "d")
        state.update("a", -20f, false)
        assertEquals(-7f, state.offsetFor(1, source) { it }, 0.001f)
        assertEquals(0f, state.offsetFor(0, source) { it }, 0f)
        val moved = listOf("b", "c", "a", "d")
        assertEquals(-7f, state.offsetFor(3, moved) { it }, 0.001f)
        state.update("c", -10f, false)
        state.end("a")
        assertEquals(-3.5f, state.offsetFor(0, moved) { it }, 0.001f)
        state.end("c")
        assertEquals(0f, state.offsetFor(0, moved) { it }, 0f)
    }

    @Test fun detachmentDoesNotToggleMidGestureAndRemovedAnchorsCannotMoveRows() {
        val state = SwipeNeighborState(35f)
        val rows = listOf("a", "b", "c")
        state.update("b", -40f, false)
        state.update("b", -10f, false)
        assertEquals(0f, state.offsetFor(0, rows) { it }, 0f)
        state.end("b")
        state.update("b", -10f, false)
        assertEquals(-3.5f, state.offsetFor(0, rows) { it }, 0.001f)
        assertEquals(0f, state.offsetFor(0, listOf("a", "c")) { it }, 0f)
    }
}
