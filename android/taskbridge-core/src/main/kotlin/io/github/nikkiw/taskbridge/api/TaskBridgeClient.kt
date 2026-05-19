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
package io.github.nikkiw.taskbridge.api

import io.github.nikkiw.taskbridge.checkpoint.InMemoryTaskBridgeCheckpointStore
import io.github.nikkiw.taskbridge.checkpoint.TaskBridgeCheckpointStore
import io.github.nikkiw.taskbridge.internal.requireValidTaskBridgeTaskId
import io.github.nikkiw.taskbridge.model.CancelTaskBody
import io.github.nikkiw.taskbridge.model.CancelTaskResponse
import io.github.nikkiw.taskbridge.model.SubmitActionResponse
import io.github.nikkiw.taskbridge.model.SubmitActionStatus
import io.github.nikkiw.taskbridge.model.TaskActionAcceptedEvent
import io.github.nikkiw.taskbridge.model.TaskActionRequest
import io.github.nikkiw.taskbridge.model.TaskBridgeMultipartAttachment
import io.github.nikkiw.taskbridge.model.TaskCancelledEvent
import io.github.nikkiw.taskbridge.model.TaskCompletedEvent
import io.github.nikkiw.taskbridge.model.TaskCreateJsonRequest
import io.github.nikkiw.taskbridge.model.TaskCreatedResponse
import io.github.nikkiw.taskbridge.model.TaskEvent
import io.github.nikkiw.taskbridge.model.TaskFailedEvent
import io.github.nikkiw.taskbridge.model.TaskSuspendedEvent
import io.github.nikkiw.taskbridge.policy.DefaultTaskBridgeFailureClassifier
import io.github.nikkiw.taskbridge.policy.ExponentialBackoffTaskBridgeRetryPolicy
import io.github.nikkiw.taskbridge.policy.NoOpTransportRetryGate
import io.github.nikkiw.taskbridge.policy.TaskBridgeFailureClassifier
import io.github.nikkiw.taskbridge.policy.TaskBridgeRetryPolicy
import io.github.nikkiw.taskbridge.policy.TransportRetryGate
import io.github.nikkiw.taskbridge.transport.TaskBridgeCheckpointBinding
import io.github.nikkiw.taskbridge.transport.TaskBridgeHttpApi
import io.github.nikkiw.taskbridge.transport.TaskBridgeStreamTransport
import io.github.nikkiw.taskbridge.transport.TaskBridgeStreamTransportConfig
import io.github.nikkiw.taskbridge.transport.TaskBridgeStreamTransportDeps
import io.github.nikkiw.taskbridge.transport.TaskBridgeStreamTransportOptions
import io.github.nikkiw.taskbridge.transport.TaskBridgeTransportBundle
import io.github.nikkiw.taskbridge.transport.TaskBridgeTransportEventListener
import io.github.nikkiw.taskbridge.transport.TaskBridgeTransportFactory
import io.github.nikkiw.taskbridge.transport.TaskBridgeTransportFactoryConfig
import io.github.nikkiw.taskbridge.transport.httpBaseToHttpUrl
import io.github.nikkiw.taskbridge.transport.httpBaseToWebSocketUrl
import io.github.nikkiw.taskbridge.transport.taskBridgeJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Configuration for [TaskBridgeClient].
 *
 * @property baseUrl The base URL of the FastAPI backend (e.g., "https://api.example.com").
 * @property transportFactory Factory to create the underlying networking transport.
 * @property routeResolver Customizes path resolution for API endpoints.
 * @property authHeaderProvider Optional function to provide "Authorization" header value for requests.
 * The second parameter is `forceRefresh`, indicating a previous token was rejected with 401.
 * @property checkpointStore Store for persisting task event IDs to allow resuming after app restart.
 * @property checkpointNamespace Optional namespace to isolate checkpoints in the store.
 * @property failureClassifier Determines which network or protocol errors are retryable.
 * @property retryPolicy Strategy for backoff between retries.
 * @property streamTransport Configuration for the event streaming mechanism.
 * @property transportEventListener Listener for internal transport lifecycle events (logging, debugging).
 * @property json JSON serializer instance.
 * @property dispatcher Dispatcher for internal asynchronous operations.
 * @property commandMaxAttempts Maximum retry attempts for non-streaming HTTP commands.
 */
