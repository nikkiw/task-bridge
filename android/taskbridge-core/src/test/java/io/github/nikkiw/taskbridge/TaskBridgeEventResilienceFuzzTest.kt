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
import io.github.nikkiw.taskbridge.model.PollEventsResponse
import io.github.nikkiw.taskbridge.model.RawTaskEventEnvelope
import io.github.nikkiw.taskbridge.model.TaskEventType
import io.github.nikkiw.taskbridge.policy.DefaultTaskBridgeFailureClassifier
import io.github.nikkiw.taskbridge.policy.TaskBridgeRetryPolicy
import io.github.nikkiw.taskbridge.transport.FallbackStrategy
import io.github.nikkiw.taskbridge.transport.SseSession
import io.github.nikkiw.taskbridge.transport.SseSessionFactory
import io.github.nikkiw.taskbridge.transport.TaskBridgeCheckpointBinding
import io.github.nikkiw.taskbridge.transport.TaskBridgePollEventsClient
import io.github.nikkiw.taskbridge.transport.TaskBridgeSseListener
import io.github.nikkiw.taskbridge.transport.TaskBridgeStreamTransport
import io.github.nikkiw.taskbridge.transport.TaskBridgeStreamTransportConfig
import io.github.nikkiw.taskbridge.transport.TaskBridgeStreamTransportDeps
import io.github.nikkiw.taskbridge.transport.TaskBridgeStreamTransportOptions
import io.github.nikkiw.taskbridge.transport.TaskBridgeWebSocketListener
import io.github.nikkiw.taskbridge.transport.TaskBridgeWebSocketSession
import io.github.nikkiw.taskbridge.transport.WebSocketSessionFactory
import io.github.nikkiw.taskbridge.transport.taskBridgeJson
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class TaskBridgeEventResilienceFuzzTest {
    private val json = taskBridgeJson()

    @Test
    fun `state machine fuzz preserves resume tokens and event order across failover`() {
        runBlocking {
            checkAll(
                iterations = 25,
                Arb.int(4..8),
                Arb.int(1..3),
                Arb.int(0..2),
                Arb.int(0..1),
                Arb.int(0..1),
                Arb.boolean(),
                Arb.boolean(),
                Arb.int(1..3),
                Arb.int(0..2),
            ) { totalEvents, rawWsNew, rawSseNew, sseOverlap, pollOverlap, wsFails, sseFails, rawPollPageSize, emptyPollPages ->
                val wsNew = rawWsNew.coerceAtMost(totalEvents - 2)
                val remainingAfterWs = totalEvents - wsNew
                val sseNew = rawSseNew.coerceAtMost((remainingAfterWs - 1).coerceAtLeast(0))
                val pollPageSize = rawPollPageSize.coerceAtMost(totalEvents).coerceAtLeast(1)

                runScenario(
                    Scenario(
                        totalEvents = totalEvents,
                        wsNew = wsNew,
                        sseNew = sseNew,
                        sseOverlap = sseOverlap,
                        pollOverlap = pollOverlap,
                        wsFails = wsFails,
                        sseFails = sseFails,
                        pollPageSize = pollPageSize,
                        emptyPollPages = emptyPollPages,
                    ),
                )
            }
        }
    }

    private suspend fun runScenario(scenario: Scenario) {
        val taskId = "task-fuzz"
        val sourceEvents = buildSourceEvents(taskId, scenario.totalEvents)
        val wsEvents = sourceEvents.take(scenario.wsNew)

        val sseStartExclusive = (scenario.wsNew - scenario.sseOverlap).coerceAtLeast(0)
        val sseEndExclusive = (scenario.wsNew + scenario.sseNew).coerceAtMost(sourceEvents.lastIndex)
        val sseEvents =
            if (sseStartExclusive >= sseEndExclusive) {
                emptyList()
            } else {
                sourceEvents.subList(sseStartExclusive, sseEndExclusive)
            }

        val pollInitialResume =
            when {
                scenario.sseNew > 0 -> sourceEvents[scenario.wsNew + scenario.sseNew - 1].eventId
                scenario.wsNew > 0 -> sourceEvents[scenario.wsNew - 1].eventId
                else -> null
            }

        val transport =
            TaskBridgeStreamTransport(
                baseUrl = "http://example.com/",
                context = Unit,
                deps =
                    TaskBridgeStreamTransportDeps(
                        pollEventsClient =
                            ResumeAwarePollClient(
                                taskId = taskId,
                                sourceEvents = sourceEvents,
                                initialResume = pollInitialResume,
                                firstPageOverlap = scenario.pollOverlap,
                                pageSize = scenario.pollPageSize,
                                emptyPages = scenario.emptyPollPages,
                            ),
                        webSocketFactory =
                            ResumeAwareWebSocketFactory(
                                expectedLastEventId = null,
                                taskId = taskId,
                                events = wsEvents,
                                failAtEnd = scenario.wsFails,
                                json = json,
                            ),
                        sseSessionFactory =
                            ResumeAwareSseFactory(
                                expectedLastEventId = wsEvents.lastOrNull()?.eventId,
                                taskId = taskId,
                                events = sseEvents,
                                failAtEnd = scenario.sseFails,
                                json = json,
                            ),
                        routeResolver = TestRouteResolver(),
                        failureClassifier = DefaultTaskBridgeFailureClassifier(),
                        retryPolicy = TaskBridgeRetryPolicy { 0L },
                    ),
                checkpoint =
                    TaskBridgeCheckpointBinding(
                        store = InMemoryTaskBridgeCheckpointStore(),
                        keyFactory = { _: Unit, tid: String -> "fuzz-$tid" },
                    ),
                options =
                    TaskBridgeStreamTransportOptions(
                        streamConfig =
                            TaskBridgeStreamTransportConfig(
                                fallbackStrategy = FallbackStrategy.PROGRESSIVE_STICKY,
                                wsMaxAttempts = 1,
                                sseMaxAttempts = 1,
                                pollEmptyBackoffMs = 0L,
                                livenessTimeoutMs = 100L,
                            ),
                        eventListener = null,
                        json = json,
                    ),
            )

        val events = transport.observeTaskEvents(taskId).toList()

        assertEquals(
            "Scenario=$scenario",
            sourceEvents.map { it.eventId },
            events.map { it.eventId },
        )
    }

    private fun buildSourceEvents(
        taskId: String,
        totalEvents: Int,
    ): List<RawTaskEventEnvelope> =
        (1..totalEvents).map { index ->
            val type =
                if (index == totalEvents) {
                    TaskEventType.TASK_COMPLETED.name
                } else {
                    TaskEventType.TASK_PROGRESS.name
                }
            val payload =
                if (index == totalEvents) {
                    Json.parseToJsonElement("""{"result":{"ok":true}}""").jsonObject
                } else {
                    JsonObject(emptyMap())
                }
            RawTaskEventEnvelope(
                type = type,
                taskId = taskId,
                eventId = "$index-0",
                createdAt = "2026-05-05T12:00:00Z",
                payload = payload,
            )
        }

    private data class Scenario(
        val totalEvents: Int,
        val wsNew: Int,
        val sseNew: Int,
        val sseOverlap: Int,
        val pollOverlap: Int,
        val wsFails: Boolean,
        val sseFails: Boolean,
        val pollPageSize: Int,
        val emptyPollPages: Int,
    )

    private class TestRouteResolver : TaskBridgeRouteResolver<Unit> {
        override fun createTaskPath(context: Unit) = "api/tasks"

        override fun pollEventsPath(
            context: Unit,
            taskId: String,
        ) = "api/tasks/$taskId/events"

        override fun cancelTaskPath(
            context: Unit,
            taskId: String,
        ) = "api/tasks/$taskId/cancel"

        override fun submitActionPath(
            context: Unit,
            taskId: String,
        ) = "api/tasks/$taskId/actions"

        override fun webSocketPath(context: Unit) = "api/tasks/ws"

        override fun streamEventsPath(
            context: Unit,
            taskId: String,
        ) = "api/tasks/$taskId/events/stream"
    }

    private class ResumeAwareWebSocketFactory(
        private val expectedLastEventId: String?,
        private val taskId: String,
        private val events: List<RawTaskEventEnvelope>,
        private val failAtEnd: Boolean,
        private val json: Json,
    ) : WebSocketSessionFactory<Unit> {
        override suspend fun open(
            context: Unit,
            url: String,
            listener: TaskBridgeWebSocketListener,
        ): TaskBridgeWebSocketSession {
            val session =
                object : TaskBridgeWebSocketSession {
                    private var handled = false

                    override fun send(text: String): Boolean {
                        if (handled) return true
                        handled = true
                        val payload = json.parseToJsonElement(text).jsonObject
                        assertEquals(taskId, payload["taskId"]?.jsonPrimitive?.content)
                        val actualLastEventId = payload["lastEventId"]?.jsonPrimitive?.content
                        assertEquals(expectedLastEventId, actualLastEventId)
                        events.forEach { event ->
                            listener.onMessage(json.encodeToString(event))
                        }
                        if (failAtEnd) {
                            listener.onFailure(IOException("ws fuzz failure"))
                        } else {
                            listener.onClosing(1000, "normal")
                        }
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
    }

    private class ResumeAwareSseFactory(
        private val expectedLastEventId: String?,
        private val taskId: String,
        private val events: List<RawTaskEventEnvelope>,
        private val failAtEnd: Boolean,
        private val json: Json,
    ) : SseSessionFactory<Unit> {
        override suspend fun open(
            context: Unit,
            url: String,
            lastEventId: String?,
            listener: TaskBridgeSseListener,
        ): SseSession {
            assertEquals(expectedLastEventId, lastEventId)
            listener.onOpen()
            events.forEach { event ->
                listener.onEvent(
                    id = event.eventId,
                    type = "message",
                    data = json.encodeToString(event),
                )
            }
            if (failAtEnd) {
                listener.onFailure(IOException("sse fuzz failure"))
            } else {
                listener.onClosed()
            }
            return object : SseSession {
                override fun cancel() = Unit
            }
        }
    }

    private class ResumeAwarePollClient(
        private val taskId: String,
        private val sourceEvents: List<RawTaskEventEnvelope>,
        initialResume: String?,
        private val firstPageOverlap: Int,
        private val pageSize: Int,
        private var emptyPages: Int,
    ) : TaskBridgePollEventsClient<Unit> {
        private var expectedAfterEventId: String? = initialResume
        private var firstNonEmpty = true

        override suspend fun pollEvents(
            context: Unit,
            url: String,
            afterEventId: String?,
            waitTimeoutMs: Int,
            maxEvents: Int,
        ): PollEventsResponse {
            assertEquals(expectedAfterEventId, afterEventId)
            if (emptyPages > 0) {
                emptyPages -= 1
                return PollEventsResponse(
                    taskId = taskId,
                    events = emptyList(),
                    nextAfterEventId = afterEventId,
                    hasMore = true,
                )
            }

            val afterIndex = afterEventId?.let { eventId -> sourceEvents.indexOfFirst { it.eventId == eventId } + 1 } ?: 0
            val overlap = if (firstNonEmpty) firstPageOverlap else 0
            val startIndex = (afterIndex - overlap).coerceAtLeast(0)
            val chunk = sourceEvents.drop(startIndex).take(pageSize)
            check(chunk.isNotEmpty()) { "Polling returned no events for afterEventId=$afterEventId" }
            firstNonEmpty = false
            expectedAfterEventId = chunk.last().eventId
            return PollEventsResponse(
                taskId = taskId,
                events = chunk,
                nextAfterEventId = chunk.last().eventId,
                hasMore = chunk.last().type != TaskEventType.TASK_COMPLETED.name,
            )
        }
    }
}
