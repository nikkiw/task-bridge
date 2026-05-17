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

import io.github.nikkiw.taskbridge.model.RawTaskEventEnvelope
import io.github.nikkiw.taskbridge.model.TaskEvent
import io.github.nikkiw.taskbridge.model.isTerminal
import io.github.nikkiw.taskbridge.model.toTaskEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

private const val TASK_BRIDGE_WS_CLOSE_NORMAL = 1000
private const val WS_CLOSE_POLICY_VIOLATION = 1008
private const val WS_CLOSE_AUTH_POLICY_START = 4400
private const val WS_CLOSE_AUTH_POLICY_END = 4499

/**
 * Exception thrown when the WebSocket is closed by the server due to a policy violation
 * or authentication error.
 *
 * @param code The WebSocket close code.
 * @param reason The reason for closing the connection provided by the server.
 */
class TaskBridgeWebSocketPolicyCloseException(
    code: Int,
    reason: String,
) : IllegalStateException("TaskBridge websocket closed by server with code=$code reason=$reason")

@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun <Ctx> collectWebSocketEvents(
    collectCtx: StreamCollectContext<Ctx>,
    lastEventId: String?,
    onInbound: suspend (TransportInbound) -> Unit,
) = coroutineScope {
    val url =
        httpBaseToWebSocketUrl(
            collectCtx.baseUrl,
            collectCtx.deps.routeResolver.webSocketPath(collectCtx.context),
        )
    val incoming = Channel<WsInbound>(capacity = maxOf(1, collectCtx.streamConfig.wsIncomingChannelCapacity))
    val streamEnd = StreamEndSignal()

    fun signalStreamEnd(error: Throwable?) {
        if (streamEnd.tryMarkEnded()) {
            incoming.trySendBlocking(WsInbound.StreamEnd(error))
        }
    }

    val opened = CompletableDeferred<Unit>()
    val consumer =
        async {
            while (true) {
                val item =
                    select<WsInbound?> {
                        incoming.onReceiveCatching { result: ChannelResult<WsInbound> ->
                            result.getOrNull()
                        }
                        onTimeout(collectCtx.streamConfig.livenessTimeoutMs) {
                            throw IOException("TaskBridge ws liveness timed out after ${collectCtx.streamConfig.livenessTimeoutMs} ms")
                        }
                    }

                if (item == null) {
                    return@async
                }

                when (item) {
                    is WsInbound.Message -> {
                        collectCtx.eventListener?.onRawPayload(
                            collectCtx.context,
                            collectCtx.taskId,
                            TaskBridgeTransportSource.WebSocket,
                            item.text,
                        )
                        collectCtx.eventListener?.onWsRawMessage(
                            collectCtx.context,
                            collectCtx.taskId,
                            item.text,
                        )
                        val decodeResult =
                            decodeWebSocketTransportEvent(
                                collectCtx.json,
                                item.text,
                            )
                        when (decodeResult) {
                            is DecodeResult.Event -> {
                                onInbound(TransportInbound.Event(decodeResult.event))
                                if (decodeResult.event.isTerminal()) {
                                    return@async
                                }
                            }
                            is DecodeResult.Malformed -> {
                                onInbound(TransportInbound.MalformedPayload(decodeResult.error))
                            }
                            is DecodeResult.Ignore -> {}
                        }
                    }

                    is WsInbound.StreamEnd -> {
                        item.error?.let { throw it }
                        return@async
                    }
                }
            }
        }

    val listener =
        taskBridgeWebSocketListener(
            incoming = incoming,
            opened = opened,
            signalStreamEnd = ::signalStreamEnd,
            subscribeJson = { webSocket ->
                webSocket.send(
                    buildTaskBridgeSubscribeJson(collectCtx.json, collectCtx.taskId, lastEventId),
                )
            },
            onClosingSocket = { webSocket -> webSocket.close(TASK_BRIDGE_WS_CLOSE_NORMAL, null) },
        )

    var webSocket: TaskBridgeWebSocketSession? = null
    try {
        webSocket = collectCtx.deps.webSocketFactory.open(collectCtx.context, url, listener)
        awaitTransportOpen(opened, "ws", collectCtx.streamConfig.transportOpenTimeoutMs)
        consumer.await()
    } finally {
        incoming.close()
        webSocket?.cancel()
        consumer.cancel()
    }
}

