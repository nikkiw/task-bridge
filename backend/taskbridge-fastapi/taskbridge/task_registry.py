"""Persistence abstraction for task state management."""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from .models import TaskCreateCommand, TaskRecord, TaskStatus


class TaskRegistry(ABC):
    """Interface for managing the persistent state of tasks.

    The registry is the source of truth for task existence, status, and ownership.
    """

    @abstractmethod
    async def create_task(self, task: TaskRecord) -> TaskRecord:
        """Persist a new task record.

        Args:
            task: Initial task state to persist.

        Returns:
            The persisted task record.

        """
        raise NotImplementedError

    @abstractmethod
    async def get_task(self, task_id: str) -> TaskRecord:
        """Retrieve a task by its unique ID.

        Args:
            task_id: Unique task identifier.

        Returns:
            The task record.

        Raises:
            KeyError: If task does not exist.

        """
        raise NotImplementedError

    @abstractmethod
    async def get_task_by_client_request_id(
        self, owner_id: str, client_request_id: str
    ) -> TaskRecord | None:
        """Lookup task by user ID and their client-provided request ID.

        Used for task creation idempotency.

        Args:
            owner_id: Subject ID of the task owner.
            client_request_id: Idempotency key provided by the client.

        Returns:
            Matching task record or None.

        """
        raise NotImplementedError

    @abstractmethod
    async def update_task_status(self, task_id: str, status: TaskStatus) -> TaskRecord:
        """Update the current status of a task.

        Args:
            task_id: Unique task identifier.
            status: New status to apply.

        Returns:
            The updated task record.

        """
        raise NotImplementedError

    @abstractmethod
    async def request_cancellation(self, task_id: str) -> TaskRecord:
        """Mark a task as cancellation requested.

        Args:
            task_id: Unique task identifier.

        Returns:
            The updated task record.

        """
        raise NotImplementedError

    @abstractmethod
    async def same_idempotency_payload(self, task: TaskRecord, command: TaskCreateCommand) -> bool:
        """Compare a stored task with a new creation command for idempotency.

        Args:
            task: Existing task record.
            command: New task creation request.

        Returns:
            True if payloads match sufficiently to be considered identical.

        """
        raise NotImplementedError
