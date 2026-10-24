package me.rerere.ai.provider.providers

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.openai.chatGptSubscriptionBody
import me.rerere.ai.ui.*
import me.rerere.ai.util.json
import me.rerere.common.platform.*
import org.junit.Assert.*
import org.junit.Test

class SubscriptionProviderTest {
    @Test fun requestValidationErrorsKeepTheProvidersActionableDetail() {
        assertEquals("HTTP 400: Unsupported reasoning effort", subscriptionHttpError(400, "HTTP 400: Unsupported reasoning effort"))
        assertTrue(subscriptionHttpError(401, "expired").contains("Sign in again"))
    }
    private val media = object : PlatformMediaEncoder {
        override fun encodeImage(url: String, withPrefix: Boolean) = Result.success(url)
        override fun encodeVideo(url: String, withPrefix: Boolean) = Result.success(url)
        override fun encodeAudio(url: String, withPrefix: Boolean) = Result.success(url)
    }

    @Test fun successfulHttpStatusKeepsTheActualStreamFailureDetail() = runBlocking {
        val http = object : PlatformHttpClient {
            override suspend fun execute(request: PlatformHttpRequest): PlatformHttpResponse = error("Must stream")
            override fun streamEvents(request: PlatformHttpRequest) = flow {
                emit(PlatformServerEvent.Open(200))
                emit(PlatformServerEvent.Failure(statusCode = 200, message = "Connection reset by peer"))
            }
        }
        val provider = SubscriptionProvider(http, media, SubscriptionTokenSource { "test-session" })
        val failure = runCatching { provider.generateText(ProviderSetting.OpenAI(subscriptionAccount = SubscriptionAccountType.CHATGPT),
            listOf(UIMessage.user("Hello")), TextGenerationParams(Model(modelId = "test"))) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure?.message.orEmpty().contains("Connection reset by peer"))
        assertFalse(failure?.message.orEmpty().contains("Subscription request failed"))
    }

    @Test fun completedResponseIsNotDiscardedWhenTheServerClosesItsConnection() = runBlocking {
        val http = object : PlatformHttpClient {
            override suspend fun execute(request: PlatformHttpRequest): PlatformHttpResponse = error("Must stream")
            override fun streamEvents(request: PlatformHttpRequest) = flow {
                emit(PlatformServerEvent.Event(data = """{"type":"response.output_text.delta","item_id":"one","delta":"Hello"}"""))
                emit(PlatformServerEvent.Event(data = """{"type":"response.completed","response":{"id":"one","output":[]}}"""))
                emit(PlatformServerEvent.Failure(statusCode = 200, message = "Connection closed"))
            }
        }
        val result = SubscriptionProvider(http, media, SubscriptionTokenSource { "test-session" }).generateText(
            ProviderSetting.OpenAI(subscriptionAccount = SubscriptionAccountType.CHATGPT), listOf(UIMessage.user("Hello")),
            TextGenerationParams(Model(modelId = "test")))
        assertEquals("Hello", result.choices.first().message?.parts?.filterIsInstance<UIMessagePart.Text>()?.joinToString("") { it.text })
    }

    @Test fun chatGptCatalogUsesAccountSlugsAndVisibilityInServerOrder() {
        val models = parseSubscriptionModels(json.parseToJsonElement("""{"models":[
            {"slug":"private","visibility":"hide"},
            {"slug":"account-model","display_name":"My model","visibility":"list"},
            {"slug":"second","visibility":"list"}]}""").jsonObject, SubscriptionAccountType.CHATGPT)
        assertEquals(listOf("account-model", "second"), models.map { it.modelId })
        assertEquals("My model", models.first().displayName)
    }

    @Test fun grokCatalogAcceptsOpenAiDataShapeAndDeduplicates() {
        val models = parseSubscriptionModels(json.parseToJsonElement("""{"data":[{"id":"grok-test"},{"id":"grok-test"}]}""").jsonObject,
            SubscriptionAccountType.GROK)
        assertEquals(listOf("grok-test"), models.map { it.modelId })
    }

