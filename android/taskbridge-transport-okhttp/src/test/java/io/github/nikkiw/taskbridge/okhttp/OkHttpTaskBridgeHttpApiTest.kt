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

import io.github.nikkiw.taskbridge.model.CancelTaskBody
import io.github.nikkiw.taskbridge.model.TaskActionRequest
import io.github.nikkiw.taskbridge.model.TaskBridgeMultipartAttachment
import io.github.nikkiw.taskbridge.model.TaskCreateJsonRequest
import io.github.nikkiw.taskbridge.policy.TaskBridgeHttpStatusException
import io.github.nikkiw.taskbridge.transport.TaskBridgeHttpApi
import io.github.nikkiw.taskbridge.transport.taskBridgeJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpTaskBridgeHttpApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: TaskBridgeHttpApi<Unit>

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        api =
            OkHttpTaskBridgeHttpApi.create<Unit>(
                baseUrl = server.url("/").toString(),
                okHttpClient = OkHttpClient(),
                json = taskBridgeJson(),
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `createTaskJson sends POST with correct body`() =
        runTest {
            server.enqueue(
                MockResponse().setBody(
                    """{"taskId":"task-1","status":"ACCEPTED","clientRequestId":"req-1","deduplicated":false}""",
                ),
            )

            val request =
                TaskCreateJsonRequest(
                    clientRequestId = "req-1",
                    taskType = "test.task",
                    input = buildJsonObject { put("key", "value") },
                )

            val response = api.createTaskJson(Unit, server.url("/create").toString(), request)

            assertEquals("task-1", response.taskId)
            val recordedRequest = server.takeRequest()
            assertEquals("POST", recordedRequest.method)
            assertEquals("/create", recordedRequest.path)
            assertTrue(recordedRequest.body.readUtf8().contains("test.task"))
        }

    @Test
    fun `createTaskMultipart sends multipart request`() =
        runTest {
            server.enqueue(
                MockResponse().setBody(
                    """{"taskId":"task-1","status":"ACCEPTED","clientRequestId":"req-1","deduplicated":false}""",
                ),
            )

            val attachment =
                TaskBridgeMultipartAttachment(
                    fieldName = "file",
                    fileName = "test.txt",
                    contentType = "text/plain",
                    content = "hello world".encodeToByteArray(),
                )

            val response =
                api.createTaskMultipart(
                    context = Unit,
                    url = server.url("/create-multipart").toString(),
                    clientRequestId = "req-1",
                    taskType = "test.multipart",
                    inputJson = """{"foo":"bar"}""",
                    metadataJson = """{"meta":"data"}""",
                    attachments = listOf(attachment),
                )

            assertEquals("task-1", response.taskId)
            val recordedRequest = server.takeRequest()
            assertEquals("POST", recordedRequest.method)
            assertTrue(recordedRequest.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
            val body = recordedRequest.body.readUtf8()
            assertTrue(body.contains("test.multipart"))
            assertTrue(body.contains("hello world"))
            assertTrue(body.contains("test.txt"))
        }

    @Test
    fun `pollEvents sends GET with query params`() =
        runTest {
            server.enqueue(
                MockResponse().setBody(
                    """{"taskId":"task-1","events":[],"nextAfterEventId":"1-0","hasMore":false}""",
                ),
            )

            val response =
                api.pollEvents(
                    context = Unit,
                    url = server.url("/poll").toString(),
                    afterEventId = "0-0",
                    waitTimeoutMs = 5000,
                    maxEvents = 10,
                )

            assertEquals("task-1", response.taskId)
            val recordedRequest = server.takeRequest()
            assertEquals("GET", recordedRequest.method)
            val path = recordedRequest.path!!
            assertTrue(path.contains("afterEventId=0-0"))
            assertTrue(path.contains("waitTimeoutMs=5000"))
            assertTrue(path.contains("maxEvents=10"))
        }

    @Test
    fun `cancelTask sends POST with optional body`() =
        runTest {
            server.enqueue(
                MockResponse().setBody(
                    """{"taskId":"task-1","status":"CANCELLATION_REQUESTED"}""",
                ),
            )

            val response =
                api.cancelTask(
                    context = Unit,
                    url = server.url("/cancel").toString(),
                    body = CancelTaskBody(reason = "test"),
                )

            assertEquals("task-1", response.taskId)
            val recordedRequest = server.takeRequest()
            assertEquals("POST", recordedRequest.method)
            assertTrue(recordedRequest.body.readUtf8().contains("test"))
        }

    @Test
    fun `submitAction sends POST with action body`() =
        runTest {
            server.enqueue(
                MockResponse().setBody(
                    """{"taskId":"task-1","suspendId":"s-1","clientActionId":"a-1","status":"ACCEPTED"}""",
                ),
            )

            val request =
                TaskActionRequest(
                    clientActionId = "a-1",
                    suspendId = "s-1",
                    actionType = "confirm",
                    payload = buildJsonObject { put("ok", true) },
                )

            val response =
                api.submitAction(
                    context = Unit,
                    url = server.url("/action").toString(),
                    body = request,
                )

            assertEquals("task-1", response.taskId)
            val recordedRequest = server.takeRequest()
            assertEquals("POST", recordedRequest.method)
            assertTrue(recordedRequest.body.readUtf8().contains("confirm"))
        }

    @Test
    fun `wrapHttpErrors converts HttpException to TaskBridgeHttpStatusException`() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(404).setBody("Not Found"),
            )

            val result =
                runCatching {
                    api.pollEvents(
                        context = Unit,
                        url = server.url("/poll").toString(),
                        afterEventId = null,
                        waitTimeoutMs = 0,
                        maxEvents = 1,
                    )
                }

            assertTrue(result.isFailure)
            val exception = result.exceptionOrNull()
            assertTrue(exception is TaskBridgeHttpStatusException)
            assertEquals(404, (exception as TaskBridgeHttpStatusException).statusCode)
        }
}
