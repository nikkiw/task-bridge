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

import io.github.nikkiw.taskbridge.transport.TaskBridgeTransportBundle
import io.github.nikkiw.taskbridge.transport.TaskBridgeTransportFactory
import io.github.nikkiw.taskbridge.transport.TaskBridgeTransportFactoryConfig
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal data class TaskBridgeContextTag(
    val context: Any?,
)

/**
 * Configuration for the OkHttp-based transport.
 *
 * @property okHttpClient The [OkHttpClient] instance to use.
 * @property longPollReadTimeoutBufferMs Extra time added to [OkHttpClient] read timeout
 * to accommodate server-side long-polling wait time.
 */
data class OkHttpTaskBridgeTransportConfig(
    val okHttpClient: OkHttpClient,
    val longPollReadTimeoutBufferMs: Int = 5_000,
)

/**
 * [TaskBridgeTransportFactory] implementation using OkHttp.
 *
 * This implementation provides HTTP, WebSocket, and SSE support via OkHttp and Retrofit.
 */
class OkHttpTaskBridgeTransportFactory<Ctx>(
    private val config: OkHttpTaskBridgeTransportConfig,
) : TaskBridgeTransportFactory<Ctx> {
    override fun create(config: TaskBridgeTransportFactoryConfig<Ctx>): TaskBridgeTransportBundle<Ctx> {
        val httpClient = wrappedOkHttp(config)
        return TaskBridgeTransportBundle(
            http = OkHttpTaskBridgeHttpApi.create<Ctx>(config.baseUrl, httpClient, config.json),
            webSocketFactory = OkHttpWebSocketSessionFactory<Ctx>(httpClient),
            sseSessionFactory = OkHttpSseSessionFactory<Ctx>(httpClient),
        )
    }

    internal fun wrappedOkHttp(factoryConfig: TaskBridgeTransportFactoryConfig<Ctx>): OkHttpClient {
        val pollMinRead = factoryConfig.streamTransport.pollWaitTimeoutMs.toLong() + config.longPollReadTimeoutBufferMs
        val streamMinRead = factoryConfig.streamTransport.livenessTimeoutMs + config.longPollReadTimeoutBufferMs
        val minReadMs = maxOf(pollMinRead, streamMinRead).coerceAtLeast(1L)
        var builder = config.okHttpClient.newBuilder()
        if (config.okHttpClient.readTimeoutMillis < minReadMs) {
            builder = builder.readTimeout(minReadMs, TimeUnit.MILLISECONDS)
        }
        val auth = factoryConfig.authHeaderProvider
        val baseHost = factoryConfig.baseUrl.toHttpUrl().host

        if (auth != null) {
            builder =
                builder.authenticator { _, response ->
                    // If we've already tried to refresh the token, don't try again to prevent infinite loops.
                    var retryCount = 0
                    var prior = response.priorResponse
                    while (prior != null) {
                        retryCount++
                        prior = prior.priorResponse
                    }

                    if (retryCount >= 2) {
                        return@authenticator null // Give up after 2 retries
                    }

                    val request = response.request
                    if (request.url.host != baseHost) return@authenticator null

                    @Suppress("UNCHECKED_CAST")
                    val context = request.tag(TaskBridgeContextTag::class.java)?.context as? Ctx ?: return@authenticator null

                    val newToken = runBlocking { auth.invoke(context, true) }

                    if (newToken.isNullOrBlank()) {
                        null
                    } else {
                        request
                            .newBuilder()
                            .header("Authorization", newToken)
                            .build()
                    }
                }
        }

        return builder
            .addInterceptor { chain ->
                val request = chain.request()

                @Suppress("UNCHECKED_CAST")
                val context = request.tag(TaskBridgeContextTag::class.java)?.context as? Ctx
                val token =
                    if (request.url.host == baseHost && context != null && auth != null) {
                        runBlocking { auth.invoke(context, false) }
                    } else {
                        null
                    }

                val authenticatedRequest =
                    if (token.isNullOrBlank()) {
                        request
                    } else {
                        request
                            .newBuilder()
                            .header("Authorization", token)
                            .build()
                    }
                chain.proceed(authenticatedRequest)
            }.build()
    }
}
