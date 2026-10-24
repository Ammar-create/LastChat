package me.rerere.common.platform.android

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.common.platform.PlatformHttpRequest
import me.rerere.common.platform.PlatformServerEvent
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.*
import org.junit.Test

class CodexEventStreamTest {
    private fun client(body: String, type: String? = null, status: Int = 200) = OkHttpPlatformHttpClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_2).code(status).message("test")
                .apply { if (type != null) header("Content-Type", type) }
                .body(body.toResponseBody(type?.toMediaType())).build()
        }.build())

    private val request = PlatformHttpRequest("POST", "https://chatgpt.com/backend-api/codex/responses",
        body = "{}".encodeToByteArray(), mediaType = "application/json")

    @Test fun codexWithoutContentTypeParsesEveryEventForASlowConsumer() = runBlocking {
        val body = (0..149).joinToString("") { "event: response.output_text.delta\ndata: {\"delta\":\"$it\"}\n\n" }
        val events = withTimeout(10_000) { client(body).streamEvents(request).onEach { delay(1) }.toList() }
        assertTrue(events.toString(), events.first() is PlatformServerEvent.Open)
        assertEquals(150, events.filterIsInstance<PlatformServerEvent.Event>().size)
        assertEquals("{\"delta\":\"149\"}", events.filterIsInstance<PlatformServerEvent.Event>().last().data)
        assertEquals(PlatformServerEvent.Closed, events.last())
        assertTrue(events.none { it is PlatformServerEvent.Failure })
    }

    @Test fun explicitJsonErrorIsNotRelabeledAsAnEventStreamAndBodySurvivesClose() = runBlocking {
        val body = """{"error":{"message":"This model is not available for your plan"}}"""
        val events = withTimeout(5_000) { client(body, "application/json").streamEvents(request).toList() }
        val failure = events.filterIsInstance<PlatformServerEvent.Failure>().single()
        assertEquals(200, failure.statusCode)
        assertEquals(body, failure.body)
    }

    @Test fun authenticationFailureRetainsItsBodyForSessionRefresh() = runBlocking {
        val events = withTimeout(5_000) { client("expired session", "text/plain", 401).streamEvents(request).toList() }
        val failure = events.filterIsInstance<PlatformServerEvent.Failure>().single()
        assertEquals(401, failure.statusCode)
        assertEquals("expired session", failure.body)
    }

    @Test fun missingMimeExceptionIsScopedToTheCodexEndpoint() = runBlocking {
        val events = withTimeout(5_000) { client("data: {}\n\n").streamEvents(request.copy(
            url = "https://example.com/responses")).toList() }
        assertTrue(events.any { it is PlatformServerEvent.Failure })
        assertTrue(events.none { it is PlatformServerEvent.Event })
    }
}
