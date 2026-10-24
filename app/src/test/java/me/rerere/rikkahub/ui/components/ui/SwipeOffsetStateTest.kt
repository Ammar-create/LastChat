package me.rerere.rikkahub.ui.components.ui

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class SwipeOffsetStateTest {
    @Test fun aNewTouchInterruptsSettlingWithoutQueuedSnapsOverwritingItsOffset() {
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined + clock)
        try {
            val state = SwipeOffsetState()
            state.dragTo(-100f)
            state.settle(scope, 0f)
            clock.sendFrame(0)
            clock.sendFrame(16_000_000)
            assertTrue(state.value > -100f)
            state.dragTo(-60f)
            clock.sendFrame(32_000_000)
            clock.sendFrame(48_000_000)
            assertEquals(-60f, state.value, 0f)
            repeat(100) { state.dragTo(-60f - it) }
            clock.sendFrame(64_000_000)
            assertEquals(-159f, state.value, 0f)
        } finally { scope.cancel() }
    }
}