data class TaskBridgeConfig<Ctx>(
    val baseUrl: String,
    val transportFactory: TaskBridgeTransportFactory<Ctx>,
    val routeResolver: TaskBridgeRouteResolver<Ctx> = DefaultTaskBridgeRouteResolver(),
    val authHeaderProvider: (suspend (context: Ctx, forceRefresh: Boolean) -> String?)? = null,
    val checkpointStore: TaskBridgeCheckpointStore = InMemoryTaskBridgeCheckpointStore(),
    val checkpointNamespace: String? = null,
    val failureClassifier: TaskBridgeFailureClassifier = DefaultTaskBridgeFailureClassifier(),
    val retryPolicy: TaskBridgeRetryPolicy = ExponentialBackoffTaskBridgeRetryPolicy(),
    val retryGate: TransportRetryGate = NoOpTransportRetryGate,
    val streamTransport: TaskBridgeStreamTransportConfig = TaskBridgeStreamTransportConfig(),
    val transportEventListener: TaskBridgeTransportEventListener<Ctx>? = null,
    val json: Json = taskBridgeJson(),
    val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    val commandMaxAttempts: Int = 3,
)

/**
 * The main entry point for interacting with the TaskBridge backend.
 *
 * Provides methods to start tasks, observe their progress via streaming events,
 * submit actions for suspended tasks, and cancel active tasks.
 *
 * @sample io.github.nikkiw.taskbridge.samples.TaskBridgeClientSamples.createClientExample
 */
interface TaskBridgeClient<Ctx> {
    /**
     * Starts a new task with JSON input.
     *
     * @sample io.github.nikkiw.taskbridge.samples.TaskBridgeClientSamples.startTaskExample
     *
     * @param context Context for the request.
     * @param request Parameters for task creation.
     * @return Response containing the new taskId.
     * @throws Throwable if task creation fails.
     */
    suspend fun startTaskJson(
        context: Ctx,
        request: TaskCreateJsonRequest,
    ): TaskCreatedResponse

    /**
     * Starts a new task using multipart/form-data, typically for uploading files.
     *
     * @param context Context for the request.
     * @param clientRequestId Client-generated unique ID for deduplication.
     * @param taskType The type of task to create.
     * @param inputJson Optional task-specific input parameters as JSON.
     * @param metadataJson Optional metadata as JSON.
     * @param attachments List of file attachments.
     * @return Response containing the new taskId.
     * @throws Throwable if task creation fails.
     */
    @Suppress("LongParameterList")
    // Suppress LongParameterList: Unnecessary to introduce a wrapper payload class for a single multipart API call.
    suspend fun startTaskMultipart(
        context: Ctx,
        clientRequestId: String,
        taskType: String,
        inputJson: String?,
        metadataJson: String?,
        attachments: List<TaskBridgeMultipartAttachment>,
    ): TaskCreatedResponse

    /**
     * Returns a [Flow] of events for a specific task.
     *
     * The flow will automatically handle reconnection and resumption using [lastEventId]
     * or stored checkpoints. It terminates when a terminal event (COMPLETED, FAILED, CANCELLED) is received.
     *
     * @sample io.github.nikkiw.taskbridge.samples.TaskBridgeClientSamples.observeEventsExample
     *
     * @param context Context for the request.
     * @param taskId The ID of the task to observe.
     * @param lastEventId Optional event ID to start observing from (overrides store).
     * @return A stream of [TaskEvent]s.
     */
    fun observeTaskEvents(
        context: Ctx,
        taskId: String,
        lastEventId: String? = null,
    ): Flow<TaskEvent>

    /**
     * Requests cancellation of an active task.
     *
     * @param context Context for the request.
     * @param taskId The ID of the task to cancel.
     * @param reason Optional human-readable reason for cancellation.
     * @return Response indicating if cancellation was requested or if the task was already terminal.
     */
    suspend fun cancelTask(
        context: Ctx,
        taskId: String,
        reason: String? = null,
    ): CancelTaskResponse

    /**
     * Submits a user action to resume a suspended task.
     *
     * @sample io.github.nikkiw.taskbridge.samples.TaskBridgeClientSamples.submitActionExample
     *
     * @param context Context for the request.
     * @param taskId The ID of the task.
     * @param action Details of the action being performed.
     * @return Response indicating acceptance or rejection of the action.
     */
    suspend fun submitAction(
        context: Ctx,
        taskId: String,
        action: TaskActionRequest,
    ): SubmitActionResponse

