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

import io.github.nikkiw.taskbridge.api.TaskBridgeRouteResolver
import io.github.nikkiw.taskbridge.checkpoint.InMemoryTaskBridgeCheckpointStore
import io.github.nikkiw.taskbridge.checkpoint.TaskBridgeCheckpointStore
import io.github.nikkiw.taskbridge.model.PollEventsResponse
import io.github.nikkiw.taskbridge.model.RawTaskEventEnvelope
import io.github.nikkiw.taskbridge.model.TaskCompletedEvent
import io.github.nikkiw.taskbridge.model.TaskEvent
import io.github.nikkiw.taskbridge.policy.DefaultTaskBridgeFailureClassifier
import io.github.nikkiw.taskbridge.policy.ExponentialBackoffTaskBridgeRetryPolicy
import io.github.nikkiw.taskbridge.transport.FakeTaskBridgeHttpApi
import io.github.nikkiw.taskbridge.transport.FallbackStrategy
import io.github.nikkiw.taskbridge.transport.SseSession
import io.github.nikkiw.taskbridge.transport.SseSessionFactory
import io.github.nikkiw.taskbridge.transport.TaskBridgeCheckpointBinding
import io.github.nikkiw.taskbridge.transport.TaskBridgeSseListener
import io.github.nikkiw.taskbridge.transport.TaskBridgeStreamTransport
import io.github.nikkiw.taskbridge.transport.TaskBridgeStreamTransportConfig
import io.github.nikkiw.taskbridge.transport.TaskBridgeStreamTransportDeps
import io.github.nikkiw.taskbridge.transport.TaskBridgeStreamTransportOptions
import io.github.nikkiw.taskbridge.transport.TaskBridgeTransportEventListener
import io.github.nikkiw.taskbridge.transport.TaskBridgeWebSocketListener
import io.github.nikkiw.taskbridge.transport.TaskBridgeWebSocketSession
import io.github.nikkiw.taskbridge.transport.WebSocketSessionFactory
import io.github.nikkiw.taskbridge.transport.asPollEventsClient
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.ArrayDeque

@Suppress("LargeClass")
class TaskBridgeStreamTransportTest {
    data class TestContext(
        val id: Int,
    )

    class TestRouteResolver : TaskBridgeRouteResolver<TestContext> {
        override fun createTaskPath(context: TestContext) = "api/ctx/${context.id}/tasks"

        override fun pollEventsPath(
            context: TestContext,
            taskId: String,
        ) = "api/ctx/${context.id}/tasks/$taskId/events"

        override fun cancelTaskPath(
            context: TestContext,
            taskId: String,
        ) = "api/ctx/${context.id}/tasks/$taskId/cancel"

        override fun submitActionPath(
            context: TestContext,
            taskId: String,
        ) = "api/ctx/${context.id}/tasks/$taskId/actions"

        override fun webSocketPath(context: TestContext) = "api/ctx/${context.id}/tasks/ws"

        override fun streamEventsPath(
            context: TestContext,
            taskId: String,
        ) = "api/ctx/${context.id}/tasks/$taskId/events/stream"
    }

    class TestEventListener : TaskBridgeTransportEventListener<TestContext> {
        val observedContexts = mutableListOf<TestContext>()
        val observedFallbackTasks = mutableListOf<String>()
        val observedSseFallbackTasks = mutableListOf<String>()

        override fun onFallbackToPolling(
            context: TestContext,
            taskId: String,
        ) {
            observedContexts.add(context)
            observedFallbackTasks.add(taskId)
        }

        override fun onFallbackToSse(
            context: TestContext,
            taskId: String,
        ) {
            observedSseFallbackTasks.add(taskId)
        }
    }

