package me.rerere.rikkahub.ui.components.ai

import androidx.compose.material3.SheetState
import androidx.compose.runtime.withFrameNanos

/** Finish and dispose the sheet's window before attaching a separate dialog window. */
internal suspend fun SheetState.hideBeforeDialog(onHidden: () -> Unit, onOpen: () -> Unit) {
    hide()
    if (isVisible) return
    onHidden()
    // The first frame schedules removal; the second follows that composition's apply phase.
    withFrameNanos { }
    withFrameNanos { }
    onOpen()
}
