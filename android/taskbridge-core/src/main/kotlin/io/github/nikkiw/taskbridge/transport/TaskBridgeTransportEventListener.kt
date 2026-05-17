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

import io.github.nikkiw.taskbridge.model.TaskEvent

/**
 * Listener for internal transport lifecycle events and diagnostics.
 *
 * Useful for logging, debugging, or analytics.
 */
@Suppress("TooManyFunctions")
interface TaskBridgeTransportEventListener<Ctx> {
    /** Called when a WebSocket connection attempt fails. */
    fun onWsSetupFailed(
        context: Ctx,
        taskId: String,
        error: Throwable,
    ) {}

    /** Called when the transport falls back to long-polling. */
    fun onFallbackToPolling(
        context: Ctx,
        taskId: String,
    ) {}

    /** Called when an event is received via long-polling. */
    fun onPollEvent(
        context: Ctx,
        taskId: String,
        eventType: String,
        eventId: String,
    ) {}

    /** Called when the transport falls back to SSE. */
    fun onFallbackToSse(
        context: Ctx,
        taskId: String,
    ) {}

    /** Called when an SSE connection attempt fails. */
    fun onSseSetupFailed(
        context: Ctx,
        taskId: String,
        error: Throwable,
    ) {}

    /** Called when an event is received via SSE. */
    fun onSseEvent(
        context: Ctx,
        taskId: String,
        eventType: String,
        eventId: String,
    ) {}

    /**
     * Called for EVERY raw payload received, regardless of transport.
     * @param source The transport source (WebSocket, SSE, Polling).
     * @param payload The raw string received from the wire.
     */
    fun onRawPayload(
        context: Ctx,
        taskId: String,
        source: TaskBridgeTransportSource,
        payload: String,
    ) {}

    /** Called when a raw WebSocket text message is received. */
    fun onWsRawMessage(
        context: Ctx,
        taskId: String,
        text: String,
    ) {}

    /** Called when a raw SSE event is received. */
    fun onSseRawEvent(
        context: Ctx,
        taskId: String,
        id: String?,
        type: String?,
        data: String,
    ) {}

    /** Called when a long-polling response is received. [json] is the raw JSON string of the response. */
    fun onPollRawResponse(
        context: Ctx,
        taskId: String,
        json: String,
    ) {}

    /**
     * Called when a wire payload could not be parsed as [TaskEvent].
     */
    fun onMalformedWirePayload(
        context: Ctx,
        taskId: String,
        source: TaskBridgeTransportSource,
        error: Throwable,
    ) {
    }
}
