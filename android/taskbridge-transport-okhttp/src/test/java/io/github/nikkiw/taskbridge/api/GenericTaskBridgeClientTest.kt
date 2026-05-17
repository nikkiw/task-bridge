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

import io.github.nikkiw.taskbridge.model.TaskCreateJsonRequest
import io.github.nikkiw.taskbridge.okhttp.OkHttpTaskBridgeTransportConfig
import io.github.nikkiw.taskbridge.okhttp.OkHttpTaskBridgeTransportFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert
import org.junit.Test

class GenericTaskBridgeClientTest {
    data class MyContext(
        val sessionId: String,
    )

    @Test
    fun `client with custom context passes it to route resolver and auth provider`() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse().setBody(
                        """{"taskId":"t1","status":"ACCEPTED","clientRequestId":"req-1","deduplicated":false}""",
                    ),
                )

                val resolver =
                    object : TaskBridgeRouteResolver<MyContext> {
                        override fun createTaskPath(context: MyContext) = "api/v1/sessions/${context.sessionId}/tasks"

                        override fun pollEventsPath(
                            context: MyContext,
                            taskId: String,
                        ) = ""

                        override fun cancelTaskPath(
                            context: MyContext,
                            taskId: String,
                        ) = ""

                        override fun submitActionPath(
                            context: MyContext,
                            taskId: String,
                        ) = ""

                        override fun webSocketPath(context: MyContext) = ""

                        override fun streamEventsPath(
                            context: MyContext,
                            taskId: String,
                        ) = ""
                    }

                var capturedAuthContext: MyContext? = null
                val config =
                    TaskBridgeConfig<MyContext>(
                        baseUrl = server.url("/").toString(),
                        transportFactory =
                            OkHttpTaskBridgeTransportFactory<MyContext>(
                                OkHttpTaskBridgeTransportConfig(OkHttpClient()),
                            ),
                        routeResolver = resolver,
                        authHeaderProvider = { ctx, _ ->
                            capturedAuthContext = ctx
                            "Bearer session-${ctx.sessionId}"
                        },
                    )
                val client = TaskBridgeClient.create(config)

                val context = MyContext("s123")
                client.startTaskJson(
                    context,
                    TaskCreateJsonRequest(
                        clientRequestId = "req-1",
                        taskType = "test",
                        input = buildJsonObject { put("k", "v") },
                    ),
                )

                val request = server.takeRequest()
                Assert.assertEquals("/api/v1/sessions/s123/tasks", request.path)
                Assert.assertEquals("Bearer session-s123", request.getHeader("Authorization"))
                Assert.assertEquals(context, capturedAuthContext)
            }
        }
}
