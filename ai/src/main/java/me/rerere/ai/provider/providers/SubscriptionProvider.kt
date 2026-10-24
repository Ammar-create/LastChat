package me.rerere.ai.provider.providers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import me.rerere.ai.util.KeyRequestException
import me.rerere.ai.util.json
import me.rerere.common.platform.*

/** Resolves encrypted, rotating credentials at request time; never stores them in settings. */
fun interface SubscriptionTokenSource {
    suspend fun accessToken(setting: ProviderSetting.OpenAI): String
    suspend fun refreshAccessToken(setting: ProviderSetting.OpenAI): String = accessToken(setting)
    suspend fun usesCodex(setting: ProviderSetting.OpenAI): Boolean = false
    suspend fun accountId(setting: ProviderSetting.OpenAI): String? = null
}

class SubscriptionProvider(
    private val client: PlatformHttpClient,
    mediaEncoder: PlatformMediaEncoder,
    private val tokens: SubscriptionTokenSource,
    private val fallbackClient: PlatformHttpClient = client,
) : Provider<ProviderSetting.OpenAI> {
    private val delegate = OpenAIProvider(client, mediaEncoder)
    private val fallbackDelegate = OpenAIProvider(fallbackClient, mediaEncoder)
    private fun http(setting: ProviderSetting.OpenAI) = if (setting.subscriptionDnsFallback) fallbackClient else client
    private fun delegate(setting: ProviderSetting.OpenAI) = if (setting.subscriptionDnsFallback) fallbackDelegate else delegate
    private suspend fun authHeaders(setting: ProviderSetting.OpenAI): Map<String, String> = buildMap {
        put("Authorization", "Bearer ${setting.apiKey}")
        if (setting.subscriptionUsesCodex) {
            put("originator", "codex_cli_rs")
            tokens.accountId(setting)?.let { put("ChatGPT-Account-ID", it) }
        }
    }
    private suspend fun requestParams(setting: ProviderSetting.OpenAI, params: TextGenerationParams): TextGenerationParams {
        val headers = authHeaders(setting).filterKeys { !it.equals("Authorization", true) }.toMutableMap()
        if (!params.sessionId.isNullOrBlank()) {
            if (setting.subscriptionUsesCodex) headers["session-id"] = params.sessionId
            if (setting.subscriptionAccount == SubscriptionAccountType.GROK) headers["x-grok-conv-id"] = params.sessionId
        }
        return params.copy(customHeaders = params.customHeaders.filterNot { header ->
            header.name.equals("Authorization", true) || headers.keys.any { it.equals(header.name, true) }
        } + headers.map { CustomHeader(it.key, it.value) })
    }

    private suspend fun authenticated(setting: ProviderSetting.OpenAI): ProviderSetting.OpenAI {
        val account = requireNotNull(setting.subscriptionAccount)
        val codex = account == SubscriptionAccountType.CHATGPT && tokens.usesCodex(setting)
        return setting.copy(
            baseUrl = if (codex) "https://chatgpt.com/backend-api/codex" else account.baseUrl,
            subscriptionUsesCodex = codex,
            apiKey = tokens.accessToken(setting),
            apiKeyPool = emptyList(),
            useResponseApi = true,
            chatCompletionsPath = "/chat/completions",
            proxy = ProviderProxy.None,
        )
    }

    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> {
        if (providerSetting.subscriptionAccount == SubscriptionAccountType.GROK) return coroutineScope {
            val cli = async { runCatching { accountJson(providerSetting, "https://cli-chat-proxy.grok.com/v1/models-v2", true) } }
            val api = async { runCatching { accountJson(providerSetting, "https://api.x.ai/v1/models") } }
            val catalogs = listOf(cli.await(), api.await())
            if (catalogs.all { it.isFailure }) throw catalogs.first().exceptionOrNull()!!
            parseGrokSubscriptionModels(catalogs.mapNotNull { it.getOrNull() })
        }
        var setting = authenticated(providerSetting)
        suspend fun request() = http(setting).execute(PlatformHttpRequest(
            method = "GET", url = "${setting.baseUrl}/models" + if (setting.subscriptionUsesCodex) "?client_version=0.163.0" else "",
            headers = authHeaders(setting),
        ))
        var response = request()
        if (response.statusCode == 401) {
            tokens.refreshAccessToken(providerSetting)
            setting = authenticated(providerSetting)
            response = request()
        }
        check(response.statusCode in 200..299) { subscriptionHttpError(response.statusCode) }
        return parseSubscriptionModels(json.parseToJsonElement(response.body.decodeToString()).jsonObject,
            requireNotNull(setting.subscriptionAccount))
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = flow {
        var emitted = false
        try {
            val setting = authenticated(providerSetting)
            delegate(setting).streamText(setting, messages, requestParams(setting, params)).collect {
                emitted = true
                emit(it)
            }
        } catch (e: CancellationException) { throw e
        } catch (e: KeyRequestException) {
            if (e.statusCode == 401 && !emitted) {
                tokens.refreshAccessToken(providerSetting)
                try {
                    val setting = authenticated(providerSetting)
            delegate(setting).streamText(setting, messages, requestParams(setting, params)).collect { emit(it) }
                } catch (retry: KeyRequestException) {
                    error(subscriptionHttpError(retry.statusCode, retry.message))
                }
            } else error(subscriptionHttpError(e.statusCode, e.message))
        }
    }

    private suspend fun accountJson(providerSetting: ProviderSetting.OpenAI, url: String, cli: Boolean = false): JsonObject {
        var setting = authenticated(providerSetting)
        suspend fun request() = http(setting).execute(PlatformHttpRequest("GET", url, headers = authHeaders(setting) +
            if (cli) mapOf("X-XAI-Token-Auth" to "xai-grok-cli") else emptyMap()))
        var response = request()
        if (response.statusCode == 401) {
            tokens.refreshAccessToken(providerSetting)
            setting = authenticated(providerSetting)
            response = request()
        }
        check(response.statusCode in 200..299) { subscriptionHttpError(response.statusCode) }
        return json.parseToJsonElement(response.body.decodeToString()).jsonObject
    }

    override suspend fun getUsage(providerSetting: ProviderSetting.OpenAI): ProviderUsage? {
        if (providerSetting.subscriptionAccount == SubscriptionAccountType.GROK) {
            val credits = accountJson(providerSetting, "https://cli-chat-proxy.grok.com/v1/billing?format=credits", true)
            parseGrokSubscriptionUsage(credits)?.let { return it }
            return parseGrokSubscriptionUsage(credits, accountJson(providerSetting, "https://cli-chat-proxy.grok.com/v1/billing", true))
        }
        var setting = authenticated(providerSetting)
        if (!setting.subscriptionUsesCodex) return null
        suspend fun request() = http(setting).execute(PlatformHttpRequest("GET", "https://chatgpt.com/backend-api/wham/usage",
            headers = authHeaders(setting)))
        var response = request()
        if (response.statusCode == 401) {
            tokens.refreshAccessToken(providerSetting)
            setting = authenticated(providerSetting)
            response = request()
        }
        check(response.statusCode in 200..299) { subscriptionHttpError(response.statusCode) }
        return parseCodexUsage(response.body.decodeToString())
    }

    // ChatGPT plan requests must use SSE even for callers that request one final result.
    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk {
        var result = listOf(UIMessage.assistant(""))
        var usage: TokenUsage? = null
        var id = ""
        streamText(providerSetting, messages, params).collect { chunk ->
            result = result.handleMessageChunk(chunk, params.model)
            if (chunk.usage != null) usage = chunk.usage
            if (chunk.id.isNotBlank()) id = chunk.id
        }
        return MessageChunk(id, params.model.modelId,
            listOf(UIMessageChoice(0, null, result.last(), "stop")), usage)
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting, params: ImageGenerationParams,
    ): ImageGenerationResult = error("Image generation is not available through subscription sign-in.")
}

fun subscriptionHttpError(status: Int, detail: String? = null): String = when (status) {
    401 -> "Your subscription session has expired. Sign in again in provider settings."
    402, 403 -> "This account or plan does not allow access to this model. Check your subscription permissions."
    429 -> "Your subscription usage limit has been reached. Try again later."
    else -> detail?.takeIf { it.isNotBlank() }?.take(500)
        ?: "Subscription request failed (HTTP $status). Try again later."
}

internal fun parseSubscriptionModels(root: JsonObject, account: SubscriptionAccountType): List<Model> {
    val items = (root["models"] ?: root["data"]) as? JsonArray ?: error("The account returned no model catalog.")
    return items.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        fun text(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
        if (account == SubscriptionAccountType.CHATGPT && text("visibility") != "list") return@mapNotNull null
        val id = text("slug") ?: text("id") ?: return@mapNotNull null
        val input = (obj["input_modalities"] as? JsonArray)?.mapNotNull { modality ->
            when ((modality as? JsonPrimitive)?.contentOrNull?.lowercase()) {
                "text" -> Modality.TEXT
                "image" -> Modality.IMAGE
                else -> null
            }
        }?.ifEmpty { null } ?: listOf(Modality.TEXT)
        Model(modelId = id, displayName = text("display_name") ?: text("name") ?: id,
            type = ModelType.CHAT, inputModalities = input,
            contextWindowTokens = (obj["context_window"] as? JsonPrimitive)?.intOrNull,
            outputModalities = listOf(Modality.TEXT), abilities = buildList {
                add(ModelAbility.TOOL)
                if ((obj["supported_reasoning_levels"] as? JsonArray)?.isNotEmpty() == true) add(ModelAbility.REASONING)
            })
    }.distinctBy { it.modelId }
}
