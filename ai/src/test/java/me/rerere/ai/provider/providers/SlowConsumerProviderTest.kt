package me.rerere.ai.provider.providers

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.opencode.OpenCodeProvider
import me.rerere.ai.ui.*
import me.rerere.common.platform.*
import org.junit.Assert.assertEquals
import org.junit.Test

class SlowConsumerProviderTest {
    private val media = object : PlatformMediaEncoder {
        override fun encodeImage(source: String, withPrefix: Boolean) = Result.success(source)
        override fun encodeVideo(source: String, withPrefix: Boolean) = Result.success(source)
        override fun encodeAudio(source: String, withPrefix: Boolean) = Result.success(source)
    }
    private val signer = object : PlatformJwtSigner {
        override fun signRs256(data: ByteArray, pkcs8PrivateKeyPem: String): ByteArray = error("Unused")
    }

    @Test fun everyWireFormatDeliversTheCompleteReplyToASlowConsumer() = runBlocking {
        val client = object : PlatformHttpClient {
            override suspend fun execute(request: PlatformHttpRequest): PlatformHttpResponse = error("Must stream")
            override fun streamEvents(request: PlatformHttpRequest) = flow {
                emit(PlatformServerEvent.Open(200))
                val claude = request.url.contains("/messages")
                val response = request.url.endsWith("/responses")
                val google = request.url.contains("streamGenerateContent")
                if (claude) emit(PlatformServerEvent.Event(event = "message_start", data =
                    """{"type":"message_start","message":{"id":"r","model":"test","usage":{"input_tokens":1}}}"""))
                for (i in 0 until 150) {
                    val token = (i % 10).toString()
                    emit(when {
                        response -> PlatformServerEvent.Event(data =
                            """{"type":"response.output_text.delta","item_id":"r","delta":"$token"}""")
                        claude -> PlatformServerEvent.Event(event = "content_block_delta", data =
                            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$token"}}""")
                        google -> PlatformServerEvent.Event(data =
                            """{"candidates":[{"content":{"role":"model","parts":[{"text":"$token"}]}}]}""")
                        else -> PlatformServerEvent.Event(data =
                            """{"id":"r","model":"test","choices":[{"index":0,"delta":{"content":"$token"}}]}""")
                    })
                }
                if (response) emit(PlatformServerEvent.Event(data =
                    """{"type":"response.completed","response":{"id":"r","output":[]}}"""))
                else if (claude) emit(PlatformServerEvent.Event(event = "message_stop", data = """{"type":"message_stop"}"""))
                else if (!google) emit(PlatformServerEvent.Event(data = "[DONE]"))
                emit(PlatformServerEvent.Closed)
            }
        }
        val provider = OpenCodeProvider(client, media, signer)
        val setting = ProviderSetting.OpenAI(baseUrl = "https://opencode.ai/inference/go/openai/v1", apiKey = "test-key")
        for (modelId in listOf("deepseek-v4.1-flash", "gpt-6-luna", "claude-haiku-5-5", "gemini-4-argon-contributor")) {
            val model = Model(modelId = modelId)
            var messages = listOf(UIMessage.assistant(""))
            withTimeout(10_000) {
                provider.streamText(setting, listOf(UIMessage.user("Hello")), TextGenerationParams(model)).collect {
                    delay(2)
                    messages = messages.handleMessageChunk(it, model)
                }
            }
            assertEquals(modelId, "0123456789".repeat(15), messages.last().toText())
        }
    }
}
