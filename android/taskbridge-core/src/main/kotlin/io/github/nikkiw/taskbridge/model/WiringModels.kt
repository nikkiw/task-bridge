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
package io.github.nikkiw.taskbridge.model

import io.github.nikkiw.taskbridge.transport.taskBridgeJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Types of events that can be emitted during a task lifecycle.
 */
@Serializable
enum class TaskEventType {
    /** The task has been officially started on the server. */
    @SerialName("TASK_STARTED")
    TASK_STARTED,

    /** Incremental progress update from the task. */
    @SerialName("TASK_PROGRESS")
    TASK_PROGRESS,

    /** A generic message or log from the task. */
    @SerialName("TASK_MESSAGE")
    TASK_MESSAGE,

    /** The task is waiting for user input or external action. */
    @SerialName("TASK_SUSPENDED")
    TASK_SUSPENDED,

    /** The server has acknowledged and accepted a user action. */
    @SerialName("TASK_ACTION_ACCEPTED")
    TASK_ACTION_ACCEPTED,

    /** The task has finished successfully. Terminal event. */
    @SerialName("TASK_COMPLETED")
    TASK_COMPLETED,

    /** The task has failed with an error. Terminal event. */
    @SerialName("TASK_FAILED")
    TASK_FAILED,

    /** The task was manually cancelled. Terminal event. */
    @SerialName("TASK_CANCELLED")
    TASK_CANCELLED,
}

/**
 * Categories of task suspensions as defined in the protocol.
 */
@Serializable
enum class TaskSuspensionKind {
    /** The task is waiting for a direct user action. */
    @SerialName("USER_ACTION_REQUIRED")
    USER_ACTION_REQUIRED,

    /** The task is waiting for external confirmation. */
    @SerialName("EXTERNAL_CONFIRMATION_PENDING")
    EXTERNAL_CONFIRMATION_PENDING,

    /** The task is manually paused by the system. */
    @SerialName("SYSTEM_PAUSED")
    SYSTEM_PAUSED,
}

/**
 * Base interface for all task events.
 */
sealed interface TaskEvent {
    /** Unique identifier of the task this event belongs to. */
    val taskId: String

    /** Unique identifier of this specific event for deduplication and replay. */
    val eventId: String

    /** ISO 8601 timestamp when the event was created on the server. */
    val createdAt: String

    /** Raw JSON data associated with the event. */
    val payload: JsonObject

    /** The string representation of the event type as received over the wire. */
    val wireType: String
}

/**
 * Interface for task events with a known [TaskEventType].
 */
sealed interface KnownTaskEvent : TaskEvent {
    /** The typed category of this event. */
    val type: TaskEventType
    override val wireType: String get() = type.name
}

/**
 * Emitted when the task starts execution.
 */
data class TaskStartedEvent(
    override val taskId: String,
    override val eventId: String,
    override val createdAt: String,
    override val payload: JsonObject = JsonObject(emptyMap()),
) : KnownTaskEvent {
    override val type: TaskEventType = TaskEventType.TASK_STARTED
}

/**
 * Emitted to provide updates on task progress.
 */
data class TaskProgressEvent(
    override val taskId: String,
    override val eventId: String,
    override val createdAt: String,
    override val payload: JsonObject = JsonObject(emptyMap()),
) : KnownTaskEvent {
    override val type: TaskEventType = TaskEventType.TASK_PROGRESS
}

/**
 * Emitted for general information or logging.
 */
data class TaskMessageEvent(
    override val taskId: String,
    override val eventId: String,
    override val createdAt: String,
    override val payload: JsonObject = JsonObject(emptyMap()),
) : KnownTaskEvent {
    override val type: TaskEventType = TaskEventType.TASK_MESSAGE
}

/**
 * Details about a task suspension waiting for user interaction.
 *
 * @property suspendId Unique ID for this suspension instance.
 * @property kind The category of interaction required (e.g., "form", "approval").
 * @property reasonCode Machine-readable code explaining why the task suspended.
 * @property allowedActions List of action types the user can submit to resume.
 * @property schemaVersion Version of the payload/interaction schema.
 * @property expiresAt Optional ISO 8601 timestamp when this suspension expires.
 * @property uiHints Hints for the UI layer on how to render this suspension.
 * @property interaction Data required for the specific interaction.
 */
