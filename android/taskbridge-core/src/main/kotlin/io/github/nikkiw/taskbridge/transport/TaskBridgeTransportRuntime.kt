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

import io.github.nikkiw.taskbridge.checkpoint.TaskBridgeCheckpointStore
import io.github.nikkiw.taskbridge.internal.HTTP_UNAUTHORIZED
import io.github.nikkiw.taskbridge.model.TaskEvent
import io.github.nikkiw.taskbridge.policy.NoOpTransportRetryGate
import io.github.nikkiw.taskbridge.policy.TaskBridgeFailureClassifier
import io.github.nikkiw.taskbridge.policy.TaskBridgeHttpStatusException
import io.github.nikkiw.taskbridge.policy.TaskBridgeRetryPolicy
import io.github.nikkiw.taskbridge.policy.TransportRetryGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

private const val DEFAULT_RECENT_EVENT_IDS_CAPACITY = 256

/**
 * Keeps only the most recent emitted ids so long-lived streams do not leak memory while still deduping
 * replayed suffixes during transport failover or checkpoint persistence races.
 */
internal class RecentEventIds(
    private val capacity: Int = DEFAULT_RECENT_EVENT_IDS_CAPACITY,
) {
    private val order = ArrayDeque<String>(capacity)
    private val seen = LinkedHashSet<String>(capacity)

    init {
        require(capacity > 0) { "capacity must be > 0" }
    }

    fun markSeen(eventId: String): Boolean {
        if (!seen.add(eventId)) {
            return false
        }
        order.addLast(eventId)
        if (order.size > capacity) {
            seen.remove(order.removeFirst())
        }
        return true
    }
}

internal sealed class WsInbound {
    data class Message(
        val text: String,
    ) : WsInbound()

    data class StreamEnd(
        val error: Throwable?,
    ) : WsInbound()
}

/** Inbound decoded SSE item: event batch item or stream end. */
internal sealed class SseInbound {
    data class Event(
        val event: TaskEvent,
    ) : SseInbound()

    data class Malformed(
        val error: Throwable,
    ) : SseInbound()

    data class StreamEnd(
        val error: Throwable?,
    ) : SseInbound()
}

internal sealed class TransportInbound {
    data class Event(
        val event: TaskEvent,
    ) : TransportInbound()

    data class MalformedPayload(
        val error: Throwable,
    ) : TransportInbound()
}

internal data class ObservationLoopVars(
    var attempt: Int,
    var watermark: String?,
    var malformedPayloadCount: Int = 0,
)

internal fun handleProcessingError(e: Exception): Nothing =
    throw when (e) {
        is CancellationException -> e
        is TaskBridgeStreamProcessingException -> e
        else -> TaskBridgeStreamProcessingException(e)
    }

/**
 * A wire payload could not be parsed as [TaskEvent].
 */
internal fun <Ctx> onMalformedPayload(
    collectCtx: StreamCollectContext<Ctx>,
    loop: ObservationLoopVars,
    source: TaskBridgeTransportSource,
    error: Throwable,
) {
    collectCtx.eventListener?.onMalformedWirePayload(
        collectCtx.context,
        loop.watermark ?: "unknown",
        source,
        error,
    )
    loop.malformedPayloadCount++
    if (loop.malformedPayloadCount > collectCtx.streamConfig.maxMalformedPayloadsBeforeFailure) {
        throw TaskBridgeStreamProcessingException(
            IOException("Too many malformed payloads ($source) in session", error),
        )
    }
}

/**
 * Groups parameters for transport collectors so the stream orchestration layer can stay focused on sequencing and
 * checkpoint semantics instead of constructor plumbing.
 */
internal data class StreamCollectContext<Ctx>(
    val baseUrl: String,
    val context: Ctx,
    val deps: TaskBridgeStreamTransportDeps<Ctx>,
    val options: TaskBridgeStreamTransportOptions<Ctx>,
    val taskId: String,
) {
    val streamConfig: TaskBridgeStreamTransportConfig get() = options.streamConfig
    val json get() = options.json
    val eventListener: TaskBridgeTransportEventListener<Ctx>? get() = options.eventListener
}

internal data class TransportBackoffContext(
    val checkpointStore: TaskBridgeCheckpointStore,
    val failureClassifier: TaskBridgeFailureClassifier,
    val retryPolicy: TaskBridgeRetryPolicy,
    val retryGate: TransportRetryGate = NoOpTransportRetryGate,
)

internal data class ObservationState(
    val watermark: String?,
    val terminal: Boolean,
    val failed: Boolean = false,
    val lastThrowable: Throwable? = null,
)

internal fun ObservationState.isFatalError(): Boolean {
    val e = lastThrowable ?: return false
    return (e is TaskBridgeHttpStatusException && e.statusCode == HTTP_UNAUTHORIZED) ||
        e is TaskBridgeWebSocketPolicyCloseException ||
        e is io.github.nikkiw.taskbridge.policy.TaskBridgeAuthException ||
        e is IllegalArgumentException ||
        e is TaskBridgeStreamProcessingException
}

internal fun ObservationState.isFatalAuthError(): Boolean {
    val e = lastThrowable ?: return false
    return e is TaskBridgeHttpStatusException && e.statusCode == HTTP_UNAUTHORIZED
}

internal data class TransportPhaseFailureContext<Ctx>(
    val eventListener: TaskBridgeTransportEventListener<Ctx>?,
    val context: Ctx,
    val taskId: String,
    val nextWatermark: String?,
    val terminal: Boolean,
)

internal fun <Ctx> wsTransportObservationFailure(
    ctx: TransportPhaseFailureContext<Ctx>,
    e: Throwable,
): ObservationState {
    ctx.eventListener?.onWsSetupFailed(ctx.context, ctx.taskId, e)
    return ObservationState(
        watermark = ctx.nextWatermark,
        terminal = ctx.terminal,
        failed = true,
        lastThrowable = e,
    )
}

internal fun <Ctx> sseTransportObservationFailure(
    ctx: TransportPhaseFailureContext<Ctx>,
    e: Throwable,
): ObservationState {
    ctx.eventListener?.onSseSetupFailed(ctx.context, ctx.taskId, e)
    return ObservationState(
        watermark = ctx.nextWatermark,
        terminal = ctx.terminal,
        failed = true,
        lastThrowable = e,
    )
}

internal suspend fun applyTransportBackoff(
    ctx: TransportBackoffContext,
    checkpointKey: String,
    loop: ObservationLoopVars,
    e: Throwable,
) {
    if (!currentCoroutineContext().isActive || !ctx.failureClassifier.isRetryable(e)) {
        throw e
    }
    ctx.retryGate.awaitRetryAllowed()
    delay(ctx.retryPolicy.nextDelayMs(loop.attempt))
    loop.attempt += 1
    loop.watermark = ctx.checkpointStore.load(checkpointKey) ?: loop.watermark
}

internal class StreamEndSignal {
    private val endOnce = AtomicBoolean(false)

    fun tryMarkEnded(): Boolean = endOnce.compareAndSet(false, true)
}
