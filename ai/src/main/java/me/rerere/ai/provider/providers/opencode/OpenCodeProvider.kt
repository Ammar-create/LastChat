package me.rerere.ai.provider.providers.opencode

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*
import me.rerere.ai.util.json
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.OpenAIProvider
import me.rerere.ai.provider.providers.ClaudeProvider
import me.rerere.ai.provider.providers.GoogleProvider
import me.rerere.ai.ui.*
import me.rerere.ai.util.providerHttpError
import me.rerere.common.platform.*

/** One OpenCode account, routed through the API actually supported by each model. */
class OpenCodeProvider(
    private val client: PlatformHttpClient,
    media: PlatformMediaEncoder,
    signer: PlatformJwtSigner,
) : Provider<ProviderSetting.OpenAI> {
    private val transport = OpenCodeHttpClient(client)
    private val openAI = OpenAIProvider(transport, media)
    private val claude = ClaudeProvider(transport, media)
    private val google = GoogleProvider(transport, media, signer)

    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> =
        openAI.listModels(providerSetting.copy(baseUrl = endpoint(providerSetting).modelsUrl.removeSuffix("/models")))

    override suspend fun getUsage(providerSetting: ProviderSetting.OpenAI): ProviderUsage? {
        val endpoint = endpoint(providerSetting)
        if (!endpoint.go) return null
        check(providerSetting.apiKey.isNotBlank() || providerSetting.apiKeyPool.any { it.enabled && it.key.isNotBlank() }) {
            "Add your OpenCode Go API key to check usage."
        }
        val key = me.rerere.ai.util.KeyRoulette.default().selectProviderKey(providerSetting).value
        val response = client.execute(PlatformHttpRequest("GET", endpoint.usageUrl,
            headers = mapOf("Authorization" to "Bearer $key", "Accept" to "application/json",
                "User-Agent" to "LastChat-Android"), proxy = providerSetting.proxy.toPlatformProxy()))
        check(response.statusCode in 200..299) { providerHttpError(response.statusCode, response.body.decodeToString()).message.orEmpty() }
        return parseOpenCodeUsage(response.body.decodeToString())
    }

    private fun endpoint(setting: ProviderSetting.OpenAI) = requireNotNull(OpenCodeEndpoint.from(setting.baseUrl))
    private fun headers(params: TextGenerationParams): TextGenerationParams {
        val custom = params.customHeaders.filterNot { it.name.equals("User-Agent", true) }.toMutableList()
        custom += CustomHeader("User-Agent", "LastChat-Android")
        if (custom.none { it.name.equals("x-opencode-session", true) }) {
            custom += CustomHeader("x-opencode-session", params.sessionId?.takeIf { it.isNotBlank() }
                ?: kotlin.uuid.Uuid.random().toString())
        }
        return params.copy(customHeaders = custom)
    }

    override suspend fun streamText(setting: ProviderSetting.OpenAI, messages: List<UIMessage>,
        params: TextGenerationParams): Flow<MessageChunk> {
        val endpoint = endpoint(setting)
        val api = endpoint.api(params.model.modelId)
        val configured = setting.copy(baseUrl = endpoint.baseUrl(api), useResponseApi = api == OpenCodeEndpoint.Api.RESPONSES)
        val request = headers(params)
        return when (api) {
            OpenCodeEndpoint.Api.CHAT, OpenCodeEndpoint.Api.RESPONSES -> openAI.streamText(configured, messages, request)
            OpenCodeEndpoint.Api.MESSAGES -> claude.streamText(configured.claude(), messages, request)
            OpenCodeEndpoint.Api.GEMINI -> google.streamText(configured.google(), messages, request)
        }
    }

    override suspend fun generateText(setting: ProviderSetting.OpenAI, messages: List<UIMessage>,
        params: TextGenerationParams): MessageChunk {
        var result = listOf(UIMessage.assistant(""))
        var usage: me.rerere.ai.core.TokenUsage? = null
        var id = ""
        streamText(setting, messages, params).collect { chunk ->
            result = result.handleMessageChunk(chunk, params.model)
            if (chunk.usage != null) usage = chunk.usage
            if (chunk.id.isNotBlank()) id = chunk.id
        }
        return MessageChunk(id, params.model.modelId, listOf(UIMessageChoice(0, null, result.last(), "stop")), usage)
    }

    override suspend fun generateImage(providerSetting: ProviderSetting, params: ImageGenerationParams): ImageGenerationResult =
        error("OpenCode does not offer image generation through this provider.")

    private fun ProviderSetting.OpenAI.claude() = ProviderSetting.Claude(id = id, name = name, apiKey = apiKey,
        apiKeyPool = apiKeyPool, keyPoolConfig = keyPoolConfig, baseUrl = baseUrl, proxy = proxy)
    private fun ProviderSetting.OpenAI.google() = ProviderSetting.Google(id = id, name = name, apiKey = apiKey,
        apiKeyPool = apiKeyPool, keyPoolConfig = keyPoolConfig, baseUrl = baseUrl, proxy = proxy)
}