@Serializable
data class SuspensionPayload(
    val suspendId: String,
    val kind: TaskSuspensionKind,
    val reasonCode: String,
    val allowedActions: List<String>,
    val schemaVersion: Int,
    val expiresAt: String? = null,
    val uiHints: JsonObject = JsonObject(emptyMap()),
    val interaction: JsonObject = JsonObject(emptyMap()),
)

/**
 * Structured payload for TASK_PROGRESS events.
 */
@Serializable
data class TaskProgressPayload(
    /** Percentage of completion (0 to 100). */
    val progress: Float,
    /** Optional identifier for the current processing stage. */
    val stage: String? = null,
    /** Optional human-readable message. */
    val message: String? = null,
)

/**
 * Structured payload for TASK_FAILED events.
 */
@Serializable
data class TaskFailedPayload(
    /** Machine-readable error code. */
    val code: String,
    /** Human-readable error message. */
    val message: String,
    /** True if the operation can be retried by the client. */
    val retryable: Boolean,
)

/**
 * Emitted when the task enters a suspended state.
 */
data class TaskSuspendedEvent(
    override val taskId: String,
    override val eventId: String,
    override val createdAt: String,
    override val payload: JsonObject = JsonObject(emptyMap()),
    /** Parsed suspension details. */
    val suspension: SuspensionPayload,
) : KnownTaskEvent {
    override val type: TaskEventType = TaskEventType.TASK_SUSPENDED
}

/**
 * Details about an accepted user action.
 *
 * @property suspendId The ID of the suspension this action addresses.
 * @property clientActionId The ID provided by the client for this action.
 * @property actionType The type of action performed.
 * @property acceptedAt ISO 8601 timestamp when the action was accepted.
 * @property message Optional confirmation message from the server.
 */
@Serializable
data class ActionAcceptedPayload(
    val suspendId: String,
    val clientActionId: String,
    val actionType: String,
    val acceptedAt: String,
    val message: String? = null,
)

/**
 * Emitted when a user action has been successfully processed.
 */
data class TaskActionAcceptedEvent(
    override val taskId: String,
    override val eventId: String,
    override val createdAt: String,
    override val payload: JsonObject = JsonObject(emptyMap()),
    /** Parsed action acceptance details. */
    val accepted: ActionAcceptedPayload,
) : KnownTaskEvent {
    override val type: TaskEventType = TaskEventType.TASK_ACTION_ACCEPTED
}

/**
 * Emitted when the task completes successfully.
 */
data class TaskCompletedEvent(
    override val taskId: String,
    override val eventId: String,
    override val createdAt: String,
    override val payload: JsonObject = JsonObject(emptyMap()),
) : KnownTaskEvent {
    override val type: TaskEventType = TaskEventType.TASK_COMPLETED
}

/**
 * Emitted when the task fails with an error.
 */
data class TaskFailedEvent(
    override val taskId: String,
    override val eventId: String,
    override val createdAt: String,
    override val payload: JsonObject = JsonObject(emptyMap()),
) : KnownTaskEvent {
    override val type: TaskEventType = TaskEventType.TASK_FAILED
}

/**
 * Emitted when the task is cancelled.
 */
data class TaskCancelledEvent(
    override val taskId: String,
    override val eventId: String,
    override val createdAt: String,
    override val payload: JsonObject = JsonObject(emptyMap()),
) : KnownTaskEvent {
    override val type: TaskEventType = TaskEventType.TASK_CANCELLED
}

/**
 * Represents an event with an unknown or unmapped [wireType].
 */
data class UnknownTaskEvent(
    override val wireType: String,
    override val taskId: String,
    override val eventId: String,
    override val createdAt: String,
    override val payload: JsonObject = JsonObject(emptyMap()),
) : TaskEvent

/**
 * Returns true if this event indicates the final state of the task.
 */
fun TaskEvent.isTerminal(): Boolean =
    when (this) {
        is TaskCompletedEvent,
        is TaskFailedEvent,
        is TaskCancelledEvent,
        -> true
        else -> false
    }

/**
 * Tries to parse the payload as [TaskProgressPayload] if the event type matches.
 */
