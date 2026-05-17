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

/**
 * Abstraction for a WebSocket session.
 */
interface TaskBridgeWebSocketSession {
    /** Sends a text message over the WebSocket. */
    fun send(text: String): Boolean

    /** Gracefully closes the WebSocket connection. */
    fun close(
        code: Int,
        reason: String?,
    ): Boolean

    /** Immediately terminates the WebSocket connection. */
    fun cancel()
}

/**
 * Listener for WebSocket lifecycle and message events.
 */
interface TaskBridgeWebSocketListener {
    /** Called when the connection is established. */
    fun onOpen(session: TaskBridgeWebSocketSession)

    /** Called when a text message is received. */
    fun onMessage(text: String)

    /** Called when the server is closing the connection. */
    fun onClosing(
        code: Int,
        reason: String,
    )

    /** Called when the connection fails due to an error. */
    fun onFailure(throwable: Throwable)
}

/**
 * Factory for creating [TaskBridgeWebSocketSession]s.
 */
fun interface WebSocketSessionFactory<Ctx> {
    /**
     * Opens a new WebSocket connection to the given URL.
     */
    suspend fun open(
        context: Ctx,
        url: String,
        listener: TaskBridgeWebSocketListener,
    ): TaskBridgeWebSocketSession
}
