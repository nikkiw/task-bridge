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
package io.github.nikkiw.taskbridge

import io.github.nikkiw.taskbridge.api.DefaultTaskBridgeRouteResolver
import io.github.nikkiw.taskbridge.api.TaskBridgeClient
import io.github.nikkiw.taskbridge.api.TaskBridgeConfig
import io.github.nikkiw.taskbridge.api.TaskBridgeRouteResolver
import io.github.nikkiw.taskbridge.checkpoint.InMemoryTaskBridgeCheckpointStore
import io.github.nikkiw.taskbridge.checkpoint.TaskBridgeCheckpointStore
import io.github.nikkiw.taskbridge.okhttp.OkHttpTaskBridgeTransportConfig
import io.github.nikkiw.taskbridge.okhttp.OkHttpTaskBridgeTransportFactory
import io.github.nikkiw.taskbridge.policy.DefaultTaskBridgeFailureClassifier
import io.github.nikkiw.taskbridge.policy.ExponentialBackoffTaskBridgeRetryPolicy
import io.github.nikkiw.taskbridge.policy.TaskBridgeFailureClassifier
import io.github.nikkiw.taskbridge.policy.TaskBridgeRetryPolicy
import io.github.nikkiw.taskbridge.transport.SseSession
import io.github.nikkiw.taskbridge.transport.SseSessionFactory
import io.github.nikkiw.taskbridge.transport.TaskBridgeSseListener
import io.github.nikkiw.taskbridge.transport.TaskBridgeStreamTransportConfig
import io.github.nikkiw.taskbridge.transport.TaskBridgeTransportFactory
import io.github.nikkiw.taskbridge.transport.TaskBridgeWebSocketListener
import io.github.nikkiw.taskbridge.transport.TaskBridgeWebSocketSession
import io.github.nikkiw.taskbridge.transport.WebSocketSessionFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

@Suppress("LongParameterList")
internal fun buildOkHttpTestClient(
    baseUrl: String = "http://example.com/",
    routeResolver: TaskBridgeRouteResolver<Unit> = DefaultTaskBridgeRouteResolver(),
    webSocketFactory: WebSocketSessionFactory<Unit>? = null,
    checkpointStore: TaskBridgeCheckpointStore = InMemoryTaskBridgeCheckpointStore(),
    failureClassifier: TaskBridgeFailureClassifier = DefaultTaskBridgeFailureClassifier(),
    retryPolicy: TaskBridgeRetryPolicy = ExponentialBackoffTaskBridgeRetryPolicy(),
    sseSessionFactory: SseSessionFactory<Unit>? = null,
    authHeaderProvider: (suspend (context: Unit, forceRefresh: Boolean) -> String?)? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    okHttpClient: OkHttpClient = OkHttpClient(),
    streamTransportConfig: TaskBridgeStreamTransportConfig = testStreamTransportConfig(),
): TaskBridgeClient<Unit> {
    val baseTransportFactory =
        OkHttpTaskBridgeTransportFactory<Unit>(
            OkHttpTaskBridgeTransportConfig(
                okHttpClient = okHttpClient,
            ),
        )

    // Use an overriding factory to inject mocks/fakes if provided.
    // When sseSessionFactory is null we substitute a FakeSseSessionFactory that fires an
    // immediate failure. This prevents the real OkHttp SSE client from stalling on a
    // MockWebServer that has no SSE response enqueued, which would hang the test for 60s.
    // Tests that actually exercise SSE should pass their own factory explicitly.
    val effectiveSseFactory: SseSessionFactory<Unit> =
        sseSessionFactory ?: FakeSseSessionFactory(listOf(SseFrame.Failure(java.io.IOException("sse not available in test"))))
    val transportFactory =
        TaskBridgeTransportFactory<Unit> { config ->
            val bundle = baseTransportFactory.create(config)
            bundle.copy(
                webSocketFactory = webSocketFactory ?: bundle.webSocketFactory,
                sseSessionFactory = effectiveSseFactory,
            )
        }

    val config =
        TaskBridgeConfig(
            baseUrl = baseUrl,
            transportFactory = transportFactory,
            routeResolver = routeResolver,
            checkpointStore = checkpointStore,
            failureClassifier = failureClassifier,
            retryPolicy = retryPolicy,
            streamTransport = streamTransportConfig,
            authHeaderProvider = authHeaderProvider,
            dispatcher = dispatcher,
        )

    return TaskBridgeClient.create(config)
}

/**
 * Returns a [TaskBridgeStreamTransportConfig] suitable for unit tests:
 * all timeouts are set to 1 ms so that `withTimeout` / `onTimeout` calls inside the
 * transport do NOT register long-lived timers in [kotlinx.coroutines.test.TestCoroutineScheduler].
 * Dangling timers are the root cause of [kotlinx.coroutines.test.UncompletedCoroutinesError]
 * in `runTest` when the fake WebSocket or SSE fires synchronously and the consumer exits
 * before the liveness/open timer is cancelled.
 */
