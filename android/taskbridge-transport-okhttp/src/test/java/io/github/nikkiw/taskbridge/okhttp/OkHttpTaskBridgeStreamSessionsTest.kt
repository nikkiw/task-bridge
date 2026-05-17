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

import io.github.nikkiw.taskbridge.transport.TaskBridgeSseListener
import io.github.nikkiw.taskbridge.transport.TaskBridgeWebSocketListener
import io.github.nikkiw.taskbridge.transport.TaskBridgeWebSocketSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class OkHttpTaskBridgeStreamSessionsTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setup() {
        server = MockWebServer()
        client =
            OkHttpClient
                .Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `OkHttpWebSocketSessionFactory connects and receives messages`() =
        runTest {
            val openDeferred = CompletableDeferred<TaskBridgeWebSocketSession>()
            val messageDeferred = CompletableDeferred<String>()

            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : okhttp3.WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response,
                        ) {
                            webSocket.send("hello from server")
                        }
                    },
                ),
            )

            val factory = OkHttpWebSocketSessionFactory<Unit>(client)
            val session =
                factory.open(
                    context = Unit,
                    url = server.url("/ws").toString(),
                    listener =
                        object : TaskBridgeWebSocketListener {
                            override fun onOpen(session: TaskBridgeWebSocketSession) {
                                openDeferred.complete(session)
                            }

                            override fun onMessage(text: String) {
                                messageDeferred.complete(text)
                            }

                            override fun onClosing(
                                code: Int,
                                reason: String,
                            ) {
                                // No-op
                            }

                            override fun onFailure(throwable: Throwable) {
                                openDeferred.completeExceptionally(throwable)
                            }
                        },
                )

            withContext(Dispatchers.Default) {
                withTimeout(5000) {
                    val openedSession = openDeferred.await()
                    assertEquals(session, openedSession)
                    assertEquals("hello from server", messageDeferred.await())
                }
            }

            session.send("hello from client")
            val request = server.takeRequest()
            assertEquals("/ws", request.path)
        }

    @Test
    fun `OkHttpWebSocketSessionFactory handles failure`() =
        runTest {
            val failureDeferred = CompletableDeferred<Throwable>()

            server.enqueue(MockResponse().setResponseCode(500))

            val factory = OkHttpWebSocketSessionFactory<Unit>(client)
            factory.open(
                context = Unit,
                url = server.url("/ws").toString(),
                listener =
                    object : TaskBridgeWebSocketListener {
                        override fun onOpen(session: TaskBridgeWebSocketSession) {
                            // No-op
                        }

                        override fun onMessage(text: String) {
                            // No-op
                        }

                        override fun onClosing(
                            code: Int,
                            reason: String,
                        ) {
                            // No-op
                        }

                        override fun onFailure(throwable: Throwable) {
                            failureDeferred.complete(throwable)
                        }
                    },
            )

            withContext(Dispatchers.Default) {
                withTimeout(5000) {
                    val failure = failureDeferred.await()
                    assertNotNull(failure)
                }
            }
        }

    @Test
    fun `OkHttpSseSessionFactory connects and receives events`() =
        runTest {
            val openDeferred = CompletableDeferred<Unit>()
            val eventDeferred = CompletableDeferred<String>()

            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: some data\n\n"),
            )

            val factory = OkHttpSseSessionFactory<Unit>(client)
            val session =
                factory.open(
                    context = Unit,
                    url = server.url("/sse").toString(),
                    lastEventId = "last-id",
                    listener =
                        object : TaskBridgeSseListener {
                            override fun onOpen() {
                                openDeferred.complete(Unit)
                            }

                            override fun onEvent(
                                id: String?,
                                type: String?,
                                data: String,
                            ) {
                                eventDeferred.complete(data)
                            }

                            override fun onFailure(throwable: Throwable) {
                                openDeferred.completeExceptionally(throwable)
                            }

                            override fun onClosed() {
                                // No-op
                            }
                        },
                )

            withContext(Dispatchers.Default) {
                withTimeout(5000) {
                    openDeferred.await()
                    assertEquals("some data", eventDeferred.await())
                }
            }

            val request = server.takeRequest()
            assertEquals("/sse", request.path)
            assertEquals("text/event-stream", request.getHeader("Accept"))
            assertEquals("last-id", request.getHeader("Last-Event-ID"))

            session.cancel()
        }

    @Test
    fun `OkHttpSseSessionFactory handles failure`() =
        runTest {
            val failureDeferred = CompletableDeferred<Throwable>()

            server.enqueue(MockResponse().setResponseCode(404))

            val factory = OkHttpSseSessionFactory<Unit>(client)
            factory.open(
                context = Unit,
                url = server.url("/sse").toString(),
                lastEventId = null,
                listener =
                    object : TaskBridgeSseListener {
                        override fun onOpen() {
                            // No-op
                        }

                        override fun onEvent(
                            id: String?,
                            type: String?,
                            data: String,
                        ) {
                            // No-op
                        }

                        override fun onFailure(throwable: Throwable) {
                            failureDeferred.complete(throwable)
                        }

                        override fun onClosed() {
                            // No-op
                        }
                    },
            )

            withContext(Dispatchers.Default) {
                withTimeout(5000) {
                    val failure = failureDeferred.await()
                    assertNotNull(failure)
                }
            }
        }
}
