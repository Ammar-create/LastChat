package me.rerere.ai.util

import kotlinx.serialization.json.*
import me.rerere.common.platform.PlatformServerEvent

/** HTTP errors can themselves be SSE, JSON, plain text or an empty proxy response. */
fun providerHttpError(status: Int?, body: String?, message: String? = null): HttpException {
    val raw = body?.trim().orEmpty()
    val documents = if (raw.startsWith("event:") || raw.startsWith("data:")) {
        raw.replace("\r\n", "\n").split("\n\n").map { frame ->
            frame.lineSequence().filter { it.startsWith("data:") }.joinToString("\n") { it.removePrefix("data:").trimStart() }
        }
    } else listOf(raw)
    fun detail(element: JsonElement?): String? = when (element) {
        is JsonObject -> listOf("message", "error", "detail", "description", "code", "type")
            .firstNotNullOfOrNull { detail(element[it]) }
        is JsonArray -> element.firstNotNullOfOrNull(::detail)
        is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() && it !in setOf("error", "null") }
        else -> null
    }
    val parsed = documents.firstNotNullOfOrNull { document ->
        runCatching { detail(json.parseToJsonElement(document)) }.getOrNull()
    }
    val fallback = when (status) {
        400 -> "The provider rejected this request. Check the model's supported settings and refresh its model list."
        401 -> "Authentication failed. Check your API key or reconnect your account."
        402 -> "The provider requires credits or an eligible subscription."
        403 -> "The provider denied access. Check the account's model permissions and network restrictions."
        404 -> "This model or endpoint is unavailable. Refresh the provider's model list."
        429 -> "The provider's usage or rate limit was reached. Check usage and try again after it resets."
        in 500..599 -> "The provider is temporarily unavailable. Please try again shortly."
        else -> message?.takeIf { it.isNotBlank() } ?: "The provider could not complete this request. Please try again."
    }
    // Do not expose HTML, serialized SSE or a JSON parser exception as a chat error.
    val plain = raw.takeIf { !it.startsWith('<') && !it.startsWith('{') && !it.startsWith('[') &&
        !it.startsWith("event:") && !it.startsWith("data:") && it.length in 1..300 }
    return HttpException(listOfNotNull(status?.takeIf { it > 0 }?.let { "HTTP $it" }, parsed ?: plain ?: fallback).joinToString(": "))
}

fun PlatformServerEvent.Failure.toProviderError(): HttpException = providerHttpError(statusCode, body, message)
