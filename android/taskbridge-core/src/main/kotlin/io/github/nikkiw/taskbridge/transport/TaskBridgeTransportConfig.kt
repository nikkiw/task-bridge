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

import io.github.nikkiw.taskbridge.api.TaskBridgeRouteResolver
import io.github.nikkiw.taskbridge.checkpoint.TaskBridgeCheckpointStore
import io.github.nikkiw.taskbridge.checkpoint.buildCheckpointKey
import io.github.nikkiw.taskbridge.policy.NoOpTransportRetryGate
import io.github.nikkiw.taskbridge.policy.TaskBridgeFailureClassifier
import io.github.nikkiw.taskbridge.policy.TaskBridgeRetryPolicy
import io.github.nikkiw.taskbridge.policy.TransportRetryGate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json

/**
 * Network and resilience dependencies for [TaskBridgeStreamTransport].
 */
data class TaskBridgeStreamTransportDeps<Ctx>(
    /** Client for long-polling. */
    val pollEventsClient: TaskBridgePollEventsClient<Ctx>,
    /** Factory for WebSocket sessions. */
    val webSocketFactory: WebSocketSessionFactory<Ctx>,
    /** Factory for SSE sessions. */
    val sseSessionFactory: SseSessionFactory<Ctx>,
    /** Resolver for API routes. */
    val routeResolver: TaskBridgeRouteResolver<Ctx>,
    /** Logic to classify errors as retryable or fatal. */
    val failureClassifier: TaskBridgeFailureClassifier,
    /** Policy for retry backoff. */
    val retryPolicy: TaskBridgeRetryPolicy,
    /** Gate for suspending retry attempts. */
    val retryGate: TransportRetryGate = NoOpTransportRetryGate,
)

/**
 * Configuration options for the event streaming transport.
 *
 * @property fallbackStrategy Strategy for falling back between WebSocket, SSE, and Polling.
 * @property wsMaxAttempts Maximum connection attempts for WebSocket before falling back.
 * @property sseMaxAttempts Maximum connection attempts for SSE before falling back.
 * @property pollWaitTimeoutMs Time in ms for the server to wait for new events during long-polling.
 * @property pollMaxEvents Maximum number of events to fetch in a single poll request.
 * @property pollEmptyBackoffMs Delay after an empty poll batch to avoid tight loops.
 * @property transportOpenTimeoutMs Max time to wait for a transport (WS/SSE) to become open.
 * @property livenessTimeoutMs Max time of inactivity on an open connection before assuming it's dead.
 * @property wsIncomingChannelCapacity Buffer capacity for incoming WebSocket messages.
 * @property sseIncomingChannelCapacity Buffer capacity for incoming SSE events.
 * @property maxMalformedPayloadsBeforeFailure Max allowed invalid payloads in a session before failing.
 */
data class TaskBridgeStreamTransportConfig(
    val fallbackStrategy: FallbackStrategy = FallbackStrategy.PROGRESSIVE_STICKY,
    val wsMaxAttempts: Int = 3,
    val sseMaxAttempts: Int = 3,
    val pollWaitTimeoutMs: Int = DEFAULT_POLL_WAIT_TIMEOUT_MS,
    val pollMaxEvents: Int = DEFAULT_POLL_MAX_EVENTS,
    val pollEmptyBackoffMs: Long = DEFAULT_POLL_EMPTY_BACKOFF_MS,
    val transportOpenTimeoutMs: Long = DEFAULT_TRANSPORT_OPEN_TIMEOUT_MS,
    val livenessTimeoutMs: Long = DEFAULT_LIVENESS_TIMEOUT_MS,
    val wsIncomingChannelCapacity: Int = DEFAULT_WS_INCOMING_CHANNEL_CAPACITY,
    val sseIncomingChannelCapacity: Int = DEFAULT_SSE_INCOMING_CHANNEL_CAPACITY,
    val maxMalformedPayloadsBeforeFailure: Int = DEFAULT_MAX_MALFORMED_PAYLOADS,
) {
    init {
        require(wsIncomingChannelCapacity >= 0) { "wsIncomingChannelCapacity must be >= 0" }
        require(sseIncomingChannelCapacity >= 0) { "sseIncomingChannelCapacity must be >= 0" }
        require(maxMalformedPayloadsBeforeFailure >= 0) { "maxMalformedPayloadsBeforeFailure must be >= 0" }
    }

    internal companion object {
        const val DEFAULT_POLL_WAIT_TIMEOUT_MS = 25_000
        const val DEFAULT_POLL_MAX_EVENTS = 100
        const val DEFAULT_POLL_EMPTY_BACKOFF_MS = 500L
        const val DEFAULT_TRANSPORT_OPEN_TIMEOUT_MS = 30_000L
        const val DEFAULT_LIVENESS_TIMEOUT_MS = 60_000L
        const val DEFAULT_WS_INCOMING_CHANNEL_CAPACITY = 64
        const val DEFAULT_SSE_INCOMING_CHANNEL_CAPACITY = 64
        const val DEFAULT_MAX_MALFORMED_PAYLOADS = 3
    }
}

/**
 * Encapsulates runtime options for [TaskBridgeStreamTransport].
 */
data class TaskBridgeStreamTransportOptions<Ctx>(
    /** Resilience and timing configuration. */
    val streamConfig: TaskBridgeStreamTransportConfig = TaskBridgeStreamTransportConfig(),
    /** Optional listener for internal transport events. */
    val eventListener: TaskBridgeTransportEventListener<Ctx>? = null,
    /** JSON serializer instance. */
    val json: Json = taskBridgeJson(),
    /** Dispatcher for flow execution. */
    val dispatcher: CoroutineDispatcher = Dispatchers.IO,
)

/**
 * Checkpoint storage and keying for stream resume.
 */
data class TaskBridgeCheckpointBinding<Ctx>(
    /** The storage implementation. */
    val store: TaskBridgeCheckpointStore,
    /** Optional namespace for keys. */
    val namespace: String? = null,
    /** Factory for generating storage keys. */
    val keyFactory: ((Ctx, taskId: String) -> String)? = null,
)

internal fun <Ctx> TaskBridgeCheckpointBinding<Ctx>.resolveKeyFactory(baseUrl: String): (Ctx, String) -> String =
    keyFactory ?: { _, taskId -> buildCheckpointKey(baseUrl, taskId, namespace) }
