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
import io.github.nikkiw.taskbridge.model.isTerminal
import io.github.nikkiw.taskbridge.model.toTaskEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonObject
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun <Ctx> collectSseEvents(
    collectCtx: StreamCollectContext<Ctx>,
    lastEventId: String?,
    onInbound: suspend (TransportInbound) -> Unit,
) = coroutineScope {
    val url =
        httpBaseToHttpUrl(
            collectCtx.baseUrl,
            collectCtx.deps.routeResolver.streamEventsPath(collectCtx.context, collectCtx.taskId),
        )
    val incoming = Channel<SseInbound>(capacity = maxOf(1, collectCtx.streamConfig.sseIncomingChannelCapacity))
    val streamEnd = StreamEndSignal()

    fun signalStreamEnd(error: Throwable?) {
        if (streamEnd.tryMarkEnded()) {
            incoming.trySendBlocking(SseInbound.StreamEnd(error))
        }
    }

    val opened = CompletableDeferred<Unit>()
    val consumer =
        async {
            while (true) {
                val item =
                    select<SseInbound?> {
                        incoming.onReceiveCatching { result: ChannelResult<SseInbound> ->
                            result.getOrNull()
                        }
                        onTimeout(collectCtx.streamConfig.livenessTimeoutMs) {
                            throw IOException("TaskBridge sse liveness timed out after ${collectCtx.streamConfig.livenessTimeoutMs} ms")
                        }
                    }

                if (item == null) {
                    return@async
                }

                when (item) {
                    is SseInbound.Event -> {
                        onInbound(TransportInbound.Event(item.event))
                        if (item.event.isTerminal()) {
                            return@async
                        }
                    }

                    is SseInbound.Malformed -> {
                        onInbound(TransportInbound.MalformedPayload(item.error))
                    }

                    is SseInbound.StreamEnd -> {
                        item.error?.let { throw it }
                        return@async
                    }
                }
            }
        }

    val eventSource =
        collectCtx.deps.sseSessionFactory.open(
            context = collectCtx.context,
            url = url,
            lastEventId = lastEventId,
            listener =
                object : TaskBridgeSseListener {
                    override fun onOpen() {
                        opened.complete(Unit)
                    }

                    override fun onEvent(
                        id: String?,
                        type: String?,
                        data: String,
                    ) {
                        val rawSse = "id: $id\nevent: $type\ndata: $data\n\n"
                        collectCtx.eventListener?.onRawPayload(
                            collectCtx.context,
                            collectCtx.taskId,
                            TaskBridgeTransportSource.Sse,
                            rawSse,
                        )
                        collectCtx.eventListener?.onSseRawEvent(
                            collectCtx.context,
                            collectCtx.taskId,
                            id,
                            type,
                            data,
                        )
                        try {
                            val objectNode = collectCtx.json.parseToJsonElement(data).jsonObject
                            val event =
                                collectCtx.json
                                    .decodeFromJsonElement(
                                        RawTaskEventEnvelope.serializer(),
                                        objectNode,
                                    ).toTaskEvent()
                            incoming.trySendBlocking(SseInbound.Event(event))
                        } catch (e: SerializationException) {
                            // Signal malformed payload through a special event type in SseInbound if we want consistency,
                            // or just use onFailure.
                            // For simplicity and consistency with WS, let's add Malformed to SseInbound.
                            incoming.trySendBlocking(SseInbound.Malformed(e))
                        }
                    }

                    override fun onFailure(throwable: Throwable) {
                        val cause = throwable
                        if (!opened.isCompleted) {
                            opened.completeExceptionally(cause)
                        }
                        signalStreamEnd(cause)
                    }

                    override fun onClosed() {
                        signalStreamEnd(null)
                    }
                },
        )

    try {
        awaitTransportOpen(opened, "sse", collectCtx.streamConfig.transportOpenTimeoutMs)
        consumer.await()
    } finally {
        incoming.close()
        eventSource.cancel()
        consumer.cancel()
    }
}
