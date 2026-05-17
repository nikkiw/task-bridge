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

import io.github.nikkiw.taskbridge.internal.HTTP_UNAUTHORIZED
import io.github.nikkiw.taskbridge.internal.requireValidTaskBridgeTaskId
import io.github.nikkiw.taskbridge.model.TaskEvent
import io.github.nikkiw.taskbridge.model.UnknownTaskEvent
import io.github.nikkiw.taskbridge.model.isTerminal
import io.github.nikkiw.taskbridge.model.toTaskEvent
import io.github.nikkiw.taskbridge.policy.TaskBridgeHttpStatusException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.IOException

/**
 * Transport orchestrator for one observed task stream.
 *
 * Lifecycle:
 * 1. Resolve the initial watermark from the explicit `afterEventId`, then persistent checkpoint storage.
 * 2. Observe via the configured fallback strategy: WebSocket first, then SSE, then long-polling.
 * 3. Deduplicate by `eventId` across transport failover and replay suffixes.
 * 4. Persist the latest delivered `eventId` after each successful emission.
 * 5. Clear the checkpoint after a terminal task event.
 *
 * The collector helpers for WebSocket and SSE intentionally live in separate files so this type can focus on
 * orchestration rules: sequencing, retry boundaries, deduplication, and checkpoint ownership.
 */