private fun ProviderProxy.toPlatformProxy(): PlatformHttpProxy? = when (this) {
    ProviderProxy.None -> null
    is ProviderProxy.Http -> PlatformHttpProxy(address, port, username, password)
}

/** Native provider adapters choose their own key; normalize that key to Console's Bearer scheme. */
internal class OpenCodeHttpClient(private val delegate: PlatformHttpClient) : PlatformHttpClient {
    private fun request(request: PlatformHttpRequest): PlatformHttpRequest {
        val originalBody = request.body
        val body = if (request.url.contains("/anthropic/v1/messages") && originalBody != null)
            adaptOpenCodeClaudeBody(json.parseToJsonElement(originalBody.decodeToString()).jsonObject).toString().encodeToByteArray()
            else originalBody
        val normalized = request.copy(body = body)
        val key = normalized.headers.entries.firstOrNull { it.key.equals("x-api-key", true) }?.value
        if (key == null) return normalized
        return normalized.copy(headers = normalized.headers.filterKeys { !it.equals("x-api-key", true) && !it.equals("Authorization", true) } +
            ("Authorization" to "Bearer $key"))
    }
    override suspend fun execute(request: PlatformHttpRequest) = delegate.execute(request(request))
    override fun streamEvents(request: PlatformHttpRequest) = delegate.streamEvents(request(request))
}

/** New Claude generations reject manual thinking budgets and sampling parameters. */
internal fun adaptOpenCodeClaudeBody(body: JsonObject): JsonObject {
    val model = body["model"]?.jsonPrimitive?.contentOrNull.orEmpty()
    if (!model.startsWith("claude-")) return body
    val adaptive = Regex("^claude-(?:fable|mythos|opus|sonnet|haiku)-5(?:[-.]|$)").containsMatchIn(model) ||
        Regex("^claude-(?:opus|sonnet)-4-[6-9](?:[-.]|$)").containsMatchIn(model)
    return JsonObject(body.toMutableMap().apply {
        val thinking = get("thinking") as? JsonObject
        if (adaptive) {
            remove("temperature"); remove("top_p"); remove("top_k")
            if (thinking?.get("type")?.jsonPrimitive?.contentOrNull == "enabled") {
                put("thinking", buildJsonObject { put("type", "adaptive") })
                val budget = thinking["budget_tokens"]?.jsonPrimitive?.intOrNull
                if (budget != null) put("output_config", buildJsonObject {
                    put("effort", when {
                        budget <= 1024 -> "low"
                        budget <= 16000 -> "medium"
                        budget <= 32000 -> "high"
                        else -> "max"
                    })
                })
            }
            // Modern Claude does not accept assistant prefilling. Preserve the history and ask to continue.
            val messages = get("messages") as? JsonArray
            if (messages?.lastOrNull()?.jsonObject?.get("role")?.jsonPrimitive?.contentOrNull == "assistant")
                put("messages", JsonArray(messages + buildJsonObject { put("role", "user"); put("content", "Continue your previous response.") }))
        } else if (thinking?.get("type")?.jsonPrimitive?.contentOrNull == "enabled") {
            val max = get("max_tokens")?.jsonPrimitive?.intOrNull ?: 64000
            if (max <= 1024) put("thinking", buildJsonObject { put("type", "disabled") })
            else {
                val requested = thinking["budget_tokens"]?.jsonPrimitive?.intOrNull ?: 16000
                put("thinking", buildJsonObject {
                    put("type", "enabled"); put("budget_tokens", requested.coerceIn(1024, (max - 1024).coerceAtLeast(1024).coerceAtMost(max - 1)))
                })
            }
        }
    })
}
