package me.rerere.ai.provider.providers.opencode

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import me.rerere.ai.util.providerHttpError
import me.rerere.common.platform.*
import org.junit.Assert.*
import org.junit.Test

class OpenCodeProviderTest {
    private class Client : PlatformHttpClient {
        lateinit var last: PlatformHttpRequest
        var usageBody = """{"usage":{"rolling":{"percent":32,"resetsAt":"2026-10-20T00:00:00Z","status":"ok"},"weekly":{"percent":100,"status":"rate-limited"},"monthly":{"percent":51}}}"""
        override suspend fun execute(request: PlatformHttpRequest): PlatformHttpResponse {
            last = request
            return PlatformHttpResponse(200, body = usageBody.encodeToByteArray())
        }
        override fun streamEvents(request: PlatformHttpRequest) = flow {
            last = request
            emit(PlatformServerEvent.Open(200))
            when {
                request.url.contains("/responses") -> {
                    emit(PlatformServerEvent.Event(data = """{"type":"response.output_text.delta","delta":"OK"}"""))
                    emit(PlatformServerEvent.Event(data = """{"type":"response.completed","response":{"id":"r","model":"test","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"OK"}]}]}}"""))
                }
                request.url.contains("/messages") -> {
                    emit(PlatformServerEvent.Event(event = "message_start", data = """{"type":"message_start","message":{"id":"r","model":"test","usage":{"input_tokens":1}}}"""))
                    emit(PlatformServerEvent.Event(event = "content_block_delta", data = """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"OK"}}"""))
                    emit(PlatformServerEvent.Event(event = "message_stop", data = """{"type":"message_stop"}"""))
                }
                request.url.contains("streamGenerateContent") -> emit(PlatformServerEvent.Event(data = """{"candidates":[{"content":{"role":"model","parts":[{"text":"OK"}]},"finishReason":"STOP"}]}"""))
                else -> {
                    emit(PlatformServerEvent.Event(data = """{"id":"r","model":"test","choices":[{"index":0,"delta":{"content":"OK"}}]}"""))
                    emit(PlatformServerEvent.Event(data = "[DONE]"))
                }
            }
            emit(PlatformServerEvent.Closed)
        }
    }
    private val media = object : PlatformMediaEncoder {
        override fun encodeImage(source: String, withPrefix: Boolean) = Result.success(source)
        override fun encodeVideo(source: String, withPrefix: Boolean) = Result.success(source)
        override fun encodeAudio(source: String, withPrefix: Boolean) = Result.success(source)
    }
    private val signer = object : PlatformJwtSigner {
        override fun signRs256(data: ByteArray, pkcs8PrivateKeyPem: String): ByteArray = error("No Vertex signing for OpenCode")
    }

