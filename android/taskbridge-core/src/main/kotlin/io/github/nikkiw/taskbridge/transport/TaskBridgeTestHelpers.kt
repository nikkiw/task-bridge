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

import java.io.IOException

/**
 * Utility for testing or simulating WebSocket failures.
 */
fun <Ctx> failingWebSocketFactory(message: String = "WebSocket disabled by interceptor"): WebSocketSessionFactory<Ctx> =
    WebSocketSessionFactory { _, _, listener ->
        listener.onFailure(IOException(message))
        object : TaskBridgeWebSocketSession {
            override fun send(text: String): Boolean = false

            override fun close(
                code: Int,
                reason: String?,
            ): Boolean = true

            override fun cancel() = Unit
        }
    }

/**
 * Utility for testing or simulating SSE failures.
 */
fun <Ctx> failingSseSessionFactory(message: String = "SSE disabled by interceptor"): SseSessionFactory<Ctx> =
    SseSessionFactory { _, _, _, _ ->
        throw IOException(message)
    }
