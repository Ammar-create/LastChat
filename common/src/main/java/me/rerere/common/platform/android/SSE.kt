package me.rerere.common.platform.android

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

sealed class SseEvent {
    data class Open(
        val statusCode: Int,
        val headers: Map<String, List<String>>
    ) : SseEvent()

    data class Event(
        val id: String?,
        val type: String?,
        val data: String
    ) : SseEvent()

    data object Closed : SseEvent()

    data class Failure(
        val throwable: Throwable?,
        val response: Response?,
        val body: String? = null,
    ) : SseEvent()
}

fun OkHttpClient.sseFlow(request: Request): Flow<SseEvent> {
    return callbackFlow {
        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                trySendBlocking(SseEvent.Open(response.code, response.headers.toMultimap()))
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                // Apply backpressure on the network dispatcher instead of dropping fast deltas.
                trySendBlocking(SseEvent.Event(id, type, data))
            }

            override fun onClosed(eventSource: EventSource) {
                trySendBlocking(SseEvent.Closed)
                channel.close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                // OkHttp closes the response after this callback. Capture its detail here.
                val body = runCatching { response?.peekBody(65_536L)?.string() }.getOrNull()
                trySendBlocking(SseEvent.Failure(t, response, body))
                channel.close()
            }
        }

        // Codex's Responses endpoint can omit Content-Type on a valid SSE response.
        // Keep strict MIME validation for every other endpoint and explicit MIME type.
        val streamClient = if (request.url.host == "chatgpt.com" &&
            request.url.encodedPath == "/backend-api/codex/responses") {
            this@sseFlow.newBuilder().apply { interceptors().add(0, Interceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.isSuccessful && response.header("Content-Type").isNullOrBlank()) {
                    val original = response.body
                    response.newBuilder().header("Content-Type", "text/event-stream").body(object : ResponseBody() {
                        override fun contentType() = "text/event-stream".toMediaType()
                        override fun contentLength() = original.contentLength()
                        override fun source() = original.source()
                    }).build()
                } else response
            }) }.build()
        } else this@sseFlow
        val eventSource = EventSources.createFactory(streamClient)
            .newEventSource(request, listener)

        awaitClose {
            eventSource.cancel()
        }
    }
}
