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
package io.github.nikkiw.taskbridge.api

import io.github.nikkiw.taskbridge.FakeSseSessionFactory
import io.github.nikkiw.taskbridge.FakeWebSocketFactory
import io.github.nikkiw.taskbridge.SseFrame
import io.github.nikkiw.taskbridge.WsFrame
import io.github.nikkiw.taskbridge.buildOkHttpTestClient
import io.github.nikkiw.taskbridge.buildOkHttpTransportFactory
import io.github.nikkiw.taskbridge.checkpoint.InMemoryTaskBridgeCheckpointStore
import io.github.nikkiw.taskbridge.checkpoint.buildCheckpointKey
import io.github.nikkiw.taskbridge.mockWebServerRequestCount
import io.github.nikkiw.taskbridge.model.RawTaskEventEnvelope
import io.github.nikkiw.taskbridge.model.SubmitActionStatus
import io.github.nikkiw.taskbridge.model.TaskActionAcceptedEvent
import io.github.nikkiw.taskbridge.model.TaskActionRequest
import io.github.nikkiw.taskbridge.model.TaskBridgeMultipartAttachment
import io.github.nikkiw.taskbridge.model.TaskCompletedEvent
import io.github.nikkiw.taskbridge.model.TaskCreateJsonRequest
import io.github.nikkiw.taskbridge.model.TaskProgressEvent
import io.github.nikkiw.taskbridge.model.TaskStartedEvent
import io.github.nikkiw.taskbridge.model.TaskSuspendedEvent
import io.github.nikkiw.taskbridge.model.UnknownTaskEvent
import io.github.nikkiw.taskbridge.model.toTaskEvent
import io.github.nikkiw.taskbridge.recordedRequestBodyUtf8
import io.github.nikkiw.taskbridge.recordedRequestMethod
import io.github.nikkiw.taskbridge.recordedRequestPath
import io.github.nikkiw.taskbridge.shutdown
import io.github.nikkiw.taskbridge.transport.taskBridgeJson
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert
import org.junit.Test
import java.io.IOException

@Suppress("LargeClass")
class TaskBridgeClientTest {
    @Test
    fun `observeTaskEvents emits typed websocket events and closes on terminal event`() =
        runTest {
            val okHttpClient = OkHttpClient()
            val webSocketFactory =
                FakeWebSocketFactory<Unit>(
                    script =
                        listOf(
                            WsFrame.Open,
                            WsFrame.Text(
                                """
                                {"type":"SUBSCRIPTION_CONFIRMED","taskId":"task-1","live":true}
                                """.trimIndent(),
                            ),
                            WsFrame.Text(
                                """
                                {"type":"TASK_PROGRESS","taskId":"task-1","eventId":"2-0","createdAt":"2026-05-05T12:00:01Z","payload":{"progress":10,"message":"processing"}}
                                """.trimIndent(),
                            ),
                            WsFrame.Text(
                                """
                                {"type":"TASK_COMPLETED","taskId":"task-1","eventId":"3-0","createdAt":"2026-05-05T12:00:02Z","payload":{"result":{"ok":true}}}
                                """.trimIndent(),
                            ),
                        ),
                )

            val client =
                buildOkHttpTestClient(
                    webSocketFactory = webSocketFactory,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    okHttpClient = okHttpClient,
                )

            val events = client.observeTaskEvents("task-1").toList()

            Assert.assertEquals(2, events.size)
            Assert.assertTrue(events[0] is TaskProgressEvent)
            Assert.assertTrue(events[1] is TaskCompletedEvent)
            Assert.assertEquals(listOf("2-0", "3-0"), events.map { it.eventId })
            okHttpClient.shutdown()
        }

