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

import io.github.nikkiw.taskbridge.model.CancelTaskBody
import io.github.nikkiw.taskbridge.model.CancelTaskResponse
import io.github.nikkiw.taskbridge.model.PollEventsResponse
import io.github.nikkiw.taskbridge.model.SubmitActionResponse
import io.github.nikkiw.taskbridge.model.TaskActionRequest
import io.github.nikkiw.taskbridge.model.TaskBridgeMultipartAttachment
import io.github.nikkiw.taskbridge.model.TaskCreateJsonRequest
import io.github.nikkiw.taskbridge.model.TaskCreatedResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TaskBridgeHttpApiTest {
    @Test
    fun `asPollEventsClient delegates to pollEvents`() =
        runTest {
            val expectedResponse = PollEventsResponse("t1", emptyList(), "e1", false)
            var capturedParams: Map<String, Any?>? = null

            val api =
                object : TaskBridgeHttpApi<Unit> {
                    override suspend fun createTaskJson(
                        context: Unit,
                        url: String,
                        body: TaskCreateJsonRequest,
                    ): TaskCreatedResponse = error("Not used")

                    override suspend fun createTaskMultipart(
                        context: Unit,
                        url: String,
                        clientRequestId: String,
                        taskType: String,
                        inputJson: String?,
                        metadataJson: String?,
                        attachments: List<TaskBridgeMultipartAttachment>,
                    ): TaskCreatedResponse = error("Not used")

                    override suspend fun cancelTask(
                        context: Unit,
                        url: String,
                        body: CancelTaskBody?,
                    ): CancelTaskResponse = error("Not used")

                    override suspend fun submitAction(
                        context: Unit,
                        url: String,
                        body: TaskActionRequest,
                    ): SubmitActionResponse = error("Not used")

                    override suspend fun pollEvents(
                        context: Unit,
                        url: String,
                        afterEventId: String?,
                        waitTimeoutMs: Int,
                        maxEvents: Int,
                    ): PollEventsResponse {
                        capturedParams =
                            mapOf(
                                "url" to url,
                                "afterEventId" to afterEventId,
                                "waitTimeoutMs" to waitTimeoutMs,
                                "maxEvents" to maxEvents,
                            )
                        return expectedResponse
                    }
                }

            val pollClient = api.asPollEventsClient()
            val actualResponse = pollClient.pollEvents(Unit, "http://test.com", "prev-id", 5000, 10)

            assertEquals(expectedResponse, actualResponse)
            assertNotNull(capturedParams)
            val params = capturedParams!!
            assertEquals("http://test.com", params["url"])
            assertEquals("prev-id", params["afterEventId"])
            assertEquals(5000, params["waitTimeoutMs"])
            assertEquals(10, params["maxEvents"])
        }

    @Test
    fun `TaskBridgeTransportFactoryConfig has correct defaults`() {
        val config = TaskBridgeTransportFactoryConfig<Unit>(baseUrl = "https://api.example.com")

        assertEquals("https://api.example.com", config.baseUrl)
        assertNull(config.authHeaderProvider)
        assertNotNull(config.streamTransport)
        assertNotNull(config.json)
    }
}
