from __future__ import annotations

from datetime import datetime, timezone
from typing import TYPE_CHECKING

from taskbridge.models import (
    ResumeHandoffStatus,
    TaskActionReceiptStatus,
    TaskSuspensionStatus,
)
from taskbridge.suspension_store import SuspensionStore
from taskbridge.action_receipt_store import ActionReceiptStore

if TYPE_CHECKING:
    from taskbridge.models import TaskSuspensionRecord, TaskActionReceipt


class InMemorySuspensionStore(SuspensionStore):
    def __init__(self) -> None:
        self.suspensions: dict[tuple[str, str], TaskSuspensionRecord] = {}

    async def get_suspension(self, task_id: str, suspend_id: str) -> TaskSuspensionRecord:
        return self.suspensions[(task_id, suspend_id)]

    async def accept_action_if_open(
        self,
        task_id: str,
        suspend_id: str,
        client_action_id: str,
    ) -> TaskSuspensionRecord | None:
        record = self.suspensions.get((task_id, suspend_id))
        if not record or record.status != TaskSuspensionStatus.OPEN:
            return None
        
        updated = record.model_copy(update={
            "status": TaskSuspensionStatus.RESOLVED,
            "successful_action_id": client_action_id,
            "resume_handoff_status": ResumeHandoffStatus.PENDING,
            "resolved_at": datetime.now(timezone.utc)
        })
        self.suspensions[(task_id, suspend_id)] = updated
        return updated

    async def mark_resume_dispatched(
        self,
        task_id: str,
        suspend_id: str,
    ) -> TaskSuspensionRecord:
        record = self.suspensions[(task_id, suspend_id)]
        updated = record.model_copy(update={
            "resume_handoff_status": ResumeHandoffStatus.DISPATCHED
        })
        self.suspensions[(task_id, suspend_id)] = updated
        return updated

    async def list_pending_resume_handoffs(self) -> list[TaskSuspensionRecord]:
        return [
            s for s in self.suspensions.values()
            if s.resume_handoff_status == ResumeHandoffStatus.PENDING
        ]

    async def expire_open_suspensions(self, expires_before: datetime) -> int:
        count = 0
        for key, s in self.suspensions.items():
            if s.status == TaskSuspensionStatus.OPEN and s.expires_at and s.expires_at < expires_before:
                self.suspensions[key] = s.model_copy(update={"status": TaskSuspensionStatus.EXPIRED})
                count += 1
        return count


class InMemoryActionReceiptStore(ActionReceiptStore):
    def __init__(self) -> None:
        self.receipts: dict[tuple[str, str], TaskActionReceipt] = {}

    async def save_receipt(self, receipt: TaskActionReceipt) -> None:
        self.receipts[(receipt.task_id, receipt.client_action_id)] = receipt

    async def get_receipt(self, task_id: str, client_action_id: str) -> TaskActionReceipt | None:
        return self.receipts.get((task_id, client_action_id))

    async def delete_expired_receipts(self, expires_before: datetime) -> int:
        to_delete = [
            key for key, r in self.receipts.items()
            if r.expires_at and r.expires_at < expires_before
        ]
        for key in to_delete:
            del self.receipts[key]
        return len(to_delete)