fun TaskEvent.asProgress(): TaskProgressPayload? =
    if (this is TaskProgressEvent) {
        taskBridgeJson().decodeFromJsonElement<TaskProgressPayload>(payload)
    } else {
        null
    }

/**
 * Tries to parse the payload as [TaskFailedPayload] if the event type matches.
 */
fun TaskEvent.asFailure(): TaskFailedPayload? =
    if (this is TaskFailedEvent) {
        taskBridgeJson().decodeFromJsonElement<TaskFailedPayload>(payload)
    } else {
        null
    }

/**
 * Raw JSON envelope for events as received from the server.
 *
 * @property type The type of the event.
 * @property taskId The unique ID of the task.
 * @property eventId The unique ID of the event.
 * @property createdAt ISO 8601 timestamp.
 * @property payload The event data.
 */
@Serializable
data class RawTaskEventEnvelope(
    val type: String,
    val taskId: String,
    val eventId: String,
    val createdAt: String,
    val payload: JsonObject = JsonObject(emptyMap()),
)

/**
 * Converts a [RawTaskEventEnvelope] to a typed [TaskEvent].
 *
 * @return The corresponding [TaskEvent] instance based on the envelope's type.
 */
fun RawTaskEventEnvelope.toTaskEvent(): TaskEvent =
    when (type) {
        TaskEventType.TASK_STARTED.name ->
            TaskStartedEvent(
                taskId = taskId,
                eventId = eventId,
                createdAt = createdAt,
                payload = payload,
            )

        TaskEventType.TASK_PROGRESS.name ->
            TaskProgressEvent(
                taskId = taskId,
                eventId = eventId,
                createdAt = createdAt,
                payload = payload,
            )

        TaskEventType.TASK_MESSAGE.name ->
            TaskMessageEvent(
                taskId = taskId,
                eventId = eventId,
                createdAt = createdAt,
                payload = payload,
            )

        TaskEventType.TASK_SUSPENDED.name ->
            TaskSuspendedEvent(
                taskId = taskId,
                eventId = eventId,
                createdAt = createdAt,
                payload = payload,
                suspension = taskBridgeJson().decodeFromJsonElement(payload),
            )

        TaskEventType.TASK_ACTION_ACCEPTED.name ->
            TaskActionAcceptedEvent(
                taskId = taskId,
                eventId = eventId,
                createdAt = createdAt,
                payload = payload,
                accepted = taskBridgeJson().decodeFromJsonElement(payload),
            )

        TaskEventType.TASK_COMPLETED.name ->
            TaskCompletedEvent(
                taskId = taskId,
                eventId = eventId,
                createdAt = createdAt,
                payload = payload,
            )

        TaskEventType.TASK_FAILED.name ->
            TaskFailedEvent(
                taskId = taskId,
                eventId = eventId,
                createdAt = createdAt,
                payload = payload,
            )

        TaskEventType.TASK_CANCELLED.name ->
            TaskCancelledEvent(
                taskId = taskId,
                eventId = eventId,
                createdAt = createdAt,
                payload = payload,
            )

        else ->
            UnknownTaskEvent(
                wireType = type,
                taskId = taskId,
                eventId = eventId,
                createdAt = createdAt,
                payload = payload,
            )
    }

/**
 * Request body for starting a new task with JSON input.
 *
 * @property clientRequestId Client-generated unique ID for deduplication.
 * @property taskType The type of task to create.
 * @property input Task-specific input parameters.
 * @property metadata Optional metadata for the task.
 */
@Serializable
data class TaskCreateJsonRequest(
    val clientRequestId: String,
    val taskType: String,
    val input: JsonObject = JsonObject(emptyMap()),
    val metadata: JsonObject = JsonObject(emptyMap()),
)

/**
 * Represents a file attachment for a multipart task creation request.
 *
 * @property fieldName The multipart form field name.
 * @property fileName The name of the file.
 * @property contentType The MIME type of the file.
 * @property content Raw byte content of the file.
 */
data class TaskBridgeMultipartAttachment(
    val fieldName: String = "attachments",
    val fileName: String,
    val contentType: String = "application/octet-stream",
    val content: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TaskBridgeMultipartAttachment) return false
        return fieldName == other.fieldName &&
            fileName == other.fileName &&
            contentType == other.contentType &&
            content.contentEquals(other.content)
    }

    override fun hashCode(): Int {
        var result = fieldName.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + content.contentHashCode()
        return result
    }
}