    /** @suppress */
    companion object {
        /**
         * Creates a new instance of [TaskBridgeClient] with the given configuration.
         */
        fun <Ctx> create(config: TaskBridgeConfig<Ctx>): TaskBridgeClient<Ctx> {
            val transport =
                config.transportFactory.create(
                    TaskBridgeTransportFactoryConfig(
                        baseUrl = config.baseUrl,
                        authHeaderProvider = config.authHeaderProvider ?: { _, _ -> null },
                        streamTransport = config.streamTransport,
                        json = config.json,
                    ),
                )
            return DefaultTaskBridgeClient(
                config = config,
                json = config.json,
                transport = transport,
            )
        }
    }
}

/**
 * Extension methods for [TaskBridgeClient] with [Unit] context to maintain backward compatibility.
 */
suspend fun TaskBridgeClient<Unit>.startTaskJson(request: TaskCreateJsonRequest): TaskCreatedResponse = startTaskJson(Unit, request)

/** @see TaskBridgeClient.startTaskMultipart */
suspend fun TaskBridgeClient<Unit>.startTaskMultipart(
    clientRequestId: String,
    taskType: String,
    inputJson: String?,
    metadataJson: String?,
    attachments: List<TaskBridgeMultipartAttachment>,
): TaskCreatedResponse =
    startTaskMultipart(
        context = Unit,
        clientRequestId = clientRequestId,
        taskType = taskType,
        inputJson = inputJson,
        metadataJson = metadataJson,
        attachments = attachments,
    )

/** @see TaskBridgeClient.observeTaskEvents */
fun TaskBridgeClient<Unit>.observeTaskEvents(
    taskId: String,
    lastEventId: String? = null,
): Flow<TaskEvent> = observeTaskEvents(Unit, taskId, lastEventId)

/** @see TaskBridgeClient.cancelTask */
suspend fun TaskBridgeClient<Unit>.cancelTask(
    taskId: String,
    reason: String? = null,
): CancelTaskResponse = cancelTask(Unit, taskId, reason)

/** @see TaskBridgeClient.submitAction */
suspend fun TaskBridgeClient<Unit>.submitAction(
    taskId: String,
    action: TaskActionRequest,
): SubmitActionResponse = submitAction(Unit, taskId, action)