internal suspend fun awaitTransportOpen(
    opened: CompletableDeferred<Unit>,
    transport: String,
    openTimeoutMs: Long,
) {
    try {
        withTimeout(openTimeoutMs) {
            opened.await()
        }
    } catch (_: TimeoutCancellationException) {
        throw IOException("TaskBridge $transport open timed out after $openTimeoutMs ms")
    }
}

private fun buildTaskBridgeSubscribeJson(
    json: Json,
    taskId: String,
    lastEventId: String?,
): String {
    val payload =
        buildJsonObject {
            put("action", JsonPrimitive("subscribe"))
            put("taskId", JsonPrimitive(taskId))
            if (lastEventId != null) {
                put("lastEventId", JsonPrimitive(lastEventId))
            }
        }
    return json.encodeToString(JsonObject.serializer(), payload)
}

internal sealed class DecodeResult {
    data class Event(
        val event: TaskEvent,
    ) : DecodeResult()

    data class Malformed(
        val error: Throwable,
    ) : DecodeResult()

    object Ignore : DecodeResult()
}

private fun decodeWebSocketTransportEvent(
    json: Json,
    text: String,
): DecodeResult {
    val objectNode =
        try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: SerializationException) {
            return DecodeResult.Malformed(e)
        }

    return when (objectNode["type"]?.jsonPrimitive?.contentOrNull) {
        "SUBSCRIPTION_CONFIRMED",
        "HEARTBEAT",
        -> DecodeResult.Ignore

        else ->
            try {
                val event =
                    json
                        .decodeFromJsonElement(
                            RawTaskEventEnvelope.serializer(),
                            objectNode,
                        ).toTaskEvent()
                DecodeResult.Event(event)
            } catch (e: SerializationException) {
                DecodeResult.Malformed(e)
            }
    }
}

private fun taskBridgeWebSocketListener(
    incoming: Channel<WsInbound>,
    opened: CompletableDeferred<Unit>,
    signalStreamEnd: (Throwable?) -> Unit,
    subscribeJson: (TaskBridgeWebSocketSession) -> Unit,
    onClosingSocket: (TaskBridgeWebSocketSession) -> Unit,
): TaskBridgeWebSocketListener =
    object : TaskBridgeWebSocketListener {
        private var webSocketSession: TaskBridgeWebSocketSession? = null

        override fun onFailure(throwable: Throwable) {
            val cause =
                if (throwable is java.util.concurrent.CancellationException) {
                    // If it's a cancellation from coroutine, we don't want to treat it as transport failure
                    // in terms of error signaling, though it will terminate the loop anyway.
                    throwable
                } else {
                    throwable
                }
            if (!opened.isCompleted) {
                opened.completeExceptionally(cause)
            }
            signalStreamEnd(cause)
        }

        override fun onMessage(text: String) {
            incoming.trySendBlocking(WsInbound.Message(text))
        }

        override fun onClosing(
            code: Int,
            reason: String,
        ) {
            val closeError =
                if (code == WS_CLOSE_POLICY_VIOLATION || code in WS_CLOSE_AUTH_POLICY_START..WS_CLOSE_AUTH_POLICY_END) {
                    TaskBridgeWebSocketPolicyCloseException(code, reason)
                } else {
                    null
                }
            signalStreamEnd(closeError)
            webSocketSession?.let(onClosingSocket)
        }

        override fun onOpen(session: TaskBridgeWebSocketSession) {
            webSocketSession = session
            opened.complete(Unit)
            try {
                subscribeJson(session)
            } catch (t: Throwable) {
                signalStreamEnd(t)
            }
        }
    }