/**
 * Response from the server after a task is created.
 *
 * @property taskId The unique ID of the created task.
 * @property status Initial status of the task.
 * @property clientRequestId The ID provided by the client.
 * @property deduplicated True if this task was already created for the same [clientRequestId].
 */
@Serializable
data class TaskCreatedResponse(
    val taskId: String,
    val status: TaskStatus,
    val clientRequestId: String,
    val deduplicated: Boolean = false,
)

/**
 * Overall status of a task.
 */
@Serializable
enum class TaskStatus {
    /** Task has been created and is in the queue. */
    @SerialName("ACCEPTED")
    ACCEPTED,

    /** Task is currently being processed. */
    @SerialName("RUNNING")
    RUNNING,

    /** Task has completed successfully. */
    @SerialName("COMPLETED")
    COMPLETED,

    /** Task has failed. */
    @SerialName("FAILED")
    FAILED,

    /** Task was cancelled. */
    @SerialName("CANCELLED")
    CANCELLED,
}

/**
 * Response from a long-polling event request.
 *
 * @property taskId The task ID.
 * @property events List of new events.
 * @property nextAfterEventId ID to use for the next poll request.
 * @property hasMore True if there are more events to fetch immediately.
 */
@Serializable
data class PollEventsResponse(
    val taskId: String,
    val events: List<RawTaskEventEnvelope>,
    val nextAfterEventId: String? = null,
    val hasMore: Boolean,
    /**
     * Raw JSON response string. This field is NOT serialized/deserialized,
     * it's populated manually by the transport layer for logging.
     */
    @Transient
    var rawJson: String? = null,
)

/**
 * Request body for cancelling a task.
 *
 * @property reason Optional human-readable reason for cancellation.
 */
@Serializable
data class CancelTaskBody(
    val reason: String? = null,
)

/**
 * Status of a cancellation request.
 */
@Serializable
enum class CancelTaskStatus {
    /** Cancellation was requested successfully. */
    @SerialName("CANCELLATION_REQUESTED")
    CANCELLATION_REQUESTED,

    /** Task was already in a terminal state. */
    @SerialName("ALREADY_TERMINAL")
    ALREADY_TERMINAL,
}

/**
 * Response from a task cancellation request.
 *
 * @property taskId The task ID.
 * @property status The result of the cancellation request.
 */
@Serializable
data class CancelTaskResponse(
    val taskId: String,
    val status: CancelTaskStatus,
)

/**
 * Request to submit a user action for a suspended task.
 *
 * @property clientActionId Client-generated unique ID for deduplication.
 * @property suspendId The ID of the suspension being addressed.
 * @property actionType The type of action to perform.
 * @property payload Action-specific parameters.
 * @property metadata Optional metadata for the action.
 */
@Serializable
data class TaskActionRequest(
    val clientActionId: String,
    val suspendId: String,
    val actionType: String,
    val payload: JsonObject = JsonObject(emptyMap()),
    val metadata: JsonObject = JsonObject(emptyMap()),
)

/**
 * Status of an action submission.
 */
@Serializable
enum class SubmitActionStatus {
    /** Action was accepted for processing. */
    @SerialName("ACCEPTED")
    ACCEPTED,

    /** Action was already accepted previously. */
    @SerialName("DEDUPLICATED")
    DEDUPLICATED,

    /** Action was rejected because the suspension is already resolved. */
    @SerialName("REJECTED_ALREADY_RESOLVED")
    REJECTED_ALREADY_RESOLVED,

    /** Action was rejected because the suspension has expired. */
    @SerialName("REJECTED_EXPIRED")
    REJECTED_EXPIRED,
}

/**
 * Response from an action submission request.
 *
 * @property taskId The task ID.
 * @property suspendId The suspension ID.
 * @property clientActionId The client action ID.
 * @property status The result of the submission.
 */
@Serializable
data class SubmitActionResponse(
    val taskId: String,
    val suspendId: String,
    val clientActionId: String,
    val status: SubmitActionStatus,
)
