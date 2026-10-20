package me.rerere.ai.provider.providers.opencode

import me.rerere.common.http.urlPartsOrNull

/** OpenCode's documented model/API mapping, checked against Console and Go on 2026-10-19. */
data class OpenCodeEndpoint(val go: Boolean) {
    val root: String get() = "https://opencode.ai/inference" + if (go) "/go" else ""
    val modelsUrl: String get() = "$root/v1/models"
    val usageUrl: String get() = "$root/v1/usage"

    enum class Api { CHAT, RESPONSES, MESSAGES, GEMINI }

    fun api(modelId: String): Api {
        val id = modelId.lowercase()
        return when {
            id.startsWith("gemini-") -> Api.GEMINI
            id.startsWith("gpt-") || id.startsWith("grok-") || id.startsWith("muse-spark-") -> Api.RESPONSES
            id.startsWith("claude-") -> Api.MESSAGES
            // Go's Qwen3.8 Max and MiniMax M3 use Messages; Zen serves these two via Chat.
            id.startsWith("qwen") && (id != "qwen3.8-max" || go) -> Api.MESSAGES
            go && id in setOf("minimax-m2.7", "minimax-m3") -> Api.MESSAGES
            else -> Api.CHAT
        }
    }

    fun baseUrl(api: Api): String = root + when (api) {
        Api.CHAT, Api.RESPONSES -> "/openai/v1"
        Api.MESSAGES -> "/anthropic/v1"
        Api.GEMINI -> "/google/v1beta"
    }

    companion object {
        fun from(baseUrl: String): OpenCodeEndpoint? {
            val parts = baseUrl.urlPartsOrNull() ?: return null
            if (parts.scheme != "https" || parts.host != "opencode.ai") return null
            val path = parts.encodedPath.trimEnd('/')
            if (path != "/zen/v1" && path != "/zen/go/v1" &&
                path != "/inference" && !path.startsWith("/inference/")) return null
            return OpenCodeEndpoint(path == "/zen/go/v1" || path.startsWith("/inference/go/"))
        }
    }
}
