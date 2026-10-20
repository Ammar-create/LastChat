package me.rerere.rikkahub.service

import me.rerere.ai.ui.MessageNode
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ContextSummaryRebaseTest {
    private fun source() = Conversation.ofId(Uuid.random(), messages = listOf(
        MessageNode.of(UIMessage.user("attachment")),
        MessageNode.of(UIMessage.assistant("first tool follow-up")),
        MessageNode.of(UIMessage.user("next question")),
        MessageNode.of(UIMessage.assistant("next reply")),
    ))

    @Test
    fun summaryPreservesNewerRepliesAndMetadata() {
        val source = source()
        val live = source.copy(title = "New title", messageNodes = source.messageNodes +
            MessageNode.of(UIMessage.assistant("new follow-up")))
        val result = applyContextSummaryIfCurrent(live, source, "summary", 1, 123L)!!
        assertEquals(live.messageNodes, result.messageNodes)
        assertEquals("New title", result.title)
        assertEquals("summary", result.contextSummary)
        assertEquals(1, result.contextSummaryUpToIndex)
        assertEquals(123L, result.lastRefreshTime)
    }

    @Test
    fun deletedOrRegeneratedInputCannotBeResurrected() {
        val source = source()
        val deleted = source.copy(messageNodes = source.messageNodes.drop(2))
        assertNull(applyContextSummaryIfCurrent(deleted, source, "summary", 1, 123L))
        val regenerated = source.copy(messageNodes = source.messageNodes.toMutableList().apply {
            this[1] = MessageNode.of(UIMessage.assistant("replacement reply"))
        })
        assertNull(applyContextSummaryIfCurrent(regenerated, source, "summary", 1, 123L))
    }

    @Test
    fun editedInputOrChangedSummaryInvalidatesLateResult() {
        val source = source()
        val edited = source.copy(messageNodes = source.messageNodes.toMutableList().apply {
            this[0] = MessageNode.of(UIMessage.user("edited attachment"))
        })
        assertNull(applyContextSummaryIfCurrent(edited, source, "summary", 1, 123L))
        assertNull(applyContextSummaryIfCurrent(source.copy(contextSummary = "newer summary"),
            source, "old summary", 1, 123L))
        assertNull(applyContextSummaryIfCurrent(source.copy(assistantId = Uuid.random()),
            source, "old summary", 1, 123L))
    }
}