    @Test fun customBodiesCannotTurnOnStorageOrDisableStreamingAndToolsAreNamespaced() {
        val result = chatGptSubscriptionBody(json.parseToJsonElement("""{
            "model":"test","input":[{"type":"function_call","name":"test","call_id":"c","arguments":"{}"},{"type":"function_call_output","call_id":"c","output":"ok"}],"stream":false,"store":true,"temperature":0.5,
            "max_output_tokens":100,"previous_response_id":"old","tools":[{"type":"function","name":"test"}]}
            """).jsonObject)
        assertEquals(JsonPrimitive(false), result["store"])
        assertEquals(JsonPrimitive(true), result["stream"])
        assertTrue(result["input"]?.jsonArray?.all { it.jsonObject["namespace"] == JsonPrimitive("lastchat") } == true)
        assertFalse(result.containsKey("temperature"))
        assertFalse(result.containsKey("max_output_tokens"))
        assertFalse(result.containsKey("previous_response_id"))
        assertEquals("namespace", result["tools"]?.jsonArray?.first()?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test fun finalResultUsesStreamingAndCredentialsAreResolvedForEveryRequest() = runBlocking {
        var resolutions = 0
        val requests = mutableListOf<PlatformHttpRequest>()
        val http = object : PlatformHttpClient {
            override suspend fun execute(request: PlatformHttpRequest): PlatformHttpResponse = error("Must stream")
            override fun streamEvents(request: PlatformHttpRequest): Flow<PlatformServerEvent> = flow {
                requests += request
                emit(PlatformServerEvent.Open(200))
                emit(PlatformServerEvent.Event(data = """{"type":"response.output_text.delta","item_id":"one","delta":"Hello"}"""))
                emit(PlatformServerEvent.Event(data = """{"type":"response.output_text.delta","item_id":"one","delta":" world"}"""))
                emit(PlatformServerEvent.Event(data = """{"type":"response.completed","response":{"id":"one","model":"test","output":[]}}"""))
                emit(PlatformServerEvent.Closed)
            }
        }
        val provider = SubscriptionProvider(http, media, SubscriptionTokenSource { "session-${++resolutions}" })
        val setting = ProviderSetting.OpenAI(baseUrl = "https://untrusted.example", apiKey = "old-api-key",
            subscriptionAccount = SubscriptionAccountType.CHATGPT)
        val result = provider.generateText(setting, listOf(UIMessage.user("Hi")), TextGenerationParams(Model(modelId = "test")))
        assertEquals("Hello world", result.choices.first().message?.parts?.filterIsInstance<UIMessagePart.Text>()?.joinToString("") { it.text })
        provider.generateText(setting, listOf(UIMessage.user("Again")), TextGenerationParams(Model(modelId = "test")))
        assertEquals(2, resolutions)
        assertEquals("https://api.openai.com/v1/responses", requests.first().url)
        assertEquals("Bearer session-2", requests.last().headers["Authorization"])
    }

    @Test fun unauthorizedRequestRefreshesOnceBeforeAnyOutputAndNeverUsesApiKeyFallback() = runBlocking {
        var calls = 0
        var refreshes = 0
        var activeToken = "expired"
        val headers = mutableListOf<String?>()
        val http = object : PlatformHttpClient {
            override suspend fun execute(request: PlatformHttpRequest) = error("Must stream")
            override fun streamEvents(request: PlatformHttpRequest): Flow<PlatformServerEvent> = flow {
                headers += request.headers["Authorization"]
                if (++calls == 1) emit(PlatformServerEvent.Failure(statusCode = 401))
                else {
                    emit(PlatformServerEvent.Event(data = """{"type":"response.output_text.delta","delta":"ok"}"""))
                    emit(PlatformServerEvent.Event(data = """{"type":"response.completed","response":{"usage":{}}}"""))
                    emit(PlatformServerEvent.Closed)
                }
            }
        }
        val tokens = object : SubscriptionTokenSource {
            override suspend fun accessToken(setting: ProviderSetting.OpenAI) = activeToken
            override suspend fun refreshAccessToken(setting: ProviderSetting.OpenAI): String {
                refreshes++
                activeToken = "refreshed"
                return activeToken
            }
        }
        val provider = SubscriptionProvider(http, media, tokens)
        provider.generateText(ProviderSetting.OpenAI(apiKey = "paid-api-key", subscriptionAccount = SubscriptionAccountType.CHATGPT),
            listOf(UIMessage.user("Hi")), TextGenerationParams(Model(modelId = "test")))
        assertEquals(1, refreshes)
        assertEquals(listOf("Bearer expired", "Bearer refreshed"), headers)
    }

    @Test fun failedOrTruncatedSubscriptionStreamsAreNotReportedAsSuccess() = runBlocking {
        for (terminal in listOf<String?>(null, "response.failed", "response.incomplete")) {
            val http = object : PlatformHttpClient {
                override suspend fun execute(request: PlatformHttpRequest) = error("Must stream")
                override fun streamEvents(request: PlatformHttpRequest): Flow<PlatformServerEvent> = flow {
                    emit(PlatformServerEvent.Open(200))
                    emit(PlatformServerEvent.Event(data = """{"type":"response.output_text.delta","delta":"partial"}"""))
                    if (terminal != null) emit(PlatformServerEvent.Event(data = """{"type":"$terminal","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}}"""))
                    emit(PlatformServerEvent.Closed)
                }
            }
            val provider = SubscriptionProvider(http, media, SubscriptionTokenSource { "test" })
            val result = runCatching { provider.generateText(ProviderSetting.OpenAI(subscriptionAccount = SubscriptionAccountType.CHATGPT),
                listOf(UIMessage.user("Hi")), TextGenerationParams(Model(modelId = "test"))) }
            assertTrue("$terminal must fail", result.isFailure)
        }
    }
    @Test fun codexBodyUsesDeveloperContextFlatToolsAndEncryptedReasoningReplay() {
        val body = me.rerere.ai.provider.providers.openai.codexSubscriptionBody(json.parseToJsonElement("""{
            "instructions":"Be helpful","input":[{"role":"user","content":"Hi"}],
            "tools":[{"type":"function","name":"lookup","parameters":{"type":"object"}}],
            "temperature":0.7,"top_p":0.9,"max_output_tokens":200,"stream":false,"store":true
        }""").jsonObject)
        assertEquals("developer", body["input"]!!.jsonArray.first().jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("Be helpful", body["input"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("instructions"))
        assertEquals("function", body["tools"]!!.jsonArray.first().jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(body["include"]!!.jsonArray.contains(JsonPrimitive("reasoning.encrypted_content")))
        assertEquals(JsonPrimitive(false), body["store"])
        assertEquals(JsonPrimitive(true), body["stream"])
        assertFalse(body.containsKey("temperature")); assertFalse(body.containsKey("max_output_tokens"))
    }

    @Test fun codexUsesAccountHeadersVersionedModelsQuotaAndTheSelectedNetworkClient() = runBlocking {
        val requests = mutableListOf<PlatformHttpRequest>()
        val primary = object : PlatformHttpClient {
            override suspend fun execute(request: PlatformHttpRequest): PlatformHttpResponse = error("Must use opted-in transport")
            override fun streamEvents(request: PlatformHttpRequest): Flow<PlatformServerEvent> = error("Must use opted-in transport")
        }
        val fallback = object : PlatformHttpClient {
            override suspend fun execute(request: PlatformHttpRequest): PlatformHttpResponse {
                requests += request
                return PlatformHttpResponse(200, body = if (request.url.contains("/wham/usage"))
                    """{"rate_limit":{"primary_window":{"used_percent":40,"limit_window_seconds":18000,"reset_at":1800000000}}}""".encodeToByteArray()
                else """{"models":[{"slug":"account-model","visibility":"list","input_modalities":["text","image"],"supported_reasoning_levels":["high"],"context_window":1050000}]}""".encodeToByteArray())
            }
            override fun streamEvents(request: PlatformHttpRequest): Flow<PlatformServerEvent> = flow {
                requests += request
                emit(PlatformServerEvent.Event(data = """{"type":"response.output_item.done","item":{"id":"reasoning-1","type":"reasoning","summary":[],"encrypted_content":"opaque-ciphertext"}}"""))
                emit(PlatformServerEvent.Event(data = """{"type":"response.output_text.delta","delta":"OK"}"""))
                emit(PlatformServerEvent.Event(data = """{"type":"response.completed","response":{"id":"r","output":[]}}"""))
                emit(PlatformServerEvent.Closed)
            }
        }
        val tokens = object : SubscriptionTokenSource {
            override suspend fun accessToken(setting: ProviderSetting.OpenAI) = "account-token"
            override suspend fun usesCodex(setting: ProviderSetting.OpenAI) = true
            override suspend fun accountId(setting: ProviderSetting.OpenAI) = "account-123"
        }
        val provider = SubscriptionProvider(primary, media, tokens, fallback)
        val setting = ProviderSetting.OpenAI(subscriptionAccount = SubscriptionAccountType.CHATGPT, subscriptionDnsFallback = true)
        val model = provider.listModels(setting).single()
        assertTrue(requests.last().url.contains("/backend-api/codex/models?client_version="))
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), model.inputModalities)
        assertTrue(model.abilities.contains(ModelAbility.REASONING))
        assertEquals(40.0, provider.getUsage(setting)!!.windows.single().usedPercent, 0.0)
        val first = provider.generateText(setting, listOf(UIMessage.user("Hi")), TextGenerationParams(model, sessionId = "session-123"))
        assertEquals("account-123", requests.last().headers["ChatGPT-Account-ID"])
        assertEquals("session-123", requests.last().headers["session-id"])
        assertEquals("codex_cli_rs", requests.last().headers["originator"])
        val history = listOf(UIMessage.user("Hi"), first.choices.single().message!!, UIMessage.user("Continue"))
        provider.generateText(setting, history, TextGenerationParams(model))
        val input = json.parseToJsonElement(requests.last().body!!.decodeToString()).jsonObject["input"]!!.jsonArray
        assertTrue(input.any { it.jsonObject["encrypted_content"] == JsonPrimitive("opaque-ciphertext") })
    }

}
