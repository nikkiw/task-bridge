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
package io.github.nikkiw.taskbridge.transport

import io.github.nikkiw.taskbridge.FakeSseSessionFactory
import io.github.nikkiw.taskbridge.FakeWebSocketFactory
import io.github.nikkiw.taskbridge.SseFrame
import io.github.nikkiw.taskbridge.WsFrame
import io.github.nikkiw.taskbridge.api.DefaultTaskBridgeRouteResolver
import io.github.nikkiw.taskbridge.checkpoint.InMemoryTaskBridgeCheckpointStore
import io.github.nikkiw.taskbridge.model.PollEventsResponse
import io.github.nikkiw.taskbridge.model.RawTaskEventEnvelope
import io.github.nikkiw.taskbridge.policy.DefaultTaskBridgeFailureClassifier
import io.github.nikkiw.taskbridge.policy.ExponentialBackoffTaskBridgeRetryPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskBridgeRawLoggingTest {
    private class CapturingEventListener : TaskBridgeTransportEventListener<Unit> {
        val rawPayloads = mutableListOf<Pair<TaskBridgeTransportSource, String>>()
        val wsMessages = mutableListOf<String>()
        val sseEvents = mutableListOf<Triple<String?, String?, String>>()
        val pollResponses = mutableListOf<String>()

        override fun onRawPayload(
            context: Unit,
            taskId: String,
            source: TaskBridgeTransportSource,
            payload: String,
        ) {
            rawPayloads.add(source to payload)
        }

        override fun onWsRawMessage(
            context: Unit,
            taskId: String,
            text: String,
        ) {
            wsMessages.add(text)
        }

        override fun onSseRawEvent(
            context: Unit,
            taskId: String,
            id: String?,
            type: String?,
            data: String,
        ) {
            sseEvents.add(Triple(id, type, data))
        }

        override fun onPollRawResponse(
            context: Unit,
            taskId: String,
            json: String,
        ) {
            pollResponses.add(json)
        }
    }

    private fun buildTestStreamCollectContext(
        taskId: String = "task-1",
        listener: TaskBridgeTransportEventListener<Unit>,
        wsFactory: WebSocketSessionFactory<Unit> = FakeWebSocketFactory<Unit>(emptyList()),
        sseFactory: SseSessionFactory<Unit> = FakeSseSessionFactory<Unit>(emptyList()),
        pollClient: TaskBridgePollEventsClient<Unit> =
            TaskBridgePollEventsClient<Unit> {
                _,
                _,
                _,
                _,
                _,
                ->
                PollEventsResponse("task-1", emptyList(), hasMore = false)
            },
    ): StreamCollectContext<Unit> {
        val deps =
            TaskBridgeStreamTransportDeps<Unit>(
                pollEventsClient = pollClient,
                webSocketFactory = wsFactory,
                sseSessionFactory = sseFactory,
                routeResolver = DefaultTaskBridgeRouteResolver<Unit>(),
                failureClassifier = DefaultTaskBridgeFailureClassifier(),
                retryPolicy = ExponentialBackoffTaskBridgeRetryPolicy(),
            )
        val options =
            TaskBridgeStreamTransportOptions<Unit>(
                streamConfig = TaskBridgeStreamTransportConfig(transportOpenTimeoutMs = 1L, livenessTimeoutMs = 1L),
                json = taskBridgeJson(),
                eventListener = listener,
            )
        return StreamCollectContext(
            baseUrl = "http://example.com/",
            context = Unit,
            deps = deps,
            options = options,
            taskId = taskId,
        )
    }

    @Test
    fun `WebSocket raw logging triggers listener`() =
        runTest {
            val text = """{"type":"TASK_COMPLETED","taskId":"task-1","eventId":"e1","createdAt":"..."}"""
            val wsFactory = FakeWebSocketFactory<Unit>(listOf(WsFrame.Open, WsFrame.Text(text)))
            val listener = CapturingEventListener()
            val collectCtx = buildTestStreamCollectContext(listener = listener, wsFactory = wsFactory)

            collectWebSocketEvents(collectCtx, lastEventId = null) { }

            assertEquals(1, listener.wsMessages.size)
            assertEquals(text, listener.wsMessages[0])
            assertEquals(1, listener.rawPayloads.size)
            assertEquals(TaskBridgeTransportSource.WebSocket, listener.rawPayloads[0].first)
            assertEquals(text, listener.rawPayloads[0].second)
        }

    @Test
    fun `SSE raw logging triggers listener`() =
        runTest {
            val id = "1"
            val type = "TASK_COMPLETED"
            val data = """{"type":"TASK_COMPLETED","taskId":"task-1","eventId":"1","createdAt":"..."}"""
            val sseFactory = FakeSseSessionFactory<Unit>(listOf(SseFrame.Open, SseFrame.Event(id, type, data)))
            val listener = CapturingEventListener()
            val collectCtx = buildTestStreamCollectContext(listener = listener, sseFactory = sseFactory)

            collectSseEvents(collectCtx, lastEventId = null) { }

            assertEquals(1, listener.sseEvents.size)
            assertEquals(Triple(id, type, data), listener.sseEvents[0])
            assertEquals(1, listener.rawPayloads.size)
            assertEquals(TaskBridgeTransportSource.Sse, listener.rawPayloads[0].first)
            // Reconstruction check: "id: 1\nevent: TASK_COMPLETED\ndata: {"type":"TASK_COMPLETED","taskId":"task-1","eventId":"1","createdAt":"..."}\n\n"
            val expectedRawSse = "id: $id\nevent: $type\ndata: $data\n\n"
            assertEquals(expectedRawSse, listener.rawPayloads[0].second)
        }

    @Test
    fun `Polling raw logging triggers listener`() =
        runTest {
            val response =
                PollEventsResponse(
                    taskId = "task-1",
                    events =
                        listOf(
                            RawTaskEventEnvelope(
                                type = "TASK_COMPLETED",
                                taskId = "task-1",
                                eventId = "1",
                                createdAt = "2026-05-05T12:00:00Z",
                                payload = buildJsonObject { },
                            ),
                        ),
                    hasMore = false,
                )
            val json = taskBridgeJson()
            val rawJson = json.encodeToString(PollEventsResponse.serializer(), response)
            response.rawJson = rawJson

            val pollClient = TaskBridgePollEventsClient<Unit> { _, _, _, _, _ -> response }
            val listener = CapturingEventListener()

            val checkpointStore = InMemoryTaskBridgeCheckpointStore()
            val transport =
                TaskBridgeStreamTransport<Unit>(
                    baseUrl = "http://example.com/",
                    context = Unit,
                    deps =
                        TaskBridgeStreamTransportDeps<Unit>(
                            pollEventsClient = pollClient,
                            webSocketFactory = FakeWebSocketFactory<Unit>(listOf(WsFrame.Failure(java.io.IOException("skip")))),
                            sseSessionFactory = FakeSseSessionFactory<Unit>(listOf(SseFrame.Failure(java.io.IOException("skip")))),
                            routeResolver = DefaultTaskBridgeRouteResolver<Unit>(),
                            failureClassifier = DefaultTaskBridgeFailureClassifier(),
                            retryPolicy = ExponentialBackoffTaskBridgeRetryPolicy(),
                        ),
                    checkpoint =
                        TaskBridgeCheckpointBinding(
                            store = checkpointStore,
                            namespace = null,
                        ),
                    options =
                        TaskBridgeStreamTransportOptions(
                            streamConfig =
                                TaskBridgeStreamTransportConfig(
                                    fallbackStrategy = FallbackStrategy.FAST_CYCLE,
                                    wsMaxAttempts = 0,
                                    sseMaxAttempts = 0,
                                    transportOpenTimeoutMs = 1L,
                                    livenessTimeoutMs = 1L,
                                    pollEmptyBackoffMs = 0L,
                                ),
                            json = json,
                            eventListener = listener,
                        ),
                )

            transport.observeTaskEvents("task-1").first()

            assertEquals(1, listener.pollResponses.size)
            assertEquals(rawJson, listener.pollResponses[0])
            assertEquals(1, listener.rawPayloads.size)
            assertEquals(TaskBridgeTransportSource.Polling, listener.rawPayloads[0].first)
            assertEquals(rawJson, listener.rawPayloads[0].second)
        }
}
