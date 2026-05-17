"""Storage abstraction for task suspension lifecycle."""

from __future__ import annotations

from abc import ABC, abstractmethod
from datetime import datetime
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from .models import TaskSuspensionRecord


class SuspensionStore(ABC):
    """Interface for managing the persistent lifecycle of task suspensions.

    Suspensions represent points in task execution where the system is waiting
    for client input.
    """

    @abstractmethod
    async def get_suspension(self, task_id: str, suspend_id: str) -> TaskSuspensionRecord:
        """Retrieve a specific suspension record.

        Args:
            task_id: Unique task identifier.
            suspend_id: Unique suspension identifier.

        Returns:
            The suspension record.

        Raises:
            KeyError: If the suspension is not found.

        """
        raise NotImplementedError

    @abstractmethod
    async def accept_action_if_open(
        self,
        task_id: str,
        suspend_id: str,
        client_action_id: str,
    ) -> TaskSuspensionRecord | None:
        """Atomically transition a suspension to RESOLVED if it is currently OPEN.

        Args:
            task_id: Unique task identifier.
            suspend_id: Unique suspension identifier.
            client_action_id: The ID of the action that resolved the suspension.

        Returns:
            The updated record if transition was successful, None otherwise.

        """
        raise NotImplementedError

    @abstractmethod
    async def mark_resume_dispatched(
        self,
        task_id: str,
        suspend_id: str,
    ) -> TaskSuspensionRecord:
        """Mark a resolved suspension as successfully handed off back to the executor.

        Args:
            task_id: Unique task identifier.
            suspend_id: Unique suspension identifier.

        Returns:
            The updated record.

        """
        raise NotImplementedError

    @abstractmethod
    async def list_pending_resume_handoffs(self) -> list[TaskSuspensionRecord]:
        """List all resolved suspensions that are waiting for handoff.

        Used by reconciliation services to recover from crashes after resolution
        but before handoff.

        Returns:
            List of suspensions with PENDING handoff status.

        """
        raise NotImplementedError

    @abstractmethod
    async def expire_open_suspensions(
        self,
        expires_before: datetime,
    ) -> int:
        """Transition OPEN suspensions to EXPIRED if they have passed their deadline.

        Args:
            expires_before: Threshold timestamp for expiration.

        Returns:
            Number of suspensions that were expired.

        """
        raise NotImplementedError