@Suppress("TooManyFunctions") // Split into helpers to maintain cohesive orchestration.
class TaskBridgeStreamTransport<Ctx>(
    private val baseUrl: String,
    private val context: Ctx,
    private val deps: TaskBridgeStreamTransportDeps<Ctx>,
    checkpoint: TaskBridgeCheckpointBinding<Ctx>,
    private val options: TaskBridgeStreamTransportOptions<Ctx> = TaskBridgeStreamTransportOptions(),
) {
    private val streamConfig = options.streamConfig
    private val eventListener = options.eventListener
    private val checkpointStore = checkpoint.store
    private val checkpointKeyFactory = checkpoint.resolveKeyFactory(baseUrl)

    /**
     * Returns a [Flow] of events for a specific task.
     */
    fun observeTaskEvents(
        taskId: String,
        afterEventId: String? = null,
    ): Flow<TaskEvent> =
        channelFlow {
            runObservationLoop(taskId, afterEventId)
        }.flowOn(options.dispatcher)

    // Suppress: State-machine style loop: each throw maps to a distinct terminal/error path.
    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    private suspend fun ProducerScope<TaskEvent>.runObservationLoop(
        taskId: String,
        afterEventId: String?,
    ) {
        requireValidTaskBridgeTaskId(taskId)
        val checkpointKey = checkpointKeyFactory(context, taskId)
        val seen = RecentEventIds()
        val loop = ObservationLoopVars(0, afterEventId ?: checkpointStore.load(checkpointKey))
        val backoffCtx =
            TransportBackoffContext(checkpointStore, deps.failureClassifier, deps.retryPolicy)

        try {
            while (currentCoroutineContext().isActive) {
                try {
                    if (runTransportCycleOnce(taskId, checkpointKey, seen, loop)) {
                        close()
                        return
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TaskBridgeStreamProcessingException) {
                    // Fatal processing error (e.g. checkpoint failure) -> terminate flow
                    throw e.cause
                } catch (e: TaskBridgeHttpStatusException) {
                    // If it's a 401 and we're here, it means transport-level retries were exhausted
                    // and it was deemed fatal. Or it's a non-retryable error from polling.
                    if (e.statusCode == HTTP_UNAUTHORIZED) {
                        throw io.github.nikkiw.taskbridge.policy
                            .TaskBridgeAuthException(cause = e)
                    }
                    if (!backoffCtx.failureClassifier.isRetryable(e)) {
                        throw e
                    }
                    applyTransportBackoff(backoffCtx, checkpointKey, loop, e)
                } catch (e: IOException) {
                    applyTransportBackoff(backoffCtx, checkpointKey, loop, e)
                } catch (e: SerializationException) {
                    applyTransportBackoff(backoffCtx, checkpointKey, loop, e)
                } finally {
                    loop.malformedPayloadCount = 0
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            close(e)
        }
    }

    private suspend fun ProducerScope<TaskEvent>.runTransportCycleOnce(
        taskId: String,
        checkpointKey: String,
        seen: RecentEventIds,
        loop: ObservationLoopVars,
    ): Boolean {
        val cycleState =
            when (streamConfig.fallbackStrategy) {
                FallbackStrategy.FAST_CYCLE ->
                    runFastCycle(taskId, loop.watermark, checkpointKey, seen, loop)

                FallbackStrategy.PROGRESSIVE_STICKY ->
                    runProgressiveSticky(taskId, loop.watermark, checkpointKey, seen, loop)
            }

        loop.watermark = cycleState.watermark

        if (cycleState.terminal || !currentCoroutineContext().isActive) {
            return true
        }

        val throwableToPropagate = cycleState.throwableToPropagateOrNull()
        if (throwableToPropagate != null) {
            throw throwableToPropagate
        }

        loop.attempt = 0
        return false
    }

    // Suppress:  Guard clauses keep transport error propagation readable.
    @Suppress("ReturnCount")
    private fun ObservationState.throwableToPropagateOrNull(): Throwable? {
        if (!failed) return null

        val error = lastThrowable ?: return null

        return if (isFatalError()) {
            error.toTransportPublicException()
        } else {
            error.takeIf { it is Exception }
        }
    }

    private fun Throwable.toTransportPublicException(): Throwable =
        when {
            this is TaskBridgeHttpStatusException &&
                statusCode == HTTP_UNAUTHORIZED -> {
                io.github.nikkiw.taskbridge.policy
                    .TaskBridgeAuthException(cause = this)
            }

            else -> this
        }

    private suspend fun ProducerScope<TaskEvent>.runFastCycle(
        taskId: String,
        watermark: String?,
        checkpointKey: String,
        seen: RecentEventIds,
        loop: ObservationLoopVars,
    ): ObservationState {
        var state = observeWebSocketPhase(taskId, watermark, checkpointKey, seen, loop)
        if (state.isFatalError()) throw state.lastThrowable!!

        if (!state.terminal && currentCoroutineContext().isActive) {
            eventListener?.onFallbackToSse(context, taskId)
            state = observeSsePhase(taskId, state.watermark, checkpointKey, seen, loop)
            if (state.isFatalError()) throw state.lastThrowable!!
        }
        if (!state.terminal && currentCoroutineContext().isActive) {
            eventListener?.onFallbackToPolling(context, taskId)
            state = observePollingPhase(taskId, state.watermark, checkpointKey, seen)
        }
        return state
    }

    private suspend fun ProducerScope<TaskEvent>.runProgressiveSticky(
        taskId: String,
        watermark: String?,
        checkpointKey: String,
        seen: RecentEventIds,
        loop: ObservationLoopVars,
    ): ObservationState {
        return run {
            val wsOutcome =
                observeWithTransportRetries(streamConfig.wsMaxAttempts, watermark) { wm ->
                    observeWebSocketPhase(taskId, wm, checkpointKey, seen, loop)
                }
            if (wsOutcome.terminal) {
                return@run wsOutcome
            }
            if (wsOutcome.isFatalError()) throw wsOutcome.lastThrowable!!

            eventListener?.onFallbackToSse(context, taskId)
            val sseOutcome =
                observeWithTransportRetries(
                    streamConfig.sseMaxAttempts,
                    wsOutcome.watermark,
                ) { wm ->
                    observeSsePhase(taskId, wm, checkpointKey, seen, loop)
                }
            if (sseOutcome.terminal) {
                return@run sseOutcome
            }
            if (sseOutcome.isFatalError()) throw sseOutcome.lastThrowable!!

            eventListener?.onFallbackToPolling(context, taskId)
            observePollingPhase(taskId, sseOutcome.watermark, checkpointKey, seen)
        }
    }

    private suspend fun ProducerScope<TaskEvent>.observeWithTransportRetries(
        maxAttempts: Int,
        startWatermark: String?,
        transport: suspend (wm: String?) -> ObservationState,
    ): ObservationState {
        var wm = startWatermark
        var attempt = 0
        var lastError: Throwable? = null
        while (currentCoroutineContext().isActive && attempt < maxAttempts) {
            val state = transport(wm)
            wm = state.watermark
            val resolved =
                when {
                    state.terminal || !currentCoroutineContext().isActive -> state
                    !state.failed -> ObservationState(watermark = wm, terminal = false)
                    else -> {
                        lastError = state.lastThrowable
                        null
                    }
                }
            if (resolved != null) {
                return resolved
            }

            if (lastError != null && !isTransportLevelRetryable(lastError)) {
                break
            }

            attempt += 1
            if (attempt < maxAttempts) {
                delay(deps.retryPolicy.nextDelayMs(attempt - 1))
            }
        }
        return ObservationState(
            watermark = wm,
            terminal = false,
            failed = true,
            lastThrowable = lastError,
        )
    }

    private fun isTransportLevelRetryable(e: Throwable): Boolean = deps.failureClassifier.isRetryable(e)

    private suspend fun ProducerScope<TaskEvent>.emitObservedEvent(
        checkpointKey: String,
        seen: RecentEventIds,
        event: TaskEvent,
    ): Boolean {
        val terminal = event.isTerminal()
        if (!seen.markSeen(event.eventId)) return terminal

        @Suppress("TooGenericExceptionCaught")
        return try {
            send(event)
            checkpointStore.save(checkpointKey, event.eventId)
            if (terminal) checkpointStore.clear(checkpointKey)
            terminal
        } catch (e: Exception) {
            handleProcessingError(e)
        }
    }

    // Suppress: Catching generic exceptions to wrap all transport errors into observation failures.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun ProducerScope<TaskEvent>.observeWebSocketPhase(
        taskId: String,
        watermark: String?,
        checkpointKey: String,
        seen: RecentEventIds,
        loop: ObservationLoopVars,
    ): ObservationState {
        var nextWatermark = watermark
        val collectCtx =
            StreamCollectContext(
                baseUrl = baseUrl,
                context = context,
                deps = deps,
                options = options,
                taskId = taskId,
            )
        return try {
            var terminal = false
            withContext(options.dispatcher) {
                collectWebSocketEvents(
                    collectCtx,
                    lastEventId = watermark,
                ) { item ->
                    when (item) {
                        is TransportInbound.Event -> {
                            nextWatermark = item.event.eventId
                            terminal = emitObservedEvent(checkpointKey, seen, item.event)
                        }

                        is TransportInbound.MalformedPayload -> {
                            onMalformedPayload(
                                collectCtx,
                                loop,
                                TaskBridgeTransportSource.WebSocket,
                                item.error,
                            )
                        }
                    }
                }
            }
            ObservationState(
                watermark = nextWatermark,
                terminal = terminal,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            wsTransportObservationFailure(
                TransportPhaseFailureContext(
                    eventListener,
                    context,
                    taskId,
                    nextWatermark,
                    false,
                ),
                e,
            )
        }
    }

    // Suppress: Catching generic exceptions to wrap all transport errors into observation failures.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun ProducerScope<TaskEvent>.observeSsePhase(
        taskId: String,
        watermark: String?,
        checkpointKey: String,
        seen: RecentEventIds,
        loop: ObservationLoopVars,
    ): ObservationState {
        var nextWatermark = watermark
        val collectCtx =
            StreamCollectContext(
                baseUrl = baseUrl,
                context = context,
                deps = deps,
                options = options,
                taskId = taskId,
            )
        return try {
            var terminal = false
            withContext(options.dispatcher) {
                collectSseEvents(
                    collectCtx,
                    lastEventId = watermark,
                ) { item ->
                    when (item) {
                        is TransportInbound.Event -> {
                            val ev = item.event
                            if (ev is UnknownTaskEvent && ev.wireType == "HEARTBEAT") {
                                // Match WebSocket: heartbeats are transport liveness only (see
                                // decodeWebSocketTransportEvent). Do not advance checkpoint / public flow.
                                return@collectSseEvents
                            }
                            eventListener?.onSseEvent(
                                context,
                                taskId,
                                ev.wireType,
                                ev.eventId,
                            )
                            nextWatermark = ev.eventId
                            terminal = emitObservedEvent(checkpointKey, seen, ev)
                        }

                        is TransportInbound.MalformedPayload -> {
                            onMalformedPayload(
                                collectCtx,
                                loop,
                                TaskBridgeTransportSource.Sse,
                                item.error,
                            )
                        }
                    }
                }
            }
            ObservationState(
                watermark = nextWatermark,
                terminal = terminal,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sseTransportObservationFailure(
                TransportPhaseFailureContext(
                    eventListener,
                    context,
                    taskId,
                    nextWatermark,
                    false,
                ),
                e,
            )
        }
    }

    // Suppress: Catching all exceptions to safely exit the polling loop and return a failed state.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun ProducerScope<TaskEvent>.observePollingPhase(
        taskId: String,
        watermark: String?,
        checkpointKey: String,
        seen: RecentEventIds,
    ): ObservationState {
        var nextWatermark = watermark
        return try {
            while (currentCoroutineContext().isActive) {
                val response =
                    deps.pollEventsClient.pollEvents(
                        context = context,
                        url =
                            httpBaseToHttpUrl(
                                baseUrl,
                                deps.routeResolver.pollEventsPath(context, taskId),
                            ),
                        afterEventId = nextWatermark,
                        waitTimeoutMs = streamConfig.pollWaitTimeoutMs,
                        maxEvents = streamConfig.pollMaxEvents,
                    )
                if (response.events.isEmpty()) {
                    delay(streamConfig.pollEmptyBackoffMs)
                    continue
                }
                response.rawJson?.let { json ->
                    eventListener?.onRawPayload(
                        context,
                        taskId,
                        TaskBridgeTransportSource.Polling,
                        json,
                    )
                    eventListener?.onPollRawResponse(context, taskId, json)
                }
                for (rawEvent in response.events) {
                    val event =
                        try {
                            rawEvent.toTaskEvent()
                        } catch (e: SerializationException) {
                            eventListener?.onMalformedWirePayload(
                                context,
                                taskId,
                                TaskBridgeTransportSource.Polling,
                                e,
                            )
                            continue
                        }
                    eventListener?.onPollEvent(context, taskId, event.wireType, event.eventId)
                    nextWatermark = event.eventId
                    if (emitObservedEvent(checkpointKey, seen, event)) {
                        return ObservationState(
                            watermark = nextWatermark,
                            terminal = true,
                        )
                    }
                }
            }
            ObservationState(
                watermark = nextWatermark,
                terminal = false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val res =
                ObservationState(
                    watermark = nextWatermark,
                    terminal = false,
                    failed = true,
                    lastThrowable = e,
                )
            res
        }
    }
}