internal class DefaultTaskBridgeClient<Ctx>(
    private val config: TaskBridgeConfig<Ctx>,
    private val json: Json,
    private val transport: TaskBridgeTransportBundle<Ctx>,
) : TaskBridgeClient<Ctx> {
    private val acknowledgementState =
        SuspensionAcknowledgementState(
            checkpointStore = config.checkpointStore,
            baseUrl = config.baseUrl,
            namespace = config.checkpointNamespace,
        )

    // Suppress TooGenericExceptionCaught: Catching all exceptions to evaluate them
    // against the retry policy and failure classifier.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> runCommand(block: suspend (TaskBridgeHttpApi<Ctx>) -> T): T =
        withContext(config.dispatcher) {
            var attemptCount = 0
            var finalResult: Result<T>? = null

            // Retry loop continues until success or a non-retryable failure.
            while (finalResult == null) {
                try {
                    finalResult = Result.success(block(transport.http))
                } catch (e: CancellationException) {
                    // Always propagate coroutine cancellation immediately.
                    throw e
                } catch (e: Exception) {
                    if (coroutineContext.isActive &&
                        attemptCount < config.commandMaxAttempts &&
                        config.failureClassifier.isRetryable(e)
                    ) {
                        delay(config.retryPolicy.nextDelayMs(attemptCount))
                        attemptCount++
                    } else {
                        finalResult = Result.failure(e)
                    }
                }
            }

            // Return the successful value or rethrow the captured exception.
            finalResult.getOrThrow()
        }

    override suspend fun startTaskJson(
        context: Ctx,
        request: TaskCreateJsonRequest,
    ): TaskCreatedResponse {
        val url = config.resolveHttpUrl(config.routeResolver.createTaskPath(context))
        return runCommand { http ->
            http.createTaskJson(context, url, request)
        }
    }

    override suspend fun startTaskMultipart(
        context: Ctx,
        clientRequestId: String,
        taskType: String,
        inputJson: String?,
        metadataJson: String?,
        attachments: List<TaskBridgeMultipartAttachment>,
    ): TaskCreatedResponse {
        val url = config.resolveHttpUrl(config.routeResolver.createTaskMultipartPath(context))
        return runCommand { http ->
            http.createTaskMultipart(
                context = context,
                url = url,
                clientRequestId = clientRequestId,
                taskType = taskType,
                inputJson = inputJson,
                metadataJson = metadataJson,
                attachments = attachments,
            )
        }
    }

    override fun observeTaskEvents(
        context: Ctx,
        taskId: String,
        lastEventId: String?,
    ): Flow<TaskEvent> {
        requireValidTaskBridgeTaskId(taskId)
        return flow {
            val streamTransport =
                TaskBridgeStreamTransport<Ctx>(
                    baseUrl = config.baseUrl,
                    context = context,
                    deps =
                        TaskBridgeStreamTransportDeps(
                            pollEventsClient = transport.asPollEventsClient(),
                            webSocketFactory = transport.webSocketFactory,
                            sseSessionFactory = transport.sseSessionFactory,
                            routeResolver = config.routeResolver,
                            failureClassifier = config.failureClassifier,
                            retryPolicy = config.retryPolicy,
                            retryGate = config.retryGate,
                        ),
                    checkpoint =
                        TaskBridgeCheckpointBinding(
                            store = config.checkpointStore,
                            namespace = config.checkpointNamespace,
                        ),
                    options =
                        TaskBridgeStreamTransportOptions(
                            streamConfig = config.streamTransport,
                            eventListener = config.transportEventListener,
                            json = json,
                            dispatcher = config.dispatcher,
                        ),
                )
            emitAll(
                streamTransport
                    .observeTaskEvents(taskId = taskId, afterEventId = lastEventId)
                    .transformWhile { event ->
                        when (event) {
                            is TaskSuspendedEvent -> {
                                if (!acknowledgementState.isAcknowledged(
                                        event.taskId,
                                        event.suspension.suspendId,
                                    )
                                ) {
                                    emit(event)
                                }
                                true
                            }

                            is TaskActionAcceptedEvent -> {
                                acknowledgementState.remember(
                                    taskId = event.taskId,
                                    suspendId = event.accepted.suspendId,
                                    clientActionId = event.accepted.clientActionId,
                                )
                                emit(event)
                                true
                            }

                            is TaskCompletedEvent,
                            is TaskFailedEvent,
                            is TaskCancelledEvent,
                            -> {
                                acknowledgementState.clearTask(event.taskId)
                                emit(event)
                                false
                            }

                            else -> {
                                emit(event)
                                true
                            }
                        }
                    },
            )
        }
    }

    override suspend fun cancelTask(
        context: Ctx,
        taskId: String,
        reason: String?,
    ): CancelTaskResponse {
        requireValidTaskBridgeTaskId(taskId)
        val url = config.resolveHttpUrl(config.routeResolver.cancelTaskPath(context, taskId))
        val body = if (reason != null) CancelTaskBody(reason) else null
        return runCommand { http ->
            http.cancelTask(context, url, body)
        }
    }

    override suspend fun submitAction(
        context: Ctx,
        taskId: String,
        action: TaskActionRequest,
    ): SubmitActionResponse {
        requireValidTaskBridgeTaskId(taskId)
        val url = config.resolveHttpUrl(config.routeResolver.submitActionPath(context, taskId))
        val response =
            runCommand { http ->
                http.submitAction(context, url, action)
            }
        if (response.status == SubmitActionStatus.ACCEPTED || response.status == SubmitActionStatus.DEDUPLICATED) {
            acknowledgementState.remember(
                taskId = taskId,
                suspendId = response.suspendId,
                clientActionId = response.clientActionId,
            )
        }
        return response
    }
}

internal fun TaskBridgeConfig<*>.resolveHttpUrl(path: String): String = httpBaseToHttpUrl(baseUrl, path)

internal fun TaskBridgeConfig<*>.resolveWebSocketUrl(path: String): String = httpBaseToWebSocketUrl(baseUrl, path)