    @Test
    fun `checkpoint save error terminates flow instead of falling back`() =
        runTest {
            val store =
                object : TaskBridgeCheckpointStore {
                    override suspend fun save(
                        key: String,
                        lastEventId: String,
                    ): Unit = throw IOException("Disk full")

                    override suspend fun load(key: String): String? = null

                    override suspend fun clear(key: String) {
                        // no-op
                    }
                }

            val eventListener = TestEventListener()
            val transport =
                TaskBridgeStreamTransport(
                    baseUrl = "http://example.com/",
                    context = TestContext(1),
                    deps =
                        TaskBridgeStreamTransportDeps<TestContext>(
                            pollEventsClient = { _, tid, _, _, _ ->
                                PollEventsResponse(
                                    tid,
                                    emptyList<RawTaskEventEnvelope>(),
                                    nextAfterEventId = "e1",
                                    hasMore = false,
                                )
                            },
                            webSocketFactory =
                                FakeWebSocketFactory<TestContext>(
                                    listOf(
                                        WsFrame.Text(
                                            """
                                            {
                                                "type": "TASK_STARTED",
                                                "taskId": "task-1",
                                                "eventId": "e1",
                                                "createdAt": "2026-05-12T00:00:00Z"
                                            }
                                            """.trimIndent(),
                                        ),
                                    ),
                                ),
                            sseSessionFactory = FakeSseSessionFactory<TestContext>(emptyList()),
                            routeResolver = TestRouteResolver(),
                            failureClassifier = DefaultTaskBridgeFailureClassifier(),
                            retryPolicy = ExponentialBackoffTaskBridgeRetryPolicy(),
                        ),
                    checkpoint = TaskBridgeCheckpointBinding(store),
                    options =
                        TaskBridgeStreamTransportOptions(
                            eventListener = eventListener,
                            dispatcher = UnconfinedTestDispatcher(testScheduler),
                        ),
                )

            try {
                transport.observeTaskEvents("task-1").toList()
                assertTrue("Should have thrown IOException", false)
            } catch (e: IOException) {
                assertEquals("Disk full", e.message)
            }
            // Verify no fallback happened
            assertTrue(eventListener.observedFallbackTasks.isEmpty())
        }

    @Test
    fun `stream transport correctly passes generic context to resolver and listener during polling fallback`() =
        runTest {
            val json = Json { ignoreUnknownKeys = true }
            val testContext = TestContext(id = 42)
            val testTaskId = "task-generic"
            val baseUrl = "http://example.com/"

            var capturedUrl: String? = null
            val httpApi =
                FakeTaskBridgeHttpApi<TestContext>(
                    pollHandler = { url, _ ->
                        capturedUrl = url
                        PollEventsResponse(
                            taskId = testTaskId,
                            events =
                                listOf(
                                    RawTaskEventEnvelope(
                                        type = "TASK_COMPLETED",
                                        taskId = testTaskId,
                                        eventId = "1-0",
                                        createdAt = "2026-05-05T12:00:02Z",
                                        payload = kotlinx.serialization.json.buildJsonObject {},
                                    ),
                                ),
                            nextAfterEventId = "1-0",
                            hasMore = false,
                        )
                    },
                )

            // Web socket fails immediately, forcing a fallback to polling
            val webSocketFactory =
                FakeWebSocketFactory<TestContext>(
                    script = listOf(WsFrame.Open, WsFrame.Failure(IOException("ws down"))),
                )

            val eventListener = TestEventListener()

            val transport =
                TaskBridgeStreamTransport(
                    baseUrl = baseUrl,
                    context = testContext,
                    deps =
                        TaskBridgeStreamTransportDeps(
                            pollEventsClient = httpApi.asPollEventsClient(),
                            webSocketFactory = webSocketFactory,
                            sseSessionFactory =
                                FakeSseSessionFactory<TestContext>(
                                    script =
                                        listOf(
                                            SseFrame.Open,
                                            SseFrame.Failure(IOException("sse down")),
                                        ),
                                ),
                            routeResolver = TestRouteResolver(),
                            failureClassifier = DefaultTaskBridgeFailureClassifier(),
                            retryPolicy = ExponentialBackoffTaskBridgeRetryPolicy(),
                        ),
                    checkpoint =
                        TaskBridgeCheckpointBinding(
                            store = InMemoryTaskBridgeCheckpointStore(),
                            keyFactory = { ctx: TestContext, tid: String -> "${ctx.id}-$tid" },
                        ),
                    options =
                        TaskBridgeStreamTransportOptions(
                            streamConfig = testStreamTransportConfig(),
                            eventListener = eventListener,
                            json = json,
                            dispatcher = UnconfinedTestDispatcher(testScheduler),
                        ),
                )

            val events = transport.observeTaskEvents(testTaskId, null).toList()

            // Verify event parsed
            assertEquals(1, events.size)
            assertTrue(events[0] is TaskCompletedEvent)

            // Verify RouteResolver was called with context
            assertTrue(capturedUrl!!.contains("/api/ctx/42/tasks/task-generic/events"))

            // Verify EventListener was called with context
            assertEquals(1, eventListener.observedContexts.size)
            assertEquals(42, eventListener.observedContexts[0].id)
            assertEquals(testTaskId, eventListener.observedFallbackTasks[0])
            assertEquals(testTaskId, eventListener.observedSseFallbackTasks[0])
        }

