"""Data models and enums for the TaskBridge system."""

from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field

from .errors import InvalidTaskStateError


class TaskStatus(str, Enum):
    """Current state of a task in the system lifecycle."""

    ACCEPTED = "ACCEPTED"
    RUNNING = "RUNNING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"

    @property
    def is_terminal(self) -> bool:
        """Return True if the status represents a final, unchangeable state."""
        return self in {
            TaskStatus.COMPLETED,
            TaskStatus.FAILED,
            TaskStatus.CANCELLED,
        }


class TaskEventType(str, Enum):
    """Types of events that can occur during task execution."""

    TASK_STARTED = "TASK_STARTED"
    TASK_PROGRESS = "TASK_PROGRESS"
    TASK_MESSAGE = "TASK_MESSAGE"
    TASK_SUSPENDED = "TASK_SUSPENDED"
    TASK_ACTION_ACCEPTED = "TASK_ACTION_ACCEPTED"
    TASK_COMPLETED = "TASK_COMPLETED"
    TASK_FAILED = "TASK_FAILED"
    TASK_CANCELLED = "TASK_CANCELLED"


class CancelTaskStatus(str, Enum):
    """Outcome of a task cancellation request."""

    CANCELLATION_REQUESTED = "CANCELLATION_REQUESTED"
    ALREADY_TERMINAL = "ALREADY_TERMINAL"


class SubmitActionResultStatus(str, Enum):
    """Status of a task action submission."""

    ACCEPTED = "ACCEPTED"
    DEDUPLICATED = "DEDUPLICATED"
    REJECTED_ALREADY_RESOLVED = "REJECTED_ALREADY_RESOLVED"
    REJECTED_EXPIRED = "REJECTED_EXPIRED"


class TaskSuspensionStatus(str, Enum):
    """Lifecycle status of a task suspension."""

    OPEN = "OPEN"
    RESOLVED = "RESOLVED"
    EXPIRED = "EXPIRED"
    CANCELLED = "CANCELLED"


class TaskSuspensionKind(str, Enum):
    """Categories of task suspensions as defined in the protocol."""

    USER_ACTION_REQUIRED = "USER_ACTION_REQUIRED"
    EXTERNAL_CONFIRMATION_PENDING = "EXTERNAL_CONFIRMATION_PENDING"
    SYSTEM_PAUSED = "SYSTEM_PAUSED"


class ResumeHandoffStatus(str, Enum):
    """Status of handing off a resumed task back to the executor."""

    NONE = "NONE"
    PENDING = "PENDING"
    DISPATCHED = "DISPATCHED"


class TaskActionReceiptStatus(str, Enum):
    """Processing status of an action receipt."""

    ACCEPTED = "ACCEPTED"
    DEDUPLICATED = "DEDUPLICATED"
    REJECTED = "REJECTED"


class TaskBridgeModel(BaseModel):
    """Base Pydantic model with project-standard configuration."""

    model_config = ConfigDict(populate_by_name=True, extra="forbid")


class WebSocketSubscribeRequest(TaskBridgeModel):
    """Client request to subscribe to task events via WebSocket."""

    action: Literal["subscribe"]
    task_id: str = Field(alias="taskId")
    last_event_id: str | None = Field(default=None, alias="lastEventId")


class WebSocketSubscriptionConfirmed(TaskBridgeModel):
    """Server confirmation of a WebSocket subscription."""

    type: Literal["SUBSCRIPTION_CONFIRMED"]
    task_id: str = Field(alias="taskId")
    replay_started_after_event_id: str | None = Field(
        default=None,
        alias="replayStartedAfterEventId",
    )
    live: bool


class WebSocketHeartbeat(TaskBridgeModel):
    """Periodic heartbeat message sent over WebSocket."""

    type: Literal["HEARTBEAT"]
    task_id: str = Field(alias="taskId")
    live: bool


class TaskAttachment(TaskBridgeModel):
    """Metadata and content for an attached file in task creation."""

    filename: str
    content_type: str
    content: bytes
    size_bytes: int