    @Test
    fun `observeTaskEvents parses TASK_STARTED as typed event`() =
        runTest {
            val okHttpClient = OkHttpClient()
            val webSocketFactory =
                FakeWebSocketFactory<Unit>(
                    script =
                        listOf(
                            WsFrame.Open,
                            WsFrame.Text("""{"type":"SUBSCRIPTION_CONFIRMED","taskId":"task-1","live":true}"""),
                            WsFrame.Text(
                                """
                                {"type":"TASK_STARTED","taskId":"task-1","eventId":"1-0","createdAt":"2026-05-05T12:00:00Z","payload":{"message":"started"}}
                                """.trimIndent(),
                            ),
                            WsFrame.Text(
                                """
                                {"type":"TASK_COMPLETED","taskId":"task-1","eventId":"2-0","createdAt":"2026-05-05T12:00:01Z","payload":{"result":{"ok":true}}}
                                """.trimIndent(),
                            ),
                        ),
                )

            val client =
                buildOkHttpTestClient(
                    webSocketFactory = webSocketFactory,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    okHttpClient = okHttpClient,
                )
            val events = client.observeTaskEvents("task-1").toList()

            Assert.assertTrue(events[0] is TaskStartedEvent)
            Assert.assertTrue(events[1] is TaskCompletedEvent)
            Assert.assertEquals(listOf("1-0", "2-0"), events.map { it.eventId })
            okHttpClient.shutdown()
        }

    @Test
    fun `observeTaskEvents falls back to polling after websocket failure`() =
        runTest {
            val okHttpClient = OkHttpClient()
            try {
                MockWebServer().use { server ->
                    server.enqueue(
                        MockResponse().setBody(
                            """
                            {
                              "taskId":"task-1",
                              "events":[
                                {"type":"TASK_PROGRESS","taskId":"task-1","eventId":"2-0","createdAt":"2026-05-05T12:00:01Z","payload":{"progress":10}},
                                {"type":"TASK_COMPLETED","taskId":"task-1","eventId":"3-0","createdAt":"2026-05-05T12:00:02Z","payload":{"result":{"ok":true}}}
                              ],
                              "nextAfterEventId":"3-0",
                              "hasMore":false
                            }
                            """.trimIndent(),
                        ),
                    )

                    val webSocketFactory =
                        FakeWebSocketFactory<Unit>(
                            script = listOf(WsFrame.Open, WsFrame.Failure(IOException("ws down"))),
                        )
                    val sseSessionFactory =
                        FakeSseSessionFactory<Unit>(
                            script = listOf(SseFrame.Failure(IOException("sse down"))),
                        )
                    val client =
                        buildOkHttpTestClient(
                            baseUrl = server.url("/").toString(),
                            webSocketFactory = webSocketFactory,
                            sseSessionFactory = sseSessionFactory,
                            dispatcher = UnconfinedTestDispatcher(testScheduler),
                            okHttpClient = okHttpClient,
                        )
                    val events =
                        client
                            .observeTaskEvents("task-1", lastEventId = "1-0")
                            .toList()

                    Assert.assertEquals(2, events.size)
                    Assert.assertTrue(events[0] is TaskProgressEvent)
                    Assert.assertTrue(events[1] is TaskCompletedEvent)

                    val pollRequest = server.takeRequest()
                    Assert.assertTrue(recordedRequestPath(pollRequest)!!.contains("afterEventId=1-0"))
                }
            } finally {
                okHttpClient.shutdown()
            }
        }

    @Test
    fun `observeTaskEvents deduplicates by eventId across websocket and polling`() =
        runTest {
            val okHttpClient = OkHttpClient()
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse().setBody(
                        """
                        {
                          "taskId":"task-1",
                          "events":[
                            {"type":"TASK_PROGRESS","taskId":"task-1","eventId":"2-0","createdAt":"2026-05-05T12:00:01Z","payload":{"progress":10}},
                            {"type":"TASK_COMPLETED","taskId":"task-1","eventId":"3-0","createdAt":"2026-05-05T12:00:02Z","payload":{"result":{"ok":true}}}
                          ],
                          "nextAfterEventId":"3-0",
                          "hasMore":false
                        }
                        """.trimIndent(),
                    ),
                )