    @Test
    fun `stream transport uses sse before polling and forwards Last-Event-ID`() =
        runTest {
            val json = Json { ignoreUnknownKeys = true }
            val testContext = TestContext(id = 7)

            var capturedUrl: String? = null
            val httpApi =
                FakeTaskBridgeHttpApi<TestContext>(
                    pollHandler = { url, _ ->
                        capturedUrl = url
                        error("Should not be called")
                    },
                )

            val webSocketFactory =
                FakeWebSocketFactory<TestContext>(
                    script =
                        listOf(
                            WsFrame.Open,
                            WsFrame.Failure(
                                IOException("ws down"),
                            ),
                        ),
                )
            val sseFactory =
                FakeSseSessionFactory<TestContext>(
                    script =
                        listOf(
                            SseFrame.Open,
                            SseFrame.Event(
                                id = "2-0",
                                eventType = "message",
                                data =
                                    """
                                    {"type":"TASK_COMPLETED","taskId":"task-2","eventId":"2-0","createdAt":"2026-05-05T12:00:02Z","payload":{"result":{"ok":true}}}
                                    """.trimIndent(),
                            ),
                            SseFrame.Closed,
                        ),
                )
            val transport =
                TaskBridgeStreamTransport(
                    baseUrl = "http://example.com/",
                    context = testContext,
                    deps =
                        TaskBridgeStreamTransportDeps(
                            pollEventsClient = httpApi.asPollEventsClient(),
                            webSocketFactory = webSocketFactory,
                            sseSessionFactory = sseFactory,
                            routeResolver = TestRouteResolver(),
                            failureClassifier = DefaultTaskBridgeFailureClassifier(),
                            retryPolicy = ExponentialBackoffTaskBridgeRetryPolicy(),
                        ),
                    checkpoint =
                        TaskBridgeCheckpointBinding(
                            store = InMemoryTaskBridgeCheckpointStore(),
                            keyFactory = { ctx: TestContext, tid: String -> "${ctx.id}-$tid" },
                        ),
                    options =
                        TaskBridgeStreamTransportOptions(
                            streamConfig = testStreamTransportConfig(),
                            eventListener = null,
                            json = json,
                            dispatcher = UnconfinedTestDispatcher(testScheduler),
                        ),
                )

            val events = transport.observeTaskEvents("task-2", "1-0").toList()

            assertEquals(listOf("2-0"), events.map { it.eventId })
            assertEquals(1, sseFactory.openedUrls.size)
            assertEquals("1-0", sseFactory.openedLastEventIds.first())
            assertNull(capturedUrl)
        }

    @Test
    fun `websocket failure preserves delivered watermark for sse fallback`() =
        runTest {
            val json = Json { ignoreUnknownKeys = true }
            val testContext = TestContext(id = 12)
            val taskId = "task-ws-watermark"
            val sseFactory =
                FakeSseSessionFactory<TestContext>(
                    script =
                        listOf(
                            SseFrame.Open,
                            SseFrame.Event(
                                id = "2-0",
                                eventType = "message",
                                data =
                                    """
                                    {"type":"TASK_COMPLETED","taskId":"$taskId","eventId":"2-0","createdAt":"2026-05-05T12:00:02Z","payload":{"result":{"ok":true}}}
                                    """.trimIndent(),
                            ),
                            SseFrame.Closed,
                        ),
                )

            val transport =
                TaskBridgeStreamTransport(
                    baseUrl = "http://example.com/",
                    context = testContext,
                    deps =
                        TaskBridgeStreamTransportDeps(
                            pollEventsClient = { _, _, _, _, _ -> error("poll should not run") },
                            webSocketFactory =
                                FakeWebSocketFactory<TestContext>(
                                    listOf(
                                        WsFrame.Open,
                                        WsFrame.Text(
                                            """
                                            {"type":"TASK_PROGRESS","taskId":"$taskId","eventId":"1-0","createdAt":"2026-05-05T12:00:00Z","payload":{}}
                                            """.trimIndent(),
                                        ),
                                        WsFrame.Failure(IOException("ws down")),
                                    ),
                                ),
                            sseSessionFactory = sseFactory,
                            routeResolver = TestRouteResolver(),
                            failureClassifier = DefaultTaskBridgeFailureClassifier(),
                            retryPolicy = ExponentialBackoffTaskBridgeRetryPolicy(),
                        ),
                    checkpoint =
                        TaskBridgeCheckpointBinding(
                            store = InMemoryTaskBridgeCheckpointStore(),
                            keyFactory = { ctx: TestContext, tid: String -> "${ctx.id}-$tid" },
                        ),
                    options =
                        TaskBridgeStreamTransportOptions(
                            streamConfig = testStreamTransportConfig(),
                            eventListener = null,
                            json = json,
                            dispatcher = UnconfinedTestDispatcher(testScheduler),
                        ),
                )

            val events = transport.observeTaskEvents(taskId).toList()

            assertEquals(listOf("1-0", "2-0"), events.map { it.eventId })
            assertEquals(listOf("1-0"), sseFactory.openedLastEventIds)
        }

