from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any

import pytest

from taskbridge.models import (
    ResumeHandoffStatus,
    TaskActionReceipt,
    TaskActionReceiptStatus,
    TaskSuspensionKind,
    TaskSuspensionRecord,
    TaskSuspensionStatus,
)
from taskbridge.services import TaskRetentionService


class FakeActionReceiptStore:
    def __init__(self) -> None:
        self.receipts: list[TaskActionReceipt] = []

    async def delete_expired_receipts(self, expires_before: datetime) -> int:
        initial_count = len(self.receipts)
        self.receipts = [
            r for r in self.receipts if r.expires_at is None or r.expires_at > expires_before
        ]
        return initial_count - len(self.receipts)


class FakeSuspensionStore:
    def __init__(self) -> None:
        self.suspensions: list[TaskSuspensionRecord] = []

    async def expire_open_suspensions(self, expires_before: datetime) -> int:
        count = 0
        for i, s in enumerate(self.suspensions):
            if (
                s.status == TaskSuspensionStatus.OPEN
                and s.expires_at is not None
                and s.expires_at <= expires_before
            ):
                self.suspensions[i] = s.model_copy(update={"status": TaskSuspensionStatus.EXPIRED})
                count += 1
        return count


@pytest.mark.asyncio
async def test_retention_service_prunes_expired_state(caplog: Any) -> None:
    import logging

    caplog.set_level(logging.INFO)

    receipt_store = FakeActionReceiptStore()
    suspension_store = FakeSuspensionStore()
    service = TaskRetentionService(
        receipt_store=receipt_store,  # type: ignore
        suspension_store=suspension_store,  # type: ignore
    )

    now = datetime.now(timezone.utc)
    past = now - timedelta(minutes=10)
    future = now + timedelta(minutes=10)

    # Setup expired receipt
    receipt_store.receipts.append(
        TaskActionReceipt(
            task_id="t1",
            suspend_id="s1",
            client_action_id="a1",
            action_type="confirm",
            payload={},
            actor_id="u1",
            status=TaskActionReceiptStatus.ACCEPTED,
            created_at=past,
            expires_at=past,
        )
    )
    # Setup active receipt
    receipt_store.receipts.append(
        TaskActionReceipt(
            task_id="t1",
            suspend_id="s1",
            client_action_id="a2",
            action_type="confirm",
            payload={},
            actor_id="u1",
            status=TaskActionReceiptStatus.ACCEPTED,
            created_at=past,
            expires_at=future,
        )
    )

    # Setup expired suspension
    suspension_store.suspensions.append(
        TaskSuspensionRecord(
            task_id="t1",
            suspend_id="s1",
            owner_id="u1",
            status=TaskSuspensionStatus.OPEN,
            kind=TaskSuspensionKind.USER_ACTION_REQUIRED,
            reason_code="test",
            allowed_actions=["confirm"],
            schema_version=1,
            created_at=past,
            expires_at=past,
            resume_handoff_status=ResumeHandoffStatus.NONE,
        )
    )
    # Setup active suspension
    suspension_store.suspensions.append(
        TaskSuspensionRecord(
            task_id="t1",
            suspend_id="s2",
            owner_id="u1",
            status=TaskSuspensionStatus.OPEN,
            kind=TaskSuspensionKind.USER_ACTION_REQUIRED,
            reason_code="test",
            allowed_actions=["confirm"],
            schema_version=1,
            created_at=past,
            expires_at=future,
            resume_handoff_status=ResumeHandoffStatus.NONE,
        )
    )

    result = await service.prune_expired_state(now=now)

    assert result["expired_receipts"] == 1
    assert result["expired_suspensions"] == 1
    assert len(receipt_store.receipts) == 1
    assert suspension_store.suspensions[0].status == TaskSuspensionStatus.EXPIRED
    assert suspension_store.suspensions[1].status == TaskSuspensionStatus.OPEN

    # Verify logs
    prune_logs = [r for r in caplog.records if r.message == "task.retention.pruned"]
    assert len(prune_logs) == 1
    log = prune_logs[0]
    assert log.taskbridge["details"]["expired_receipts"] == 1
    assert log.taskbridge["details"]["expired_suspensions"] == 1
