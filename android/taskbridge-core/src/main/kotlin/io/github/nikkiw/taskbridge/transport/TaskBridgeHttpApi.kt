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

import io.github.nikkiw.taskbridge.model.CancelTaskBody
import io.github.nikkiw.taskbridge.model.CancelTaskResponse
import io.github.nikkiw.taskbridge.model.PollEventsResponse
import io.github.nikkiw.taskbridge.model.SubmitActionResponse
import io.github.nikkiw.taskbridge.model.TaskActionRequest
import io.github.nikkiw.taskbridge.model.TaskBridgeMultipartAttachment
import io.github.nikkiw.taskbridge.model.TaskCreateJsonRequest
import io.github.nikkiw.taskbridge.model.TaskCreatedResponse
import kotlinx.serialization.json.Json

/**
 * Abstraction for HTTP operations required by the SDK.
 *
 * This interface decouples the core SDK from specific HTTP clients like OkHttp or Ktor.
 */
interface TaskBridgeHttpApi<Ctx> {
    /**
     * Executes a POST request to create a task with JSON body.
     */
    suspend fun createTaskJson(
        context: Ctx,
        url: String,
        body: TaskCreateJsonRequest,
    ): TaskCreatedResponse

    /**
     * Executes a POST request to create a task with multipart/form-data.
     */
    @Suppress("LongParameterList")
    suspend fun createTaskMultipart(
        context: Ctx,
        url: String,
        clientRequestId: String,
        taskType: String,
        inputJson: String?,
        metadataJson: String?,
        attachments: List<TaskBridgeMultipartAttachment>,
    ): TaskCreatedResponse

    /**
     * Executes a GET request to poll for events using long-polling.
     */
    suspend fun pollEvents(
        context: Ctx,
        url: String,
        afterEventId: String?,
        waitTimeoutMs: Int,
        maxEvents: Int,
    ): PollEventsResponse

    /**
     * Executes a POST request to cancel a task.
     */
    suspend fun cancelTask(
        context: Ctx,
        url: String,
        body: CancelTaskBody? = null,
    ): CancelTaskResponse

    /**
     * Executes a POST request to submit a task action.
     */
    suspend fun submitAction(
        context: Ctx,
        url: String,
        body: TaskActionRequest,
    ): SubmitActionResponse
}

/**
 * Functional interface for long-polling event requests.
 */
fun interface TaskBridgePollEventsClient<Ctx> {
    /**
     * Performs a single long-polling request.
     */
    suspend fun pollEvents(
        context: Ctx,
        url: String,
        afterEventId: String?,
        waitTimeoutMs: Int,
        maxEvents: Int,
    ): PollEventsResponse
}

internal fun <Ctx> TaskBridgeHttpApi<Ctx>.asPollEventsClient(): TaskBridgePollEventsClient<Ctx> =
    TaskBridgePollEventsClient { context, url, afterEventId, waitTimeoutMs, maxEvents ->
        pollEvents(
            context = context,
            url = url,
            afterEventId = afterEventId,
            waitTimeoutMs = waitTimeoutMs,
            maxEvents = maxEvents,
        )
    }

/**
 * Configuration passed to [TaskBridgeTransportFactory].
 *
 * @property baseUrl The base URL of the backend.
 * @property authHeaderProvider Provider for authorization headers.
 * @property streamTransport Stream configuration defaults.
 * @property json JSON serializer.
 */
data class TaskBridgeTransportFactoryConfig<Ctx>(
    val baseUrl: String,
    val authHeaderProvider: (suspend (context: Ctx, forceRefresh: Boolean) -> String?)? = null,
    val streamTransport: TaskBridgeStreamTransportConfig = TaskBridgeStreamTransportConfig(),
    val json: Json = taskBridgeJson(),
)

/**
 * A bundle of transport components returned by [TaskBridgeTransportFactory].
 *
 * @property http HTTP API implementation.
 * @property webSocketFactory WebSocket session factory.
 * @property sseSessionFactory SSE session factory.
 */
data class TaskBridgeTransportBundle<Ctx>(
    val http: TaskBridgeHttpApi<Ctx>,
    val webSocketFactory: WebSocketSessionFactory<Ctx>,
    val sseSessionFactory: SseSessionFactory<Ctx>,
) {
    /**
     * Returns a [TaskBridgePollEventsClient] based on the current [http] implementation.
     */
    internal fun asPollEventsClient(): TaskBridgePollEventsClient<Ctx> = http.asPollEventsClient()
}

/**
 * Factory for creating transport components.
 *
 * This is the primary extension point for providing custom networking implementations.
 */
fun interface TaskBridgeTransportFactory<Ctx> {
    /**
     * Creates a [TaskBridgeTransportBundle] based on the provided configuration.
     */
    fun create(config: TaskBridgeTransportFactoryConfig<Ctx>): TaskBridgeTransportBundle<Ctx>
}