    @Test
    fun `websocket failure during subscribe still preserves delivered watermark for sse fallback`() =
        runTest {
            val json = Json { ignoreUnknownKeys = true }
            val testContext = TestContext(id = 14)
            val taskId = "task-ws-subscribe-race"
            val sseFactory =
                FakeSseSessionFactory<TestContext>(
                    script =
                        listOf(
                            SseFrame.Open,
                            SseFrame.Event(
                                id = "2-0",
                                eventType = "message",
                                data =
                                    """
                                    {"type":"TASK_COMPLETED","taskId":"$taskId","eventId":"2-0","createdAt":"2026-05-05T12:00:02Z","payload":{"result":{"ok":true}}}
                                    """.trimIndent(),
                            ),
                            SseFrame.Closed,
                        ),
                )

            val transport =
                TaskBridgeStreamTransport(
                    baseUrl = "http://example.com/",
                    context = testContext,
                    deps =
                        TaskBridgeStreamTransportDeps(
                            pollEventsClient = { _, _, _, _, _ -> error("poll should not run") },
                            webSocketFactory =
                                object : WebSocketSessionFactory<TestContext> {
                                    override suspend fun open(
                                        context: TestContext,
                                        url: String,
                                        listener: TaskBridgeWebSocketListener,
                                    ): TaskBridgeWebSocketSession {
                                        val session =
                                            object : TaskBridgeWebSocketSession {
                                                private var handled = false

                                                override fun send(text: String): Boolean {
                                                    if (handled) return true
                                                    handled = true
                                                    listener.onMessage(
                                                        """
                                                        {"type":"TASK_PROGRESS","taskId":"$taskId","eventId":"1-0","createdAt":"2026-05-05T12:00:00Z","payload":{}}
                                                        """.trimIndent(),
                                                    )
                                                    listener.onFailure(IOException("ws down"))
                                                    return true
                                                }

                                                override fun close(
                                                    code: Int,
                                                    reason: String?,
                                                ): Boolean = true

                                                override fun cancel() = Unit
                                            }
                                        listener.onOpen(session)
                                        return session
                                    }
                                },
                            sseSessionFactory = sseFactory,
                            routeResolver = TestRouteResolver(),
                            failureClassifier = DefaultTaskBridgeFailureClassifier(),
                            retryPolicy = ExponentialBackoffTaskBridgeRetryPolicy(),
                        ),
                    checkpoint =
                        TaskBridgeCheckpointBinding(
                            store = InMemoryTaskBridgeCheckpointStore(),
                            keyFactory = { ctx: TestContext, tid: String -> "${ctx.id}-$tid" },
                        ),
                    options =
                        TaskBridgeStreamTransportOptions(
                            streamConfig = testStreamTransportConfig(),
                            eventListener = null,
                            json = json,
                            dispatcher = UnconfinedTestDispatcher(testScheduler),
                        ),
                )

            val events = transport.observeTaskEvents(taskId).toList()

            assertEquals(listOf("1-0", "2-0"), events.map { it.eventId })
            assertEquals(listOf("1-0"), sseFactory.openedLastEventIds)
        }

    @Test
    fun `sse failure preserves delivered watermark for polling fallback`() =
        runTest {
            val json = Json { ignoreUnknownKeys = true }
            val testContext = TestContext(id = 13)
            val taskId = "task-sse-watermark"
            val pollCalls = mutableListOf<String?>()

            val transport =
                TaskBridgeStreamTransport(
                    baseUrl = "http://example.com/",
                    context = testContext,
                    deps =
                        TaskBridgeStreamTransportDeps(
                            pollEventsClient = { _, _, afterEventId, _, _ ->
                                pollCalls += afterEventId
                                PollEventsResponse(
                                    taskId = taskId,
                                    events =
                                        listOf(
                                            RawTaskEventEnvelope(
                                                type = "TASK_COMPLETED",
                                                taskId = taskId,
                                                eventId = "3-0",
                                                createdAt = "2026-05-05T12:00:02Z",
                                                payload = Json.parseToJsonElement("""{"result":{"ok":true}}""").jsonObject,
                                            ),
                                        ),
                                    nextAfterEventId = "3-0",
                                    hasMore = false,
                                )
                            },
                            webSocketFactory =
                                FakeWebSocketFactory<TestContext>(
                                    listOf(WsFrame.Open, WsFrame.Failure(IOException("ws down"))),
                                ),
                            sseSessionFactory =
                                FakeSseSessionFactory<TestContext>(
                                    listOf(
                                        SseFrame.Open,
                                        SseFrame.Event(
                                            id = "2-0",
                                            eventType = "message",
                                            data =
                                                """
                                                {"type":"TASK_PROGRESS","taskId":"$taskId","eventId":"2-0","createdAt":"2026-05-05T12:00:01Z","payload":{}}
                                                """.trimIndent(),
                                        ),
                                        SseFrame.Failure(IOException("sse down")),
                                    ),
                                ),
                            routeResolver = TestRouteResolver(),
                            failureClassifier = DefaultTaskBridgeFailureClassifier(),
                            retryPolicy = ExponentialBackoffTaskBridgeRetryPolicy(),
                        ),
                    checkpoint =
                        TaskBridgeCheckpointBinding(
                            store = InMemoryTaskBridgeCheckpointStore(),
                            keyFactory = { ctx: TestContext, tid: String -> "${ctx.id}-$tid" },
                        ),
                    options =
                        TaskBridgeStreamTransportOptions(
                            streamConfig = testStreamTransportConfig(),
                            eventListener = null,
                            json = json,
                            dispatcher = UnconfinedTestDispatcher(testScheduler),
                        ),
                )

            val events = transport.observeTaskEvents(taskId, "1-0").toList()

            assertEquals(listOf("2-0", "3-0"), events.map { it.eventId })
            assertEquals(listOf("2-0"), pollCalls)
        }

