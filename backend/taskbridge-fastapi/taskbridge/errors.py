"""Exception definitions for the TaskBridge system."""

from __future__ import annotations


class TaskBridgeError(Exception):
    """Base class for all TaskBridge-related exceptions."""

    code = "TASKBRIDGE_ERROR"


class AuthenticationError(TaskBridgeError):
    """Raised when request authentication fails."""

    code = "AUTHENTICATION_ERROR"


class TaskNotFoundError(TaskBridgeError):
    """Raised when a requested task does not exist or is inaccessible."""

    code = "TASK_NOT_FOUND"


class TaskOwnershipError(TaskBridgeError):
    """Raised when a user attempts to create a task they are not allowed to own.

    Note: Read/cancel/subscribe operations use TaskNotFoundError instead of this
    exception to avoid leaking task existence via enumeration.
    """

    code = "TASK_OWNERSHIP_ERROR"


class TaskConflictError(TaskBridgeError):
    """Raised when a task operation conflicts with the current state."""

    code = "TASK_CONFLICT"


class TaskAlreadyTerminalError(TaskBridgeError):
    """Raised when attempting to modify a task that has already finished."""

    code = "TASK_ALREADY_TERMINAL"


class InvalidTaskStateError(TaskBridgeError):
    """Raised when a task is in an unexpected state for the requested operation."""

    code = "INVALID_TASK_STATE"


class InvalidRequestError(TaskBridgeError):
    """Raised when the client request is malformed or invalid."""

    code = "INVALID_REQUEST"


class IdempotencyConflictError(TaskConflictError):
    """Raised when an idempotent request has a mismatching payload."""

    code = "IDEMPOTENCY_CONFLICT"


class TaskSubmissionError(TaskBridgeError):
    """Raised when submitting a task to the executor fails."""

    code = "TASK_SUBMISSION_ERROR"


class UploadValidationError(TaskBridgeError):
    """Raised when an uploaded file fails validation."""

    code = "UPLOAD_VALIDATION_ERROR"
