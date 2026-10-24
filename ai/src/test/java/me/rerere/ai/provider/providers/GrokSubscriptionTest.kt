package me.rerere.ai.provider.providers

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.openai.grokSubscriptionBody
import me.rerere.ai.ui.*
import me.rerere.ai.util.json
import me.rerere.common.platform.*
import org.junit.Assert.*
import org.junit.Test

class GrokSubscriptionTest {
    @Test fun catalogMergesCliOverridesAndRejectsHiddenAndNonChatModels() {
        val cli = json.parseToJsonElement("""{"data":[
            {"id":"alias","model":"grok-test","name":"Account model","reasoningEfforts":["high"],"contextWindow":256000},
            {"id":"hidden","hidden":true},{"id":"grok-imagine"}]}""").jsonObject
        val api = json.parseToJsonElement("""{"data":[{"id":"grok-test","name":"Duplicate"},{"id":"grok-other"}]}""").jsonObject
        val models = parseGrokSubscriptionModels(listOf(cli, api))
        assertEquals(listOf("grok-test", "grok-other"), models.map { it.modelId })
        assertEquals("Account model", models.first().displayName)
        assertTrue(models.first().abilities.contains(ModelAbility.REASONING))
        assertEquals(256000, models.first().contextWindowTokens)
    }
    @Test fun handlesWeeklyAndUnifiedMonthlyQuotaWithoutInventingUnknownUsage() {
        val weekly = json.parseToJsonElement("""{"config":{"currentPeriod":{"start":"2026-10-12T00:00:00Z","end":"2026-10-19T00:00:00Z","type":"WEEK"},"creditUsagePercent":45}}""").jsonObject
        assertEquals(45.0, parseGrokSubscriptionUsage(weekly)!!.windows.single().usedPercent, 0.0)
        val billing = json.parseToJsonElement("""{"config":{"billingPeriodStart":"2026-10-01T00:00:00Z","billingPeriodEnd":"2026-11-01T00:00:00Z","monthlyLimit":{"val":"20"},"used":{"val":"25"}}}""").jsonObject
        assertTrue(parseGrokSubscriptionUsage(buildJsonObject { }, billing)!!.limitReached)
        assertNull(parseGrokSubscriptionUsage(buildJsonObject { }))
    }
    @Test fun grokResponsesUsesSystemContextAndValidEffortValues() {
        val body = json.parseToJsonElement("""{"instructions":"Help","input":[],"reasoning":{"summary":"auto","effort":"max"}}""").jsonObject
        val grok46 = grokSubscriptionBody(body, "grok-4.6", 64000)
        assertEquals("system", grok46["input"]!!.jsonArray.first().jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("xhigh", grok46["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertFalse(grok46["reasoning"]!!.jsonObject.containsKey("summary"))
        assertEquals("high", grokSubscriptionBody(body, "grok-4.7", 64000)["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertFalse(grokSubscriptionBody(body, "grok-4.20-reasoning", 0).containsKey("reasoning"))
    }
    @Test fun generationUsesPublicResponsesAndAccountCatalogUsesCliChannel() = runBlocking {
        val requests = mutableListOf<PlatformHttpRequest>()
        val http = object : PlatformHttpClient {
            override suspend fun execute(request: PlatformHttpRequest): PlatformHttpResponse {
                requests += request
                return PlatformHttpResponse(200, body = """{"data":[{"id":"grok-test"}]}""".encodeToByteArray())
            }
            override fun streamEvents(request: PlatformHttpRequest): Flow<PlatformServerEvent> = flow {
                requests += request
                emit(PlatformServerEvent.Event(data = """{"type":"response.output_text.delta","delta":"OK"}"""))
                emit(PlatformServerEvent.Event(data = """{"type":"response.completed","response":{"id":"r","output":[]}}"""))
                emit(PlatformServerEvent.Closed)
            }
        }
        val media = object : PlatformMediaEncoder {
            override fun encodeImage(url: String, withPrefix: Boolean) = Result.success(url)
            override fun encodeVideo(url: String, withPrefix: Boolean) = Result.success(url)
            override fun encodeAudio(url: String, withPrefix: Boolean) = Result.success(url)
        }
        val provider = SubscriptionProvider(http, media, SubscriptionTokenSource { "oauth-token" })
        val setting = ProviderSetting.OpenAI(subscriptionAccount = SubscriptionAccountType.GROK)
        assertEquals("grok-test", provider.listModels(setting).single().modelId)
        assertEquals("xai-grok-cli", requests.first { it.url.contains("models-v2") }.headers["X-XAI-Token-Auth"])
        provider.generateText(setting, listOf(UIMessage.user("Hi")), TextGenerationParams(Model(modelId = "grok-test"), sessionId = "grok-session"))
        assertEquals("https://api.x.ai/v1/responses", requests.last().url)
        assertEquals("grok-session", requests.last().headers["x-grok-conv-id"])
        assertEquals("Bearer oauth-token", requests.last().headers["Authorization"])
    }
}
