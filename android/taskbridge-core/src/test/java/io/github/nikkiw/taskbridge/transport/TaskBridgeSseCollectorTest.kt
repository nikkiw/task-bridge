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
import io.github.nikkiw.taskbridge.SseFrame
import io.github.nikkiw.taskbridge.api.DefaultTaskBridgeRouteResolver
import io.github.nikkiw.taskbridge.model.TaskCompletedEvent
import io.github.nikkiw.taskbridge.model.TaskProgressEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class TaskBridgeSseCollectorTest {
    private fun buildTestStreamCollectContext(
        taskId: String = "task-1",
        baseUrl: String = "http://example.com/",
        sseSessionFactory: SseSessionFactory<Unit>,
    ): StreamCollectContext<Unit> {
        val deps =
            TaskBridgeStreamTransportDeps<Unit>(
                pollEventsClient = { _, _, _, _, _ -> throw UnsupportedOperationException() },
                webSocketFactory =
                    io.github.nikkiw.taskbridge
                        .FakeWebSocketFactory<Unit>(emptyList()),
                sseSessionFactory = sseSessionFactory,
                routeResolver =
                    io.github.nikkiw.taskbridge.api
                        .DefaultTaskBridgeRouteResolver<Unit>(),
                failureClassifier =
                    io.github.nikkiw.taskbridge.policy
                        .DefaultTaskBridgeFailureClassifier(),
                retryPolicy =
                    io.github.nikkiw.taskbridge.policy
                        .ExponentialBackoffTaskBridgeRetryPolicy(),
            )
        val options =
            TaskBridgeStreamTransportOptions<Unit>(
                streamConfig = TaskBridgeStreamTransportConfig(transportOpenTimeoutMs = 1L, livenessTimeoutMs = 1L),
                json = taskBridgeJson(),
                eventListener = null,
            )
        return StreamCollectContext(
            baseUrl = baseUrl,
            context = Unit,
            deps = deps,
            options = options,
            taskId = taskId,
        )
    }

    @Test
    fun `collectSseEvents emits events and terminates on terminal event`() =
        runTest {
            val script =
                listOf(
                    SseFrame.Open,
                    SseFrame.Event(
                        "1",
                        "message",
                        """{"type":"TASK_PROGRESS","taskId":"task-1","eventId":"1-0","createdAt":"2026-05-05T12:00:00Z","payload":{"progress":10}}""",
                    ),
                    SseFrame.Event(
                        "2",
                        "message",
                        """{"type":"TASK_COMPLETED","taskId":"task-1","eventId":"2-0","createdAt":"2026-05-05T12:00:01Z","payload":{"result":{}}}""",
                    ),
                )
            val sseFactory = FakeSseSessionFactory<Unit>(script)

            val inbound = mutableListOf<TransportInbound>()
            val collectCtx = buildTestStreamCollectContext(sseSessionFactory = sseFactory)

            collectSseEvents(collectCtx, lastEventId = null) {
                inbound.add(it)
            }

            assertEquals(2, inbound.size)
            assertTrue(
                inbound[0] is TransportInbound.Event && (inbound[0] as TransportInbound.Event).event is TaskProgressEvent,
            )
            assertTrue(
                inbound[1] is TransportInbound.Event && (inbound[1] as TransportInbound.Event).event is TaskCompletedEvent,
            )
        }

    @Test
    fun `collectSseEvents throws on failure during open`() =
        runTest {
            val script =
                listOf(
                    SseFrame.Failure(IOException("SSE failed to connect")),
                )
            val sseFactory = FakeSseSessionFactory<Unit>(script)

            val collectCtx = buildTestStreamCollectContext(sseSessionFactory = sseFactory)

            val result =
                runCatching {
                    collectSseEvents(collectCtx, lastEventId = null) { }
                }

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IOException)
        }

    @Test
    fun `collectSseEvents handles malformed payload without crashing`() =
        runTest {
            val script =
                listOf(
                    SseFrame.Open,
                    SseFrame.Event("1", "message", "not a json"),
                    SseFrame.Event(
                        "2",
                        "message",
                        """{"type":"TASK_COMPLETED","taskId":"task-1","eventId":"2-0","createdAt":"2026-05-05T12:00:01Z","payload":{"result":{}}}""",
                    ),
                )
            val sseFactory = FakeSseSessionFactory<Unit>(script)

            val inbound = mutableListOf<TransportInbound>()
            val collectCtx = buildTestStreamCollectContext(sseSessionFactory = sseFactory)

            collectSseEvents(collectCtx, lastEventId = null) {
                inbound.add(it)
            }

            assertEquals(2, inbound.size)
            assertTrue(inbound[0] is TransportInbound.MalformedPayload)
            assertTrue(inbound[1] is TransportInbound.Event)
        }
}