    @Test
    fun `sse liveness timeout falls through to polling instead of surfacing closed channel failure`() =
        runTest {
            val json = Json { ignoreUnknownKeys = true }
            val testContext = TestContext(id = 11)
            val taskId = "task-sse-timeout"
            val pollCalls = mutableListOf<String?>()

            val transport =
                TaskBridgeStreamTransport(
                    baseUrl = "http://example.com/",
                    context = testContext,
                    deps =
                        TaskBridgeStreamTransportDeps(
                            pollEventsClient = { _, _, afterEventId, _, _ ->
                                pollCalls += afterEventId
                                PollEventsResponse(
                                    taskId = taskId,
                                    events =
                                        listOf(
                                            RawTaskEventEnvelope(
                                                type = "TASK_COMPLETED",
                                                taskId = taskId,
                                                eventId = "done-1",
                                                createdAt = "2026-05-05T12:00:02Z",
                                                payload = Json.parseToJsonElement("""{"result":{"ok":true}}""").jsonObject,
                                            ),
                                        ),
                                    nextAfterEventId = "done-1",
                                    hasMore = false,
                                )
                            },
                            webSocketFactory =
                                FakeWebSocketFactory<TestContext>(
                                    listOf(WsFrame.Open, WsFrame.Failure(IOException("ws down"))),
                                ),
                            sseSessionFactory = FakeSseSessionFactory<TestContext>(listOf(SseFrame.Open)),
                            routeResolver = TestRouteResolver(),
                            failureClassifier = DefaultTaskBridgeFailureClassifier(),
                            retryPolicy = ExponentialBackoffTaskBridgeRetryPolicy(),
                        ),
                    checkpoint =
                        TaskBridgeCheckpointBinding(
                            store = InMemoryTaskBridgeCheckpointStore(),
                            keyFactory = { ctx: TestContext, tid: String -> "${ctx.id}-$tid" },
                        ),
                    options =
                        TaskBridgeStreamTransportOptions(
                            streamConfig =
                                TaskBridgeStreamTransportConfig(
                                    fallbackStrategy = FallbackStrategy.PROGRESSIVE_STICKY,
                                    wsMaxAttempts = 1,
                                    sseMaxAttempts = 1,
                                    transportOpenTimeoutMs = 1L,
                                    livenessTimeoutMs = 10,
                                    pollEmptyBackoffMs = 0L,
                                ),
                            eventListener = null,
                            json = json,
                            dispatcher = UnconfinedTestDispatcher(testScheduler),
                        ),
                )

            val events = transport.observeTaskEvents(taskId).toList()

            assertEquals(listOf("done-1"), events.map { it.eventId })
            assertEquals(listOf(null), pollCalls)
        }