    @Test fun routesAllApiFamiliesAndGoExceptionsWithSessionAndBearerAuth() = runBlocking {
        val client = Client()
        val provider = OpenCodeProvider(client, media, signer)
        val setting = ProviderSetting.OpenAI(baseUrl = "https://opencode.ai/zen/go/v1", apiKey = "test-key")
        for ((model, path) in listOf("deepseek-v4.1-flash" to "/openai/v1/chat/completions",
            "gpt-6-luna" to "/openai/v1/responses", "grok-4.7" to "/openai/v1/responses",
            "claude-haiku-5-5" to "/anthropic/v1/messages", "qwen3.8-max" to "/anthropic/v1/messages",
            "minimax-m3" to "/anthropic/v1/messages", "gemini-4-argon-contributor" to "/google/v1beta/models/gemini-4-argon-contributor:streamGenerateContent?alt=sse")) {
            val result = provider.generateText(setting, listOf(UIMessage.user("Hi")), TextGenerationParams(Model(modelId = model, abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING)),
                thinkingBudget = 64000, temperature = 0.7f, topP = 0.9f, sessionId = "conversation-123"))
            assertTrue("model=$model, result=$result", result.choices.single().message?.toText()?.contains("OK") == true)
            assertEquals("https://opencode.ai/inference/go$path", client.last.url)
            assertEquals("Bearer test-key", client.last.headers["Authorization"])
            assertEquals("conversation-123", client.last.headers["x-opencode-session"])
            if (model == "claude-haiku-5-5") {
                val body = me.rerere.ai.util.json.parseToJsonElement(client.last.body!!.decodeToString()).jsonObject
                assertEquals("adaptive", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
                assertFalse(body["thinking"]!!.jsonObject.containsKey("budget_tokens"))
                assertFalse(body.containsKey("top_p")); assertFalse(body.containsKey("temperature"))
            }
            if (model == "grok-4.7") {
                val body = me.rerere.ai.util.json.parseToJsonElement(client.last.body!!.decodeToString()).jsonObject
                assertEquals("high", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
                assertFalse(body["reasoning"]!!.jsonObject.containsKey("summary"))
            }
        }
        val zen = requireNotNull(OpenCodeEndpoint.from("https://opencode.ai/zen/v1"))
        assertEquals(OpenCodeEndpoint.Api.CHAT, zen.api("qwen3.8-max"))
        assertEquals(OpenCodeEndpoint.Api.CHAT, zen.api("minimax-m3"))
        assertNull(OpenCodeEndpoint.from("https://opencode.ai.evil.example/zen/v1"))
        assertNull(OpenCodeEndpoint.from("http://opencode.ai/zen/v1"))
    }

    @Test fun readsServerUsageWithoutConfusingAnUnknownSchemaWithZeroUsage() = runBlocking {
        val client = Client()
        val provider = OpenCodeProvider(client, media, signer)
        val usage = provider.getUsage(ProviderSetting.OpenAI(baseUrl = "https://opencode.ai/zen/go/v1", apiKey = "key"))!!
        assertEquals("https://opencode.ai/inference/go/v1/usage", client.last.url)
        assertEquals(listOf(32.0, 100.0, 51.0), usage.windows.map { it.usedPercent })
        assertTrue(usage.limitReached)
        assertNotNull(usage.windows.first().resetsAt)
        client.usageBody = "{}"
        assertTrue(runCatching { provider.getUsage(ProviderSetting.OpenAI(baseUrl = "https://opencode.ai/zen/go/v1", apiKey = "key")) }.isFailure)
    }

    @Test fun parsesJsonSsePlainAndEmptyErrorsWithoutLeakingParserFailures() {
        assertTrue(providerHttpError(429, "event: error\ndata: {\"error\":{\"message\":\"Window resets in 2h\"}}\n\n").message!!.contains("Window resets in 2h"))
        assertTrue(providerHttpError(400, "event: error\ndata: {}\n\n").message!!.contains("provider rejected"))
        assertTrue(providerHttpError(403, """{"type":"error","error":{"message":"This model needs Go Plus"}}""").message!!.contains("Go Plus"))
        assertTrue(providerHttpError(503, "<html>bad gateway</html>").message!!.contains("temporarily unavailable"))
        assertTrue(providerHttpError(401, "").message!!.contains("Authentication failed"))
    }
    @Test fun claudeCompatibilityPreservesHistoryAndReservesOutputBelowLegacyThinkingBudgets() {
        val modern = adaptOpenCodeClaudeBody(me.rerere.ai.util.json.parseToJsonElement("""{
            "model":"claude-opus-5-5","messages":[{"role":"assistant","content":"Partial answer"}],
            "temperature":0.7,"top_p":0.9,"thinking":{"type":"enabled","budget_tokens":16000},"max_tokens":32000
        }""").jsonObject)
        assertEquals("adaptive", modern["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("medium", modern["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("assistant", modern["messages"]!!.jsonArray.first().jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("user", modern["messages"]!!.jsonArray.last().jsonObject["role"]!!.jsonPrimitive.content)
        val legacy = adaptOpenCodeClaudeBody(me.rerere.ai.util.json.parseToJsonElement("""{
            "model":"claude-sonnet-4-5","max_tokens":64000,"thinking":{"type":"enabled","budget_tokens":64000}
        }""").jsonObject)
        assertEquals(62976, legacy["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.int)
    }

}
