"""Storage abstraction for task action idempotency receipts."""

from __future__ import annotations

from abc import ABC, abstractmethod
from datetime import datetime
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from .models import TaskActionReceipt


class ActionReceiptStore(ABC):
    """Interface for persisting and retrieving task action receipts.

    Receipts are used to ensure idempotency for task actions submitted by clients.
    """

    @abstractmethod
    async def get_receipt(
        self,
        task_id: str,
        client_action_id: str,
    ) -> TaskActionReceipt | None:
        """Retrieve a receipt by task ID and client-provided action ID.

        Args:
            task_id: Unique identifier of the task.
            client_action_id: Idempotency key provided by the client.

        Returns:
            The matching receipt or None if not found.

        """
        raise NotImplementedError

    @abstractmethod
    async def save_receipt(
        self,
        receipt: TaskActionReceipt,
    ) -> TaskActionReceipt:
        """Save a new task action receipt.

        Args:
            receipt: The receipt record to persist.

        Returns:
            The persisted receipt.

        """
        raise NotImplementedError

    @abstractmethod
    async def delete_expired_receipts(
        self,
        expires_before: datetime,
    ) -> int:
        """Remove receipts that have passed their expiration time.

        Args:
            expires_before: Threshold timestamp for expiration.

        Returns:
            Number of deleted receipts.

        """
        raise NotImplementedError