    @Test
    fun `stream transport rejects absolute websocket route resolver path`() =
        runTest {
            val json = Json { ignoreUnknownKeys = true }
            val resolver =
                object : TaskBridgeRouteResolver<TestContext> {
                    override fun createTaskPath(context: TestContext) = "api/ctx/${context.id}/tasks"

                    override fun pollEventsPath(
                        context: TestContext,
                        taskId: String,
                    ) = "api/ctx/${context.id}/tasks/$taskId/events"

                    override fun cancelTaskPath(
                        context: TestContext,
                        taskId: String,
                    ) = "api/ctx/${context.id}/tasks/$taskId/cancel"

                    override fun submitActionPath(
                        context: TestContext,
                        taskId: String,
                    ) = "api/ctx/${context.id}/tasks/$taskId/actions"

                    override fun webSocketPath(context: TestContext) = "wss://evil.example/tasks/ws"

                    override fun streamEventsPath(
                        context: TestContext,
                        taskId: String,
                    ) = "api/ctx/${context.id}/tasks/$taskId/events/stream"
                }

            val transport =
                TaskBridgeStreamTransport(
                    baseUrl = "https://api.example.com/",
                    context = TestContext(id = 9),
                    deps =
                        TaskBridgeStreamTransportDeps(
                            pollEventsClient = { _, _, _, _, _ -> error("poll should not run") },
                            webSocketFactory = FakeWebSocketFactory<TestContext>(emptyList()),
                            sseSessionFactory = FakeSseSessionFactory<TestContext>(emptyList()),
                            routeResolver = resolver,
                            failureClassifier = DefaultTaskBridgeFailureClassifier(),
                            retryPolicy = ExponentialBackoffTaskBridgeRetryPolicy(),
                        ),
                    checkpoint =
                        TaskBridgeCheckpointBinding(
                            store = InMemoryTaskBridgeCheckpointStore(),
                            keyFactory = { ctx: TestContext, tid: String -> "${ctx.id}-$tid" },
                        ),
                    options =
                        TaskBridgeStreamTransportOptions(
                            streamConfig = testStreamTransportConfig(),
                            eventListener = null,
                            json = json,
                            dispatcher = UnconfinedTestDispatcher(testScheduler),
                        ),
                )

            val result = runCatching { transport.observeTaskEvents("task-9").toList() }

            assertTrue("Result should be failure, but was $result", result.isFailure)
            assertTrue(
                "Expected IllegalArgumentException, got ${result.exceptionOrNull()}",
                result.exceptionOrNull() is IllegalArgumentException,
            )
        }

    private class FailingSaveCheckpointStore : TaskBridgeCheckpointStore {
        override suspend fun load(key: String): String? = null

        override suspend fun save(
            key: String,
            lastEventId: String,
        ) {
            error("checkpoint save failed")
        }

        override suspend fun clear(key: String) = Unit
    }

    @Test
    fun `at-least-once emit sends to flow before checkpoint save`() =
        runTest {
            val json = Json { ignoreUnknownKeys = true }
            val testContext = TestContext(id = 1)
            val testTaskId = "task-als"

            var capturedUrl: String? = null
            val httpApi =
                FakeTaskBridgeHttpApi<TestContext>(
                    pollHandler = { url, _ ->
                        capturedUrl = url
                        PollEventsResponse(
                            taskId = testTaskId,
                            events =
                                listOf(
                                    RawTaskEventEnvelope(
                                        type = "TASK_COMPLETED",
                                        taskId = testTaskId,
                                        eventId = "1-0",
                                        createdAt = "2026-05-05T12:00:02Z",
                                        payload = Json.parseToJsonElement("{}").jsonObject,
                                    ),
                                ),
                            nextAfterEventId = "1-0",
                            hasMore = false,
                        )
                    },
                )

            val webSocketFactory =
                FakeWebSocketFactory<TestContext>(
                    script = listOf(WsFrame.Open, WsFrame.Failure(IOException("ws down"))),
                )

            val transport =
                TaskBridgeStreamTransport(
                    baseUrl = "http://example.com/",
                    context = testContext,
                    deps =
                        TaskBridgeStreamTransportDeps(
                            pollEventsClient = httpApi.asPollEventsClient(),
                            webSocketFactory = webSocketFactory,
                            sseSessionFactory =
                                FakeSseSessionFactory<TestContext>(
                                    script =
                                        listOf(
                                            SseFrame.Open,
                                            SseFrame.Failure(IOException("sse down")),
                                        ),
                                ),
                            routeResolver = TestRouteResolver(),
                            failureClassifier = DefaultTaskBridgeFailureClassifier(),
                            retryPolicy = ExponentialBackoffTaskBridgeRetryPolicy(),
                        ),
                    checkpoint =
                        TaskBridgeCheckpointBinding(
                            store = FailingSaveCheckpointStore(),
                            keyFactory = { ctx: TestContext, tid: String -> "${ctx.id}-$tid" },
                        ),
                    options =
                        TaskBridgeStreamTransportOptions(
                            streamConfig = testStreamTransportConfig(),
                            eventListener = null,
                            json = json,
                            dispatcher = UnconfinedTestDispatcher(testScheduler),
                        ),
                )

            val received = mutableListOf<TaskEvent>()
            val outcome =
                runCatching {
                    transport.observeTaskEvents(testTaskId, null).collect {
                        received.add(it)
                    }
                }

            assertTrue(
                "emit should reach collector before failing checkpoint save",
                outcome.isFailure,
            )
            assertEquals(1, received.size)
            assertTrue(received[0] is TaskCompletedEvent)
            assertTrue(capturedUrl!!.contains("/task-als/events"))
        }