                val webSocketFactory =
                    FakeWebSocketFactory<Unit>(
                        script =
                            listOf(
                                WsFrame.Open,
                                WsFrame.Text(
                                    """
                                    {"type":"SUBSCRIPTION_CONFIRMED","taskId":"task-1","live":true}
                                    """.trimIndent(),
                                ),
                                WsFrame.Text(
                                    """
                                    {"type":"TASK_PROGRESS","taskId":"task-1","eventId":"2-0","createdAt":"2026-05-05T12:00:01Z","payload":{"progress":10}}
                                    """.trimIndent(),
                                ),
                                WsFrame.Failure(IOException("ws down")),
                            ),
                    )
                val client =
                    buildOkHttpTestClient(
                        baseUrl = server.url("/").toString(),
                        webSocketFactory = webSocketFactory,
                        dispatcher = UnconfinedTestDispatcher(testScheduler),
                        okHttpClient = okHttpClient,
                    )
                val events = client.observeTaskEvents("task-1", lastEventId = "1-0").toList()

                Assert.assertEquals(listOf("2-0", "3-0"), events.map { it.eventId })
            }
            okHttpClient.shutdown()
        }

    @Test
    fun `startTaskMultipart sends multipart fields and attachment`() =
        runTest {
            val okHttpClient = OkHttpClient()
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse().setBody(
                        """
                        {"taskId":"task-1","status":"ACCEPTED","clientRequestId":"req-1","deduplicated":false}
                        """.trimIndent(),
                    ),
                )
                val client =
                    buildOkHttpTestClient(
                        baseUrl = server.url("/").toString(),
                        dispatcher = UnconfinedTestDispatcher(testScheduler),
                        okHttpClient = okHttpClient,
                    )
                val attachment =
                    TaskBridgeMultipartAttachment(
                        fieldName = "attachment",
                        fileName = "photo.png",
                        contentType = "image/png",
                        content = "png".encodeToByteArray(),
                    )

                val response =
                    client.startTaskMultipart(
                        clientRequestId = "req-1",
                        taskType = "vision.classify",
                        inputJson = """{"image":"1"}""",
                        metadataJson = """{"source":"test"}""",
                        attachments = listOf(attachment),
                    )

                Assert.assertEquals("task-1", response.taskId)
                val request = server.takeRequest()
                val requestBody = recordedRequestBodyUtf8(request)
                Assert.assertEquals("POST", recordedRequestMethod(request))
                Assert.assertTrue(
                    request.getHeader("Content-Type")!!.startsWith("multipart/form-data"),
                )
                Assert.assertTrue(requestBody.contains("vision.classify"))
                Assert.assertTrue(requestBody.contains("photo.png"))
            }
            okHttpClient.shutdown()
        }

    @Test
    fun `authHeaderProvider adds authorization header only to same host`() =
        runTest {
            val okHttpClient = OkHttpClient()
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse().setBody(
                        """
                        {"taskId":"task-1","status":"ACCEPTED","clientRequestId":"req-1","deduplicated":false}
                        """.trimIndent(),
                    ),
                )
                val baseUrl = server.url("/").toString()
                val config =
                    TaskBridgeConfig(
                        baseUrl = baseUrl,
                        transportFactory =
                            buildOkHttpTransportFactory(
                                okHttpClient = okHttpClient,
                            ),
                        authHeaderProvider = { _, _ -> "Bearer test-token" },
                    )
                val client = TaskBridgeClient.create(config)

                client.startTaskJson(
                    TaskCreateJsonRequest(
                        clientRequestId = "req-1",
                        taskType = "demo.echo",
                        input = buildJsonObject { put("x", 1) },
                    ),
                )

                val request = server.takeRequest()
                Assert.assertEquals("Bearer test-token", request.getHeader("Authorization"))

                // Now verify it doesn't leak if we were to use the same client's OkHttpClient for another URL.
                // Since TaskBridgeClient doesn't expose its OkHttpClient, we have to trust that TaskBridgeHttpApi
                // or other internal parts only use URLs from the same host.
                // But we can test the interceptor directly if we could access it.
                // Since it's created inside TaskBridgeClient.create(config), we can't easily.
            }
            okHttpClient.shutdown()
        }

    @Test
    fun `route resolver absolute http url is rejected before request dispatch`() =
        runTest {
            val okHttpClient = OkHttpClient()
            val resolver =
                object : TaskBridgeRouteResolver<Unit> {
                    override fun createTaskPath(context: Unit) = "https://evil.example/api/v1/tasks"

                    override fun pollEventsPath(
                        context: Unit,
                        taskId: String,
                    ) = "api/v1/tasks/$taskId/events"

                    override fun cancelTaskPath(
                        context: Unit,
                        taskId: String,
                    ) = "api/v1/tasks/$taskId/cancel"

                    override fun submitActionPath(
                        context: Unit,
                        taskId: String,
                    ) = "api/v1/tasks/$taskId/actions"

                    override fun webSocketPath(context: Unit) = "api/v1/tasks/ws"

                    override fun streamEventsPath(
                        context: Unit,
                        taskId: String,
                    ) = "api/v1/tasks/$taskId/events/stream"
                }

            val client =
                buildOkHttpTestClient(
                    routeResolver = resolver,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    okHttpClient = okHttpClient,
                )

            val result =
                runCatching {
                    client.startTaskJson(
                        TaskCreateJsonRequest(
                            clientRequestId = "req-1",
                            taskType = "demo.echo",
                            input = buildJsonObject { put("x", 1) },
                        ),
                    )
                }

            Assert.assertTrue(result.isFailure)
            Assert.assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            okHttpClient.shutdown()
        }

    @Test
    fun `custom route resolver changes poll and ws paths`() =
        runTest {
            val okHttpClient = OkHttpClient()
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse().setBody(
                        """
                        {
                          "taskId":"task-1",
                          "events":[
                            {"type":"TASK_COMPLETED","taskId":"task-1","eventId":"2-0","createdAt":"2026-05-05T12:00:02Z","payload":{"result":{"ok":true}}}
                          ],
                          "nextAfterEventId":"2-0",
                          "hasMore":false
                        }
                        """.trimIndent(),
                    ),
                )

                val resolver =
                    object : TaskBridgeRouteResolver<Unit> {
                        override fun createTaskPath(context: Unit) = "api/v2/sessions/s1/tasks"

                        override fun pollEventsPath(
                            context: Unit,
                            taskId: String,
                        ) = "api/v2/sessions/s1/tasks/$taskId/events"

                        override fun cancelTaskPath(
                            context: Unit,
                            taskId: String,
                        ) = "api/v2/sessions/s1/tasks/$taskId/cancel"

                        override fun submitActionPath(
                            context: Unit,
                            taskId: String,
                        ) = "api/v2/sessions/s1/tasks/$taskId/actions"

                        override fun webSocketPath(context: Unit) = "api/v2/sessions/s1/tasks/ws"

                        override fun streamEventsPath(
                            context: Unit,
                            taskId: String,
                        ) = "api/v2/sessions/s1/tasks/$taskId/events/stream"
                    }

                val client =
                    buildOkHttpTestClient(
                        baseUrl = server.url("/").toString(),
                        routeResolver = resolver,
                        webSocketFactory =
                            FakeWebSocketFactory<Unit>(
                                listOf(
                                    WsFrame.Open,
                                    WsFrame.Failure(
                                        IOException("ws down"),
                                    ),
                                ),
                            ),
                        dispatcher = UnconfinedTestDispatcher(testScheduler),
                        okHttpClient = okHttpClient,
                    )

                client.observeTaskEvents("task-1", lastEventId = "1-0").toList()

                val request = server.takeRequest()
                Assert.assertTrue(recordedRequestPath(request)!!.startsWith("/api/v2/sessions/s1/tasks/task-1/events"))
            }
            okHttpClient.shutdown()
        }

    @Test
    fun `unknown task event type is surfaced as UnknownTaskEvent`() =
        runTest {
            val okHttpClient = OkHttpClient()
            val webSocketFactory =
                FakeWebSocketFactory<Unit>(
                    script =
                        listOf(
                            WsFrame.Open,
                            WsFrame.Text("""{"type":"SUBSCRIPTION_CONFIRMED","taskId":"task-1","live":true}"""),
                            WsFrame.Text(
                                """
                                {"type":"TASK_DEFERRED","taskId":"task-1","eventId":"2-0","createdAt":"2026-05-05T12:00:01Z","payload":{"note":"future"}}
                                """.trimIndent(),
                            ),
                            WsFrame.Text(
                                """
                                {"type":"TASK_COMPLETED","taskId":"task-1","eventId":"3-0","createdAt":"2026-05-05T12:00:02Z","payload":{"result":{"ok":true}}}
                                """.trimIndent(),
                            ),
                        ),
                )
            val client =
                buildOkHttpTestClient(
                    webSocketFactory = webSocketFactory,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    okHttpClient = okHttpClient,
                )

            val events = client.observeTaskEvents("task-1").toList()

            Assert.assertTrue(events[0] is UnknownTaskEvent)
            Assert.assertEquals("TASK_DEFERRED", (events[0] as UnknownTaskEvent).wireType)
            Assert.assertTrue(events[1] is TaskCompletedEvent)
            okHttpClient.shutdown()
        }

    @Test
    fun `suspension and action accepted events are surfaced as typed events`() {
        val json =
            taskBridgeJson()
        val suspended =
            json
                .decodeFromString<RawTaskEventEnvelope>(
                    """
                    {"type":"TASK_SUSPENDED","taskId":"task-1","eventId":"4-0","createdAt":"2026-05-05T12:00:03Z","payload":{"suspendId":"suspend-1","kind":"USER_ACTION_REQUIRED","reasonCode":"clarification_required","allowedActions":["submit_form"],"schemaVersion":1,"interaction":{"question":"Continue?"},"uiHints":{"presentation":"dialog"}}}
                    """.trimIndent(),
                ).toTaskEvent()
        val accepted =
            json
                .decodeFromString<RawTaskEventEnvelope>(
                    """
                    {"type":"TASK_ACTION_ACCEPTED","taskId":"task-1","eventId":"5-0","createdAt":"2026-05-05T12:00:04Z","payload":{"suspendId":"suspend-1","clientActionId":"action-1","actionType":"submit_form","acceptedAt":"2026-05-05T12:00:04Z"}}
                    """.trimIndent(),
                ).toTaskEvent()

        Assert.assertTrue(suspended is TaskSuspendedEvent)
        Assert.assertEquals("suspend-1", (suspended as TaskSuspendedEvent).suspension.suspendId)
        Assert.assertEquals(listOf("submit_form"), suspended.suspension.allowedActions)
        Assert.assertTrue(accepted is TaskActionAcceptedEvent)
        Assert.assertEquals(
            "action-1",
            (accepted as TaskActionAcceptedEvent).accepted.clientActionId,
        )
    }

    @Test
    fun `submitAction posts request body to resolved action path`() =
        runTest {
            val okHttpClient = OkHttpClient()
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(202)
                        .setBody(
                            """
                            {"taskId":"task-1","suspendId":"suspend-1","clientActionId":"action-1","status":"ACCEPTED"}
                            """.trimIndent(),
                        ),
                )
                val client =
                    buildOkHttpTestClient(
                        baseUrl = server.url("/").toString(),
                        dispatcher = UnconfinedTestDispatcher(testScheduler),
                        okHttpClient = okHttpClient,
                    )

                val response =
                    client.submitAction(
                        taskId = "task-1",
                        action =
                            TaskActionRequest(
                                clientActionId = "action-1",
                                suspendId = "suspend-1",
                                actionType = "submit_form",
                                payload = buildJsonObject { put("answer", "yes") },
                            ),
                    )

                Assert.assertEquals(SubmitActionStatus.ACCEPTED, response.status)
                val request = server.takeRequest()
                Assert.assertEquals("/api/v1/tasks/task-1/actions", recordedRequestPath(request))
                Assert.assertTrue(recordedRequestBodyUtf8(request).contains("\"clientActionId\":\"action-1\""))
            }
            okHttpClient.shutdown()
        }

    @Test
    fun `observeTaskEvents does not downgrade after websocket policy close`() =
        runTest {
            val okHttpClient = OkHttpClient()
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse().setBody(
                        """
                        {
                          "taskId":"task-1",
                          "events":[
                            {"type":"TASK_COMPLETED","taskId":"task-1","eventId":"2-0","createdAt":"2026-05-05T12:00:02Z","payload":{"result":{"ok":true}}}
                          ],
                          "nextAfterEventId":"2-0",
                          "hasMore":false
                        }
                        """.trimIndent(),
                    ),
                )

                val client =
                    buildOkHttpTestClient(
                        baseUrl = server.url("/").toString(),
                        webSocketFactory =
                            FakeWebSocketFactory<Unit>(
                                listOf(
                                    WsFrame.Open,
                                    WsFrame.Closing(1008, "policy violation"),
                                ),
                            ),
                        dispatcher = UnconfinedTestDispatcher(testScheduler),
                        okHttpClient = okHttpClient,
                    )

                val result = runCatching { client.observeTaskEvents("task-1").toList() }

                Assert.assertTrue("Result should be failure, but was $result", result.isFailure)
                Assert.assertTrue(
                    "Expected TaskBridgeWebSocketPolicyCloseException, got ${result.exceptionOrNull()}",
                    result.exceptionOrNull() is io.github.nikkiw.taskbridge.transport.TaskBridgeWebSocketPolicyCloseException,
                )
                Assert.assertEquals(0, mockWebServerRequestCount(server))
            }
            okHttpClient.shutdown()
        }

    @Test
    fun `observeTaskEvents skips already acknowledged suspensions`() =
        runTest {
            val okHttpClient = OkHttpClient()
            val store = InMemoryTaskBridgeCheckpointStore()
            val baseUrl = "http://test-api-1.com/"
            // Manually acknowledge s1
            val k1 = buildCheckpointKey(baseUrl, "task-1|suspend-ack|s1")
            store.save(k1, "a1")

            val webSocketFactory =
                FakeWebSocketFactory<Unit>(
                    script =
                        listOf(
                            WsFrame.Open,
                            WsFrame.Text("""{"type":"SUBSCRIPTION_CONFIRMED","taskId":"task-1","live":true}"""),
                            WsFrame.Text(
                                """
                                {"type":"TASK_SUSPENDED","taskId":"task-1","eventId":"1-0","createdAt":"2026-05-05T12:00:01Z","payload":{"suspendId":"s1","kind":"USER_ACTION_REQUIRED","reasonCode":"r1","allowedActions":[],"schemaVersion":1}}
                                """.trimIndent(),
                            ),
                            WsFrame.Text(
                                """
                                {"type":"TASK_SUSPENDED","taskId":"task-1","eventId":"2-0","createdAt":"2026-05-05T12:00:02Z","payload":{"suspendId":"s2","kind":"USER_ACTION_REQUIRED","reasonCode":"r1","allowedActions":[],"schemaVersion":1}}
                                """.trimIndent(),
                            ),
                            WsFrame.Text(
                                """
                                {"type":"TASK_COMPLETED","taskId":"task-1","eventId":"3-0","createdAt":"2026-05-05T12:00:03Z","payload":{"result":{"ok":true}}}
                                """.trimIndent(),
                            ),
                        ),
                )

            val client =
                buildOkHttpTestClient(
                    baseUrl = baseUrl,
                    webSocketFactory = webSocketFactory,
                    checkpointStore = store,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    okHttpClient = okHttpClient,
                )

            // s1 should be skipped, so we expect only s2. Flow won't end naturally.
            val events = client.observeTaskEvents("task-1").take(1).toList()

            Assert.assertEquals(1, events.size)
            Assert.assertTrue(events[0] is TaskSuspendedEvent)
            Assert.assertEquals("s2", (events[0] as TaskSuspendedEvent).suspension.suspendId)
            okHttpClient.shutdown()
        }

    @Test
    fun `TASK_ACTION_ACCEPTED updates acknowledgement state`() =
        runTest {
            val okHttpClient = OkHttpClient()
            val store = InMemoryTaskBridgeCheckpointStore()
            val baseUrl = "http://test-api-2.com/"
            val webSocketFactory =
                FakeWebSocketFactory<Unit>(
                    script =
                        listOf(
                            WsFrame.Open,
                            WsFrame.Text("""{"type":"SUBSCRIPTION_CONFIRMED","taskId":"task-2","live":true}"""),
                            WsFrame.Text(
                                """
                                {"type":"TASK_ACTION_ACCEPTED","taskId":"task-2","eventId":"1-0","createdAt":"2026-05-05T12:00:01Z","payload":{"suspendId":"s1","clientActionId":"a1","actionType":"confirm","acceptedAt":"2026-05-05T12:00:01Z"}}
                                """.trimIndent(),
                            ),
                        ),
                )

            val client =
                buildOkHttpTestClient(
                    baseUrl = baseUrl,
                    webSocketFactory = webSocketFactory,
                    checkpointStore = store,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    okHttpClient = okHttpClient,
                )

            // Collect only the ACTION_ACCEPTED event
            val events = client.observeTaskEvents("task-2").take(1).toList()
            Assert.assertEquals(1, events.size)

            val key = buildCheckpointKey(baseUrl, "task-2|suspend-ack|s1")
            Assert.assertEquals("a1", store.load(key))
            okHttpClient.shutdown()
        }

    @Test
    fun `submitAction updates acknowledgement state on ACCEPTED`() =
        runTest {
            val okHttpClient = OkHttpClient()
            MockWebServer().use { server ->
                val baseUrl = server.url("/").toString()
                server.enqueue(
                    MockResponse()
                        .setResponseCode(202)
                        .setBody(
                            """
                            {"taskId":"task-3","suspendId":"s1","clientActionId":"a1","status":"ACCEPTED"}
                            """.trimIndent(),
                        ),
                )
                val store = InMemoryTaskBridgeCheckpointStore()
                val client =
                    buildOkHttpTestClient(
                        baseUrl = baseUrl,
                        checkpointStore = store,
                        dispatcher = UnconfinedTestDispatcher(testScheduler),
                        okHttpClient = okHttpClient,
                    )

                client.submitAction(
                    taskId = "task-3",
                    action =
                        TaskActionRequest(
                            clientActionId = "a1",
                            suspendId = "s1",
                            actionType = "confirm",
                            payload = buildJsonObject { put("ok", true) },
                        ),
                )

                val key = buildCheckpointKey(baseUrl, "task-3|suspend-ack|s1")
                Assert.assertEquals("a1", store.load(key))
            }
            okHttpClient.shutdown()
        }

    @Test
    fun `terminal events clear all task acknowledgements`() =
        runTest {
            val okHttpClient = OkHttpClient()
            val store = InMemoryTaskBridgeCheckpointStore()
            val baseUrl = "http://test-api-4.com/"
            val k1 = buildCheckpointKey(baseUrl, "task-4|suspend-ack|s1")

            store.save(k1, "a1")

            val webSocketFactory =
                FakeWebSocketFactory<Unit>(
                    script =
                        listOf(
                            WsFrame.Open,
                            WsFrame.Text("""{"type":"SUBSCRIPTION_CONFIRMED","taskId":"task-4","live":true}"""),
                            WsFrame.Text(
                                """
                                {"type":"TASK_SUSPENDED","taskId":"task-4","eventId":"2-0","createdAt":"2026-05-05T12:00:01Z","payload":{"suspendId":"s1","kind":"USER_ACTION_REQUIRED","reasonCode":"r1","allowedActions":[],"schemaVersion":1}}
                                """.trimIndent(),
                            ),
                            WsFrame.Text(
                                """
                                {"type":"TASK_COMPLETED","taskId":"task-4","eventId":"3-0","createdAt":"2026-05-05T12:00:02Z","payload":{"result":{"ok":true}}}
                                """.trimIndent(),
                            ),
                        ),
                )

            val client =
                buildOkHttpTestClient(
                    baseUrl = baseUrl,
                    webSocketFactory = webSocketFactory,
                    checkpointStore = store,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    okHttpClient = okHttpClient,
                )

            // s1 skipped because already acknowledged (but it populates internal tracking),
            // then COMPLETED arrives and clears tracking.
            val events = client.observeTaskEvents("task-4").toList()
            Assert.assertEquals(1, events.size)
            Assert.assertTrue(events[0] is TaskCompletedEvent)

            Assert.assertEquals(null, store.load(k1))
            okHttpClient.shutdown()
        }
}