class TaskProgressPayload(TaskBridgeModel):
    """Structured payload for TASK_PROGRESS events."""

    progress: float = Field(ge=0, le=100)
    stage: str | None = None
    message: str | None = None


class TaskFailedPayload(TaskBridgeModel):
    """Structured payload for TASK_FAILED events."""

    code: str
    message: str
    retryable: bool


class AuthContext(TaskBridgeModel):
    """Security context derived from the authenticated user or system."""

    subject: str
    scopes: set[str] = Field(default_factory=set)
    app_id: str | None = None
    attributes: dict[str, Any] = Field(default_factory=dict)


class TaskEvent(TaskBridgeModel):
    """A discrete event emitted during the lifecycle of a task."""

    type: TaskEventType
    task_id: str = Field(alias="taskId")
    event_id: str = Field(alias="eventId")
    created_at: datetime = Field(alias="createdAt")
    payload: dict[str, Any]

    @property
    def progress(self) -> TaskProgressPayload | None:
        """Return typed progress data if this is a progress event."""
        if self.type == TaskEventType.TASK_PROGRESS:
            return TaskProgressPayload.model_validate(self.payload)
        return None

    @property
    def failure(self) -> TaskFailedPayload | None:
        """Return typed failure data if this is a failure event."""
        if self.type == TaskEventType.TASK_FAILED:
            return TaskFailedPayload.model_validate(self.payload)
        return None


class TaskCreateCommand(TaskBridgeModel):
    """Internal command to create a new task."""

    client_request_id: str
    task_type: str
    input_payload: dict[str, Any]
    metadata: dict[str, Any] = Field(default_factory=dict)
    attachments: list[TaskAttachment] = Field(default_factory=list)
    auth_context: AuthContext


class HttpTaskCreateRequest(TaskBridgeModel):
    """HTTP request body for task creation."""

    client_request_id: str = Field(alias="clientRequestId")
    task_type: str = Field(alias="taskType")
    input_payload: dict[str, Any] = Field(alias="input")
    metadata: dict[str, Any] = Field(default_factory=dict)


class TaskCreatedResult(TaskBridgeModel):
    """Result of a task creation request."""

    task_id: str = Field(alias="taskId")
    status: TaskStatus
    client_request_id: str = Field(alias="clientRequestId")
    deduplicated: bool = False


class PollEventsResult(TaskBridgeModel):
    """Result of a long-polling request for task events."""

    task_id: str = Field(alias="taskId")
    events: list[TaskEvent]
    next_after_event_id: str | None = Field(default=None, alias="nextAfterEventId")
    has_more: bool = Field(alias="hasMore")


class CancelTaskCommand(TaskBridgeModel):
    """Internal command to cancel a task."""

    task_id: str
    auth_context: AuthContext
    reason: str | None = None


class HttpCancelTaskRequest(TaskBridgeModel):
    """HTTP request body for task cancellation."""

    reason: str | None = None


class CancelTaskResult(TaskBridgeModel):
    """Result of a task cancellation request."""

    task_id: str = Field(alias="taskId")
    status: CancelTaskStatus


class HttpSubmitActionRequest(TaskBridgeModel):
    """HTTP request body for submitting an action to a suspended task."""

    client_action_id: str = Field(alias="clientActionId")
    suspend_id: str = Field(alias="suspendId")
    action_type: str = Field(alias="actionType")
    payload: dict[str, Any] = Field(default_factory=dict)
    metadata: dict[str, Any] = Field(default_factory=dict)


class SubmitActionCommand(TaskBridgeModel):
    """Internal command to submit a task action."""

    task_id: str
    client_action_id: str
    suspend_id: str
    action_type: str
    payload: dict[str, Any]
    metadata: dict[str, Any] = Field(default_factory=dict)
    auth_context: AuthContext


class SubmitActionResult(TaskBridgeModel):
    """Result of a task action submission."""

    task_id: str = Field(alias="taskId")
    suspend_id: str = Field(alias="suspendId")
    client_action_id: str = Field(alias="clientActionId")
    status: SubmitActionResultStatus


