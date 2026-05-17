/*
 * Copyright 2026 Nikolay Vlasov (https://github.com/nikkiw)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.nikkiw.taskbridge.okhttp

import io.github.nikkiw.taskbridge.transport.SseSession
import io.github.nikkiw.taskbridge.transport.SseSessionFactory
import io.github.nikkiw.taskbridge.transport.TaskBridgeSseListener
import io.github.nikkiw.taskbridge.transport.TaskBridgeWebSocketListener
import io.github.nikkiw.taskbridge.transport.TaskBridgeWebSocketSession
import io.github.nikkiw.taskbridge.transport.WebSocketSessionFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/**
 * [WebSocketSessionFactory] implementation using OkHttp.
 */
class OkHttpWebSocketSessionFactory<Ctx>(
    private val okHttpClient: OkHttpClient,
) : WebSocketSessionFactory<Ctx> {
    override suspend fun open(
        context: Ctx,
        url: String,
        listener: TaskBridgeWebSocketListener,
    ): TaskBridgeWebSocketSession {
        val request =
            Request
                .Builder()
                .url(url)
                .tag(TaskBridgeContextTag::class.java, TaskBridgeContextTag(context))
                .build()
        lateinit var session: TaskBridgeWebSocketSession
        val socket =
            okHttpClient.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        listener.onOpen(session)
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        listener.onMessage(text)
                    }

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        listener.onClosing(code, reason)
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: okhttp3.Response?,
                    ) {
                        listener.onFailure(t)
                    }
                },
            )
        session = OkHttpTaskBridgeWebSocketSession(socket)
        return session
    }
}

/**
 * [SseSessionFactory] implementation using OkHttp SSE.
 */
class OkHttpSseSessionFactory<Ctx>(
    okHttpClient: OkHttpClient,
) : SseSessionFactory<Ctx> {
    private val sseHttpClient =
        okHttpClient
            .newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()

    private val eventSourceFactory = EventSources.createFactory(sseHttpClient)

    override suspend fun open(
        context: Ctx,
        url: String,
        lastEventId: String?,
        listener: TaskBridgeSseListener,
    ): SseSession {
        val requestBuilder =
            Request
                .Builder()
                .url(url)
                .header("Accept", "text/event-stream")
                .tag(TaskBridgeContextTag::class.java, TaskBridgeContextTag(context))
        if (lastEventId != null) {
            requestBuilder.header("Last-Event-ID", lastEventId)
        }
        val eventSource =
            eventSourceFactory.newEventSource(
                requestBuilder.build(),
                object : EventSourceListener() {
                    override fun onOpen(
                        eventSource: EventSource,
                        response: okhttp3.Response,
                    ) {
                        listener.onOpen()
                    }

                    override fun onEvent(
                        eventSource: EventSource,
                        id: String?,
                        type: String?,
                        data: String,
                    ) {
                        listener.onEvent(id, type, data)
                    }

                    override fun onFailure(
                        eventSource: EventSource,
                        t: Throwable?,
                        response: okhttp3.Response?,
                    ) {
                        listener.onFailure(t ?: IllegalStateException("SSE connection failure"))
                    }

                    override fun onClosed(eventSource: EventSource) {
                        listener.onClosed()
                    }
                },
            )
        return EventSourceSession(eventSource)
    }

    private class EventSourceSession(
        private val eventSource: EventSource,
    ) : SseSession {
        override fun cancel() {
            eventSource.cancel()
        }
    }
}

private class OkHttpTaskBridgeWebSocketSession(
    private val webSocket: WebSocket,
) : TaskBridgeWebSocketSession {
    override fun send(text: String): Boolean = webSocket.send(text)

    override fun close(
        code: Int,
        reason: String?,
    ): Boolean = webSocket.close(code, reason)

    override fun cancel() {
        webSocket.cancel()
    }
}
