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
 * Identifies the transport mechanism that delivered a task event.
 *
 * @property value Internal numeric representation.
 */
@JvmInline
value class TaskBridgeTransportSource(
    val value: Int,
) {
    /** @suppress */
    companion object {
        /** Event received over a WebSocket connection. */
        val WebSocket = TaskBridgeTransportSource(0)

        /** Event received over a Server-Sent Events (SSE) connection. */
        val Sse = TaskBridgeTransportSource(1)

        /** Event received via HTTP long-polling. */
        val Polling = TaskBridgeTransportSource(2)

        /** Source is unknown or unmapped. */
        val Unknown = TaskBridgeTransportSource(-1)
    }

    override fun toString(): String =
        when (this) {
            WebSocket -> "ws"
            Sse -> "sse"
            Polling -> "poll"
            else -> "unknown($value)"
        }
}