internal fun testStreamTransportConfig(
    pollEmptyBackoffMs: Long = 0L,
    transportOpenTimeoutMs: Long = 1L,
    livenessTimeoutMs: Long = 1L,
) = TaskBridgeStreamTransportConfig(
    pollEmptyBackoffMs = pollEmptyBackoffMs,
    transportOpenTimeoutMs = transportOpenTimeoutMs,
    livenessTimeoutMs = livenessTimeoutMs,
)

internal fun OkHttpClient.shutdown() {
    dispatcher.executorService.shutdown()
    connectionPool.evictAll()
    cache?.close()
}

internal fun buildOkHttpTransportFactory(okHttpClient: OkHttpClient = OkHttpClient()): OkHttpTaskBridgeTransportFactory<Unit> =
    OkHttpTaskBridgeTransportFactory<Unit>(
        OkHttpTaskBridgeTransportConfig(
            okHttpClient = okHttpClient,
        ),
    )

internal fun recordedRequestPath(request: RecordedRequest): String? = request.path

internal fun recordedRequestMethod(request: RecordedRequest): String? = request.method

internal fun recordedRequestBodyUtf8(request: RecordedRequest): String = request.body.clone().readUtf8()

internal fun mockWebServerRequestCount(server: MockWebServer): Int = server.requestCount

internal sealed interface WsFrame {
    data object Open : WsFrame

    data class Text(
        val body: String,
    ) : WsFrame

    data class Closing(
        val code: Int,
        val reason: String = "",
    ) : WsFrame

    data class Failure(
        val throwable: Throwable,
    ) : WsFrame
}

internal class FakeWebSocketFactory<Ctx>(
    private val script: List<WsFrame>,
) : WebSocketSessionFactory<Ctx> {
    val openedUrls = mutableListOf<String>()

    override suspend fun open(
        context: Ctx,
        url: String,
        listener: TaskBridgeWebSocketListener,
    ): TaskBridgeWebSocketSession {
        openedUrls += url
        val socket = FakeWebSocketSession()
        var opened = false
        script.forEach { frame ->
            when (frame) {
                WsFrame.Open -> {
                    if (!opened) {
                        opened = true
                        listener.onOpen(socket)
                    }
                }

                is WsFrame.Text -> {
                    if (!opened) {
                        opened = true
                        listener.onOpen(socket)
                    }
                    listener.onMessage(frame.body)
                }

                is WsFrame.Closing -> {
                    if (!opened) {
                        opened = true
                        listener.onOpen(socket)
                    }
                    listener.onClosing(frame.code, frame.reason)
                }

                is WsFrame.Failure -> {
                    if (!opened) {
                        opened = true
                        listener.onOpen(socket)
                    }
                    listener.onFailure(frame.throwable)
                }
            }
        }
        return socket
    }
}

internal sealed interface SseFrame {
    data object Open : SseFrame

    data class Event(
        val id: String?,
        val eventType: String?,
        val data: String,
    ) : SseFrame

    data class Failure(
        val throwable: Throwable,
    ) : SseFrame

    data object Closed : SseFrame
}

internal class FakeSseSessionFactory<Ctx>(
    private val script: List<SseFrame>,
) : SseSessionFactory<Ctx> {
    val openedUrls = mutableListOf<String>()
    val openedLastEventIds = mutableListOf<String?>()

    override suspend fun open(
        context: Ctx,
        url: String,
        lastEventId: String?,
        listener: TaskBridgeSseListener,
    ): SseSession {
        openedUrls += url
        openedLastEventIds += lastEventId
        val session = FakeSseSession()
        var opened = false
        script.forEach { frame ->
            when (frame) {
                SseFrame.Open -> {
                    if (!opened) {
                        opened = true
                        listener.onOpen()
                    }
                }

                is SseFrame.Event -> {
                    if (!opened) {
                        opened = true
                        listener.onOpen()
                    }
                    listener.onEvent(frame.id, frame.eventType, frame.data)
                }

                is SseFrame.Failure -> {
                    if (!opened) {
                        opened = true
                        listener.onOpen()
                    }
                    listener.onFailure(frame.throwable)
                }

                SseFrame.Closed -> listener.onClosed()
            }
        }
        return session
    }
}

internal class FakeSseSession : SseSession {
    var cancelled: Boolean = false

    override fun cancel() {
        cancelled = true
    }
}

internal class FakeWebSocketSession : TaskBridgeWebSocketSession {
    val sentMessages = mutableListOf<String>()
    var cancelled = false

    override fun send(text: String): Boolean {
        sentMessages += text
        return true
    }

    override fun close(
        code: Int,
        reason: String?,
    ): Boolean = true

    override fun cancel() {
        cancelled = true
    }
}
