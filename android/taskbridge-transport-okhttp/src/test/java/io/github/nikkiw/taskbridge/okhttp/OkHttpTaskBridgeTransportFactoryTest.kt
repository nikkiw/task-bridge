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

import io.github.nikkiw.taskbridge.transport.TaskBridgeStreamTransportConfig
import io.github.nikkiw.taskbridge.transport.TaskBridgeTransportFactoryConfig
import io.github.nikkiw.taskbridge.transport.taskBridgeJson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class OkHttpTaskBridgeTransportFactoryTest {
    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `create returns bundle with all components`() {
        val factory = OkHttpTaskBridgeTransportFactory<Unit>(OkHttpTaskBridgeTransportConfig(OkHttpClient()))
        val bundle =
            factory.create(
                TaskBridgeTransportFactoryConfig<Unit>(
                    baseUrl = "http://example.com",
                    json = taskBridgeJson(),
                    streamTransport = TaskBridgeStreamTransportConfig(),
                ),
            )

        assertNotNull(bundle.http)
        assertNotNull(bundle.webSocketFactory)
        assertNotNull(bundle.sseSessionFactory)
    }

    @Test
    fun `wrappedOkHttp adjusts read timeout based on poll and stream settings`() {
        val baseClient =
            OkHttpClient
                .Builder()
                .readTimeout(1, TimeUnit.SECONDS)
                .build()

        val transportConfig =
            OkHttpTaskBridgeTransportConfig(
                okHttpClient = baseClient,
                longPollReadTimeoutBufferMs = 1000,
            )
        val factory = OkHttpTaskBridgeTransportFactory<Unit>(transportConfig)

        val factoryConfig =
            TaskBridgeTransportFactoryConfig<Unit>(
                baseUrl = "http://example.com",
                json = taskBridgeJson(),
                streamTransport =
                    TaskBridgeStreamTransportConfig(
                        pollWaitTimeoutMs = 5000, // 5s + 1s buffer = 6s
                        livenessTimeoutMs = 2000, // 2s + 1s buffer = 3s
                    ),
            )

        val client = factory.wrappedOkHttp(factoryConfig)
        assertEquals(6000, client.readTimeoutMillis)
    }

    @Test
    fun `wrappedOkHttp does not decrease original read timeout`() {
        val baseClient =
            OkHttpClient
                .Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

        val transportConfig =
            OkHttpTaskBridgeTransportConfig(
                okHttpClient = baseClient,
                longPollReadTimeoutBufferMs = 1000,
            )
        val factory = OkHttpTaskBridgeTransportFactory<Unit>(transportConfig)

        val factoryConfig =
            TaskBridgeTransportFactoryConfig<Unit>(
                baseUrl = "http://example.com",
                json = taskBridgeJson(),
                streamTransport =
                    TaskBridgeStreamTransportConfig(
                        pollWaitTimeoutMs = 1000, // 1s + 1s buffer = 2s
                        livenessTimeoutMs = 1000, // 1s + 1s buffer = 2s
                    ),
            )

        val client = factory.wrappedOkHttp(factoryConfig)
        assertEquals(10000, client.readTimeoutMillis)
    }

    @Test
    fun `authorization header is added to same host requests`() {
        server.start()
        val baseUrl = server.url("/").toString()

        val factory =
            OkHttpTaskBridgeTransportFactory<Unit>(
                OkHttpTaskBridgeTransportConfig(OkHttpClient()),
            )

        val client =
            factory.wrappedOkHttp(
                TaskBridgeTransportFactoryConfig<Unit>(
                    baseUrl = baseUrl,
                    json = taskBridgeJson(),
                    authHeaderProvider = { _, _ -> "Bearer secret-token" },
                    streamTransport = TaskBridgeStreamTransportConfig(),
                ),
            )

        server.enqueue(MockResponse())
        client
            .newCall(
                Request
                    .Builder()
                    .url(baseUrl)
                    .tag(TaskBridgeContextTag::class.java, TaskBridgeContextTag(Unit))
                    .build(),
            ).execute()

        val request = server.takeRequest()
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
    }

    @Test
    fun `authorization header is NOT added to different host requests`() {
        server.start()
        val interceptorServerUrl = server.url("/").toString()
        val otherHost = "http://other-host.com/"

        val factory =
            OkHttpTaskBridgeTransportFactory<Unit>(
                OkHttpTaskBridgeTransportConfig(OkHttpClient()),
            )

        val client =
            factory.wrappedOkHttp(
                TaskBridgeTransportFactoryConfig<Unit>(
                    baseUrl = otherHost,
                    json = taskBridgeJson(),
                    authHeaderProvider = { _, _ -> "Bearer secret-token" },
                    streamTransport = TaskBridgeStreamTransportConfig(),
                ),
            )

        server.enqueue(MockResponse())
        client
            .newCall(
                Request
                    .Builder()
                    .url(interceptorServerUrl)
                    .tag(TaskBridgeContextTag::class.java, TaskBridgeContextTag(Unit))
                    .build(),
            ).execute()

        val request = server.takeRequest()
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun `authorization header handles null token from provider`() {
        server.start()
        val baseUrl = server.url("/").toString()

        val factory =
            OkHttpTaskBridgeTransportFactory<Unit>(
                OkHttpTaskBridgeTransportConfig(OkHttpClient()),
            )

        val client =
            factory.wrappedOkHttp(
                TaskBridgeTransportFactoryConfig<Unit>(
                    baseUrl = baseUrl,
                    json = taskBridgeJson(),
                    authHeaderProvider = { _, _ -> null },
                    streamTransport = TaskBridgeStreamTransportConfig(),
                ),
            )

        server.enqueue(MockResponse())
        client
            .newCall(
                Request
                    .Builder()
                    .url(baseUrl)
                    .tag(TaskBridgeContextTag::class.java, TaskBridgeContextTag(Unit))
                    .build(),
            ).execute()

        val request = server.takeRequest()
        assertNull(request.getHeader("Authorization"))
    }
}