    @Test
    fun `polling does not trust nextAfterEventId before checkpoint save`() =
        runTest {
            val recordedAfterEventIds = mutableListOf<String?>()
            val responses =
                ArrayDeque(
                    listOf(
                        PollEventsResponse(
                            taskId = "task-poll-durable",
                            events = emptyList(),
                            nextAfterEventId = "5-0",
                            hasMore = false,
                        ),
                        PollEventsResponse(
                            taskId = "task-poll-durable",
                            events =
                                listOf(
                                    RawTaskEventEnvelope(
                                        type = "TASK_COMPLETED",
                                        taskId = "task-poll-durable",
                                        eventId = "2-0",
                                        createdAt = "2026-05-05T12:00:02Z",
                                        payload = Json.parseToJsonElement("""{"result":{"ok":true}}""").jsonObject,
                                    ),
                                ),
                            nextAfterEventId = "2-0",
                            hasMore = false,
                        ),
                    ),
                )

            val store = InMemoryTaskBridgeCheckpointStore()
            store.save("ctx-task-poll-durable", "1-0")

            val transport =
                TaskBridgeStreamTransport(
                    baseUrl = "http://example.com/",
                    context = TestContext(1),
                    deps =
                        TaskBridgeStreamTransportDeps<TestContext>(
                            pollEventsClient = { _, url, afterEventId, _, _ ->
                                assertTrue(url.contains("/task-poll-durable/events"))
                                recordedAfterEventIds += afterEventId
                                responses.removeFirst()
                            },
                            webSocketFactory =
                                FakeWebSocketFactory<TestContext>(
                                    listOf(WsFrame.Open, WsFrame.Failure(IOException("ws down"))),
                                ),
                            sseSessionFactory =
                                FakeSseSessionFactory<TestContext>(
                                    listOf(
                                        SseFrame.Open,
                                        SseFrame.Failure(IOException("sse down")),
                                    ),
                                ),
                            routeResolver = TestRouteResolver(),
                            failureClassifier = DefaultTaskBridgeFailureClassifier(),
                            retryPolicy = ExponentialBackoffTaskBridgeRetryPolicy(),
                        ),
                    checkpoint =
                        TaskBridgeCheckpointBinding(
                            store = store,
                            keyFactory = { _: TestContext, tid: String -> "ctx-$tid" },
                        ),
                    options =
                        TaskBridgeStreamTransportOptions(
                            streamConfig = testStreamTransportConfig(),
                            dispatcher = UnconfinedTestDispatcher(testScheduler),
                        ),
                )

            val events = transport.observeTaskEvents("task-poll-durable").toList()

            assertEquals(listOf("1-0", "1-0"), recordedAfterEventIds)
            assertEquals(listOf("2-0"), events.map { it.eventId })
        }

    @Test
    fun `websocket uses bounded channel and delivers all frames without trySend drops`() =
        runTest {
            val json = Json { ignoreUnknownKeys = true }
            val testContext = TestContext(id = 99)
            val taskId = "task-ws-buf"
            val wsScript =
                buildList {
                    add(WsFrame.Open)
                    repeat(12) { i ->
                        add(
                            WsFrame.Text(
                                """
                                {"type":"TASK_PROGRESS","taskId":"$taskId","eventId":"p-$i","createdAt":"2026-05-05T12:00:00Z","payload":{}}
                                """.trimIndent(),
                            ),
                        )
                    }
                    add(
                        WsFrame.Text(
                            """
                            {"type":"TASK_COMPLETED","taskId":"$taskId","eventId":"done","createdAt":"2026-05-05T12:00:02Z","payload":{}}
                            """.trimIndent(),
                        ),
                    )
                }
            val webSocketFactory = FakeWebSocketFactory<TestContext>(script = wsScript)

            var capturedUrl: String? = null
            val httpApi =
                FakeTaskBridgeHttpApi<TestContext>(
                    pollHandler = { url, _ ->
                        capturedUrl = url
                        error("Should not be called")
                    },
                )
            val transport =
                TaskBridgeStreamTransport(
                    baseUrl = "http://example.com/",
                    context = testContext,
                    deps =
                        TaskBridgeStreamTransportDeps(
                            pollEventsClient = httpApi.asPollEventsClient(),
                            webSocketFactory = webSocketFactory,
                            sseSessionFactory =
                                FakeSseSessionFactory<TestContext>(
                                    script = listOf(SseFrame.Failure(IOException("sse not used"))),
                                ),
                            routeResolver = TestRouteResolver(),
                            failureClassifier = DefaultTaskBridgeFailureClassifier(),
                            retryPolicy = ExponentialBackoffTaskBridgeRetryPolicy(),
                        ),
                    checkpoint =
                        TaskBridgeCheckpointBinding(
                            store = InMemoryTaskBridgeCheckpointStore(),
                            keyFactory = { ctx: TestContext, tid: String -> "${ctx.id}-$tid" },
                        ),
                    options =
                        TaskBridgeStreamTransportOptions(
                            streamConfig =
                                TaskBridgeStreamTransportConfig(
                                    fallbackStrategy = FallbackStrategy.FAST_CYCLE,
                                    transportOpenTimeoutMs = 1L,
                                    livenessTimeoutMs = 1L,
                                    pollEmptyBackoffMs = 0L,
                                    wsIncomingChannelCapacity = 1,
                                ),
                            eventListener = null,
                            json = json,
                            dispatcher = UnconfinedTestDispatcher(testScheduler),
                        ),
                )

            val events =
                transport
                    .observeTaskEvents(taskId, null)
                    .toList()

            assertEquals(13, events.size)
            assertTrue(events.last() is TaskCompletedEvent)
            assertNull(capturedUrl)
        }

