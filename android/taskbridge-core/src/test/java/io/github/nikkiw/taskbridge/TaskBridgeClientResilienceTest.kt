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

import io.github.nikkiw.taskbridge.api.startTaskJson
import io.github.nikkiw.taskbridge.checkpoint.InMemoryTaskBridgeCheckpointStore
import io.github.nikkiw.taskbridge.checkpoint.buildCheckpointKey
import io.github.nikkiw.taskbridge.model.PollEventsResponse
import io.github.nikkiw.taskbridge.model.RawTaskEventEnvelope
import io.github.nikkiw.taskbridge.policy.TaskBridgeRetryPolicy
import io.github.nikkiw.taskbridge.transport.FakeTaskBridgeHttpApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class TaskBridgeClientResilienceTest {
    @Test
    fun `startTaskJson retries on retryable exception`() =
        runTest {
            val callCount = AtomicInteger(0)
            val httpApi =
                FakeTaskBridgeHttpApi<Unit>(
                    createHandler = { _, _ ->
                        if (callCount.getAndIncrement() < 2) {
                            throw IOException("temporary network issue")
                        }
                        io.github.nikkiw.taskbridge.model.TaskCreatedResponse(
                            taskId = "t-retry",
                            status = io.github.nikkiw.taskbridge.model.TaskStatus.ACCEPTED,
                            clientRequestId = "req-1",
                            deduplicated = false,
                        )
                    },
                )

            val client =
                buildTestClient(
                    httpApi = httpApi,
                    retryPolicy = { 0L },
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            val response =
                client.startTaskJson(
                    Unit,
                    io.github.nikkiw.taskbridge.model.TaskCreateJsonRequest(
                        clientRequestId = "req-1",
                        taskType = "test",
                        input = buildJsonObject {},
                    ),
                )

            assertEquals("t-retry", response.taskId)
            assertEquals(3, callCount.get()) // 2 failures + 1 success
        }

    @Test
    fun `observeTaskEvents resumes from persisted checkpoint when explicit lastEventId is absent`() =
        runTest {
            val capturedUrls = mutableListOf<String>()
            val capturedAfterEventIds = mutableListOf<String?>()
            val callCount = AtomicInteger(0)

            val httpApi =
                FakeTaskBridgeHttpApi<Unit>(
                    pollHandler = { url, afterEventId ->
                        capturedUrls.add(url)
                        capturedAfterEventIds.add(afterEventId)
                        if (callCount.getAndIncrement() == 0) {
                            throw IOException("temporary")
                        }
                        PollEventsResponse(
                            taskId = "task-1",
                            events =
                                listOf(
                                    RawTaskEventEnvelope(
                                        type = "TASK_COMPLETED",
                                        taskId = "task-1",
                                        eventId = "3-0",
                                        createdAt = "2026-05-05T12:00:02Z",
                                        payload = buildJsonObject {},
                                    ),
                                ),
                            nextAfterEventId = "3-0",
                            hasMore = false,
                        )
                    },
                )

            val store = InMemoryTaskBridgeCheckpointStore()
            val baseUrl = "http://example.com/"
            val key = buildCheckpointKey(baseUrl, "task-1")
            store.save(key, "2-0")

            val client =
                buildTestClient(
                    baseUrl = baseUrl,
                    checkpointStore = store,
                    retryPolicy = TaskBridgeRetryPolicy { 0L },
                    webSocketFactory = FakeWebSocketFactory(listOf(WsFrame.Open, WsFrame.Failure(IOException("ws down")))),
                    httpApi = httpApi,
                )

            val events = client.observeTaskEvents(Unit, "task-1").toList()

            assertEquals(listOf("3-0"), events.map { it.eventId })
            assertEquals(null, store.load(key))
            assertEquals("2-0", capturedAfterEventIds[0])
            assertEquals("2-0", capturedAfterEventIds[1])
        }

    @Test
    fun `explicit lastEventId overrides persisted checkpoint`() =
        runTest {
            val capturedUrls = mutableListOf<String>()
            val capturedAfterEventIds = mutableListOf<String?>()
            val httpApi =
                FakeTaskBridgeHttpApi<Unit>(
                    pollHandler = { url, afterEventId ->
                        capturedUrls.add(url)
                        capturedAfterEventIds.add(afterEventId)
                        PollEventsResponse(
                            taskId = "task-1",
                            events =
                                listOf(
                                    RawTaskEventEnvelope(
                                        type = "TASK_COMPLETED",
                                        taskId = "task-1",
                                        eventId = "3-0",
                                        createdAt = "2026-05-05T12:00:02Z",
                                        payload = buildJsonObject {},
                                    ),
                                ),
                            nextAfterEventId = "3-0",
                            hasMore = false,
                        )
                    },
                )

            val store = InMemoryTaskBridgeCheckpointStore()
            val baseUrl = "http://example.com/"
            val key = buildCheckpointKey(baseUrl, "task-1")
            store.save(key, "9-0")

            val client =
                buildTestClient(
                    baseUrl = baseUrl,
                    checkpointStore = store,
                    retryPolicy = TaskBridgeRetryPolicy { 0L },
                    webSocketFactory = FakeWebSocketFactory(listOf(WsFrame.Open, WsFrame.Failure(IOException("ws down")))),
                    httpApi = httpApi,
                )

            val events = client.observeTaskEvents(Unit, "task-1", lastEventId = "1-0").toList()

            assertEquals(listOf("3-0"), events.map { it.eventId })
            assertEquals("1-0", capturedAfterEventIds[0])
        }
}
