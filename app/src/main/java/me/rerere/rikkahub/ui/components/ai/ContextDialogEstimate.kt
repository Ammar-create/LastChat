package me.rerere.rikkahub.ui.components.ai

import me.rerere.ai.context.ContextTokenEstimator
import me.rerere.ai.context.smartInputBudget
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ContextCompressionPreview
import me.rerere.rikkahub.service.activeContextMessages
import me.rerere.rikkahub.service.previewContextCompression

internal data class ContextDialogEstimate(
    val preview: ContextCompressionPreview?,
    val usedTokens: Int,
    val budget: Int?,
)

/** Runs off the UI thread, including token counting for long conversations. */
internal fun estimateContextDialog(
    conversation: Conversation,
    assistant: Assistant,
    model: Model?,
): ContextDialogEstimate = ContextDialogEstimate(
    preview = model?.let { previewContextCompression(conversation, assistant, it) },
    usedTokens = (
        ContextTokenEstimator.messagesTokens(activeContextMessages(conversation), model).toLong() +
            ContextTokenEstimator.textTokens(conversation.contextSummary.orEmpty() + assistant.systemPrompt, model)
        ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
    budget = model?.let { smartInputBudget(it, assistant.maxTokens) },
)