    @Test
    fun `websocket setup IOException is reported to listener and does not swallow as generic Throwable`() =
        runTest {
            val json = Json { ignoreUnknownKeys = true }
            val testContext = TestContext(id = 3)
            val taskId = "task-ws-fail"

            class RecordingWsListener : TaskBridgeTransportEventListener<TestContext> {
                val errors = mutableListOf<Throwable>()

                override fun onWsSetupFailed(
                    context: TestContext,
                    taskId: String,
                    error: Throwable,
                ) {
                    errors += error
                }
            }

            val listener = RecordingWsListener()
            val webSocketFactory =
                FakeWebSocketFactory<TestContext>(
                    script = listOf(WsFrame.Open, WsFrame.Failure(IOException("ws io"))),
                )

            var capturedUrl: String? = null
            val httpApi =
                FakeTaskBridgeHttpApi<TestContext>(
                    pollHandler = { url, _ ->
                        capturedUrl = url
                        PollEventsResponse(
                            taskId = taskId,
                            events =
                                listOf(
                                    RawTaskEventEnvelope(
                                        type = "TASK_COMPLETED",
                                        taskId = taskId,
                                        eventId = "e1",
                                        createdAt = "2026-05-05T12:00:02Z",
                                        payload = Json.parseToJsonElement("{}").jsonObject,
                                    ),
                                ),
                            nextAfterEventId = "e1",
                            hasMore = false,
                        )
                    },
                )

            val transport =
                TaskBridgeStreamTransport(
                    baseUrl = "http://example.com/",
                    context = testContext,
                    deps =
                        TaskBridgeStreamTransportDeps(
                            pollEventsClient = httpApi.asPollEventsClient(),
                            webSocketFactory = webSocketFactory,
                            sseSessionFactory =
                                FakeSseSessionFactory<TestContext>(
                                    script =
                                        listOf(
                                            SseFrame.Open,
                                            SseFrame.Event(
                                                id = null,
                                                eventType = null,
                                                data =
                                                    """
                                                    {"type":"TASK_COMPLETED","taskId":"$taskId","eventId":"e1","createdAt":"2026-05-05T12:00:02Z","payload":{}}
                                                    """.trimIndent(),
                                            ),
                                            SseFrame.Closed,
                                        ),
                                ),
                            routeResolver = TestRouteResolver(),
                            failureClassifier = DefaultTaskBridgeFailureClassifier(),
                            retryPolicy = ExponentialBackoffTaskBridgeRetryPolicy(),
                        ),
                    checkpoint =
                        TaskBridgeCheckpointBinding(
                            store = InMemoryTaskBridgeCheckpointStore(),
                            keyFactory = { ctx: TestContext, tid: String -> "${ctx.id}-$tid" },
                        ),
                    options =
                        TaskBridgeStreamTransportOptions(
                            streamConfig =
                                TaskBridgeStreamTransportConfig(
                                    fallbackStrategy = FallbackStrategy.FAST_CYCLE,
                                    transportOpenTimeoutMs = 1L,
                                    livenessTimeoutMs = 1L,
                                    pollEmptyBackoffMs = 0L,
                                ),
                            eventListener = listener,
                            json = json,
                            dispatcher = UnconfinedTestDispatcher(testScheduler),
                        ),
                )

            transport.observeTaskEvents(taskId, null).toList()

            assertEquals(1, listener.errors.size)
            assertTrue(listener.errors[0] is IOException)
            // Polling might or might not have happened depending on whether SSE was also tried
            // but the test didn't assert on takeRequest before.
        }
}
