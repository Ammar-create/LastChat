package me.rerere.rikkahub.utils

import android.content.Context
import me.rerere.locallm.LiteRtRuntime
import me.rerere.rikkahub.R

fun Throwable.toLocalInferenceUserMessage(context: Context): String? {
    val memoryError = generateSequence(this) { current ->
        current.cause?.takeUnless { it === current }
    }.filterIsInstance<LiteRtRuntime.InsufficientMemoryException>().firstOrNull()

    generateSequence(this) { current -> current.cause?.takeUnless { it === current } }
        .filterIsInstance<LiteRtRuntime.IncompatibleModelException>().firstOrNull()
        ?.let { return it.toUserMessage(context) }

    return memoryError?.info?.let { info ->
        context.getString(
            R.string.local_llm_insufficient_memory_format,
            info.requiredMb,
            info.modelMb,
            info.availableMb,
        )
    } ?: message
}

fun LiteRtRuntime.IncompatibleModelException.toUserMessage(context: Context): String {
    val headline = when (reason) {
        LiteRtRuntime.IncompatibleModelException.Reason.INVALID_FILE ->
            context.getString(R.string.local_llm_invalid_model_file)
        LiteRtRuntime.IncompatibleModelException.Reason.UNSUPPORTED ->
            context.getString(R.string.local_llm_incompatible_model)
    }
    return headline + "\n\n" + context.getString(R.string.local_llm_technical_detail_format, detail)
}
