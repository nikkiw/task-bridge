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

import io.github.nikkiw.taskbridge.FakeSseSession
import io.github.nikkiw.taskbridge.FakeWebSocketSession
import io.github.nikkiw.taskbridge.api.DefaultTaskBridgeRouteResolver
import io.github.nikkiw.taskbridge.checkpoint.InMemoryTaskBridgeCheckpointStore
import io.github.nikkiw.taskbridge.model.PollEventsResponse
import io.github.nikkiw.taskbridge.model.TaskEvent
import io.github.nikkiw.taskbridge.policy.DefaultTaskBridgeFailureClassifier
import io.github.nikkiw.taskbridge.policy.TaskBridgeHttpStatusException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskBridgeAuthRetryTest {
    @Test
    fun `stream transport DOES NOT retry on 401 and immediately throws fatal error`() =
        runTest {
            val taskId = "task-auth-fail"
            var wsAttempts = 0
            var sseAttempts = 0

            val transport =
                TaskBridgeStreamTransport(
                    baseUrl = "http://example.com/",
                    context = Unit,
                    deps =
                        TaskBridgeStreamTransportDeps(
                            pollEventsClient = { _, _, _, _, _ ->
                                // Should not reach polling if WS and SSE both fail with fatal 401
                                PollEventsResponse(taskId, emptyList(), null, false)
                            },
                            webSocketFactory =
                                object : WebSocketSessionFactory<Unit> {
                                    override suspend fun open(
                                        context: Unit,
                                        url: String,
                                        listener: TaskBridgeWebSocketListener,
                                    ): TaskBridgeWebSocketSession {
                                        wsAttempts++
                                        val session = FakeWebSocketSession()
                                        // Ensure this runs slightly later if needed, though in this case it might be blocking the loop
                                        listener.onOpen(session)
                                        listener.onFailure(TaskBridgeHttpStatusException(401))
                                        return session
                                    }
                                },
                            sseSessionFactory =
                                object : SseSessionFactory<Unit> {
                                    override suspend fun open(
                                        context: Unit,
                                        url: String,
                                        lastEventId: String?,
                                        listener: TaskBridgeSseListener,
                                    ): SseSession {
                                        sseAttempts++
                                        listener.onOpen()
                                        listener.onFailure(TaskBridgeHttpStatusException(401))
                                        return FakeSseSession()
                                    }
                                },
                            routeResolver = DefaultTaskBridgeRouteResolver(),
                            failureClassifier = DefaultTaskBridgeFailureClassifier(),
                            retryPolicy = { 0L },
                        ),
                    checkpoint = TaskBridgeCheckpointBinding(InMemoryTaskBridgeCheckpointStore()),
                    options =
                        TaskBridgeStreamTransportOptions(
                            streamConfig =
                                TaskBridgeStreamTransportConfig(
                                    fallbackStrategy = FallbackStrategy.PROGRESSIVE_STICKY,
                                    wsMaxAttempts = 2,
                                    sseMaxAttempts = 2,
                                    transportOpenTimeoutMs = 1L,
                                    livenessTimeoutMs = 1L,
                                ),
                            dispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler),
                        ),
                )

            // Use a list to collect results
            val events = mutableListOf<TaskEvent>()
            val result =
                runCatching {
                    transport.observeTaskEvents(taskId).collect { events.add(it) }
                }

            assertTrue(
                "Should have failed with auth exception, but result was $result",
                result.isFailure,
            )
            val exception = result.exceptionOrNull()
            assertTrue(
                "Expected TaskBridgeAuthException, got $exception",
                exception is io.github.nikkiw.taskbridge.policy.TaskBridgeAuthException,
            )

            // Progressive sticky: 1 WS attempt. Since WS fails with 401, it should stop immediately without trying SSE.
            assertEquals("WS attempts mismatch", 1, wsAttempts)
            assertEquals("SSE attempts mismatch", 0, sseAttempts)
        }
}
