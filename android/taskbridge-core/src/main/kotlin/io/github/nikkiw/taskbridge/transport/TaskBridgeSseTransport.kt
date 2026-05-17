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
 * Strategy for falling back between different transport mechanisms.
 */
enum class FallbackStrategy {
    /** Quickly cycle through transports until one works. */
    FAST_CYCLE,

    /** Try transports in order and stick to the best available one. */
    PROGRESSIVE_STICKY,
}

/**
 * Abstraction for a Server-Sent Events (SSE) session.
 */
interface SseSession {
    /** Immediately terminates the SSE connection. */
    fun cancel()
}

/**
 * Listener for SSE lifecycle and event data.
 */
interface TaskBridgeSseListener {
    /** Called when the connection is established. */
    fun onOpen()

    /** Called when an event is received. */
    fun onEvent(
        id: String?,
        type: String?,
        data: String,
    )

    /** Called when the connection fails due to an error. */
    fun onFailure(throwable: Throwable)

    /** Called when the connection is closed. */
    fun onClosed()
}

/**
 * Factory for creating [SseSession]s.
 */
fun interface SseSessionFactory<Ctx> {
    /**
     * Opens a new SSE connection to the given URL.
     */
    suspend fun open(
        context: Ctx,
        url: String,
        lastEventId: String?,
        listener: TaskBridgeSseListener,
    ): SseSession
}