class TaskSuspensionRecord(TaskBridgeModel):
    """Persistent record of a task suspension waiting for client interaction."""

    task_id: str
    suspend_id: str
    owner_id: str
    status: TaskSuspensionStatus
    kind: TaskSuspensionKind
    reason_code: str
    allowed_actions: list[str]
    schema_version: int
    interaction_payload: dict[str, Any] = Field(default_factory=dict)
    ui_hints: dict[str, Any] = Field(default_factory=dict)
    resume_token: str | None = None
    successful_action_id: str | None = None
    resume_handoff_status: ResumeHandoffStatus = ResumeHandoffStatus.NONE
    created_at: datetime
    expires_at: datetime | None = None
    resolved_at: datetime | None = None


class TaskActionReceipt(TaskBridgeModel):
    """Idempotency record for a submitted task action."""

    task_id: str
    suspend_id: str
    client_action_id: str
    action_type: str
    payload: dict[str, Any]
    actor_id: str
    status: TaskActionReceiptStatus
    created_at: datetime
    processed_at: datetime | None = None
    expires_at: datetime | None = None


class ErrorResponse(TaskBridgeModel):
    """Standard error response payload for API endpoints."""

    code: str
    message: str
    details: dict[str, Any] = Field(default_factory=dict)
    timestamp: datetime


class HealthResponse(TaskBridgeModel):
    """Basic health check response."""

    status: str


class ReadinessResponse(TaskBridgeModel):
    """Detailed readiness probe response."""

    status: str
    details: dict[str, Any] = Field(default_factory=dict)


class TaskRecord(TaskBridgeModel):
    """Persistent state of a task in the registry."""

    task_id: str
    client_request_id: str
    task_type: str
    input_payload: dict[str, Any]
    metadata: dict[str, Any] = Field(default_factory=dict)
    attachments: list[TaskAttachment] = Field(default_factory=list)
    owner_id: str
    status: TaskStatus = TaskStatus.ACCEPTED
    cancellation_requested: bool = False
    created_at: datetime
    updated_at: datetime

    @classmethod
    def from_command(cls, task_id: str, command: TaskCreateCommand) -> "TaskRecord":
        """Create a new record instance from a creation command.

        Args:
            task_id: Assigned unique identifier for the task.
            command: The command containing task input and metadata.

        Returns:
            A new TaskRecord initialized with status ACCEPTED.

        """
        now = datetime.now(timezone.utc)
        return cls(
            task_id=task_id,
            client_request_id=command.client_request_id,
            task_type=command.task_type,
            input_payload=command.input_payload,
            metadata=command.metadata,
            attachments=command.attachments,
            owner_id=command.auth_context.subject,
            status=TaskStatus.ACCEPTED,
            cancellation_requested=False,
            created_at=now,
            updated_at=now,
        )


def ensure_transition(current: TaskStatus, new: TaskStatus) -> None:
    """Validate that a task can transition from one status to another.

    Args:
        current: The existing status of the task.
        new: The requested new status.

    Raises:
        InvalidTaskStateError: If the transition is not allowed by lifecycle rules.

    """
    allowed_transitions = {
        TaskStatus.ACCEPTED: {TaskStatus.ACCEPTED, TaskStatus.RUNNING},
        TaskStatus.RUNNING: {
            TaskStatus.RUNNING,
            TaskStatus.COMPLETED,
            TaskStatus.FAILED,
            TaskStatus.CANCELLED,
        },
        TaskStatus.COMPLETED: {TaskStatus.COMPLETED},
        TaskStatus.FAILED: {TaskStatus.FAILED},
        TaskStatus.CANCELLED: {TaskStatus.CANCELLED},
    }
    if new not in allowed_transitions[current]:
        raise InvalidTaskStateError(f"Cannot transition task from {current} to {new}")


def same_idempotency_payload(task: TaskRecord, command: TaskCreateCommand) -> bool:
    """Check if a new creation request matches an existing task's payload.

    Used to detect conflicting idempotency keys.

    Args:
        task: Existing task record.
        command: New creation request.

    Returns:
        True if payloads are considered identical for idempotency purposes.

    """
    return (
        task.task_type == command.task_type
        and task.input_payload == command.input_payload
        and task.attachments == command.attachments
    )
