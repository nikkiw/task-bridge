from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

import pytest

from taskbridge.models import (
    ResumeHandoffStatus,
    SubmitActionCommand,
    TaskActionReceipt,
    TaskActionReceiptStatus,
    TaskSuspensionKind,
    TaskSuspensionRecord,
    TaskSuspensionStatus,
)
from taskbridge.services import TaskResumeReconciliationService


class FakeSuspensionStore:
    def __init__(self, suspensions: list[TaskSuspensionRecord]) -> None:
        self.suspensions = suspensions
        self.marked_dispatched: list[tuple[str, str]] = []

    async def list_pending_resume_handoffs(self) -> list[TaskSuspensionRecord]:
        return [
            s for s in self.suspensions if s.resume_handoff_status == ResumeHandoffStatus.PENDING
        ]

    async def mark_resume_dispatched(self, task_id: str, suspend_id: str) -> TaskSuspensionRecord:
        self.marked_dispatched.append((task_id, suspend_id))
        for i, s in enumerate(self.suspensions):
            if s.task_id == task_id and s.suspend_id == suspend_id:
                self.suspensions[i] = s.model_copy(
                    update={"resume_handoff_status": ResumeHandoffStatus.DISPATCHED}
                )
                return self.suspensions[i]
        raise KeyError(suspend_id)


class FakeActionReceiptStore:
    def __init__(self, receipts: dict[tuple[str, str], TaskActionReceipt]) -> None:
        self.receipts = receipts

    async def get_receipt(self, task_id: str, client_action_id: str) -> TaskActionReceipt | None:
        return self.receipts.get((task_id, client_action_id))


class RecordingResumeService:
    def __init__(self, fail: bool = False) -> None:
        self.calls: list[tuple[SubmitActionCommand, TaskSuspensionRecord]] = []
        self.fail = fail

    async def resume_task(
        self,
        command: SubmitActionCommand,
        suspension: TaskSuspensionRecord,
    ) -> None:
        self.calls.append((command, suspension))
        if self.fail:
            raise RuntimeError("Resume failed")


@pytest.mark.asyncio
async def test_reconciliation_service_handles_resume_failure(caplog: Any) -> None:
    import logging

    caplog.set_level(logging.INFO)

    now = datetime.now(timezone.utc)
    suspension = TaskSuspensionRecord(
        task_id="t1",
        suspend_id="s1",
        owner_id="u1",
        status=TaskSuspensionStatus.OPEN,
        kind=TaskSuspensionKind.USER_ACTION_REQUIRED,
        reason_code="test",
        allowed_actions=["confirm"],
        schema_version=1,
        created_at=now,
        successful_action_id="a1",
        resume_handoff_status=ResumeHandoffStatus.PENDING,
    )
    receipt = TaskActionReceipt(
        task_id="t1",
        suspend_id="s1",
        client_action_id="a1",
        action_type="confirm",
        payload={},
        actor_id="u1",
        status=TaskActionReceiptStatus.ACCEPTED,
        created_at=now,
    )

    suspension_store = FakeSuspensionStore([suspension])
    receipt_store = FakeActionReceiptStore({("t1", "a1"): receipt})
    resume_service = RecordingResumeService(fail=True)

    service = TaskResumeReconciliationService(
        receipt_store=receipt_store,  # type: ignore
        suspension_store=suspension_store,  # type: ignore
        resume_service=resume_service,  # type: ignore
    )

    with pytest.raises(RuntimeError, match="Resume failed"):
        await service.reconcile_pending_resumes()

    # Verify it was not marked as dispatched if resume failed
    assert len(suspension_store.marked_dispatched) == 0
    assert suspension_store.suspensions[0].resume_handoff_status == ResumeHandoffStatus.PENDING


@pytest.mark.asyncio
async def test_reconciliation_service_skips_missing_receipt(caplog: Any) -> None:
    now = datetime.now(timezone.utc)
    suspension = TaskSuspensionRecord(
        task_id="t1",
        suspend_id="s1",
        owner_id="u1",
        status=TaskSuspensionStatus.OPEN,
        kind=TaskSuspensionKind.USER_ACTION_REQUIRED,
        reason_code="test",
        allowed_actions=["confirm"],
        schema_version=1,
        created_at=now,
        successful_action_id="a1",
        resume_handoff_status=ResumeHandoffStatus.PENDING,
    )

    suspension_store = FakeSuspensionStore([suspension])
    receipt_store = FakeActionReceiptStore({})
    resume_service = RecordingResumeService()

    service = TaskResumeReconciliationService(
        receipt_store=receipt_store,  # type: ignore
        suspension_store=suspension_store,  # type: ignore
        resume_service=resume_service,  # type: ignore
    )

    processed = await service.reconcile_pending_resumes()

    assert processed == 0
    assert len(resume_service.calls) == 0
    assert len(suspension_store.marked_dispatched) == 0


@pytest.mark.asyncio
async def test_reconciliation_service_logs_on_success(caplog: Any) -> None:
    import logging

    caplog.set_level(logging.INFO)

    now = datetime.now(timezone.utc)
    suspension = TaskSuspensionRecord(
        task_id="t1",
        suspend_id="s1",
        owner_id="u1",
        status=TaskSuspensionStatus.OPEN,
        kind=TaskSuspensionKind.USER_ACTION_REQUIRED,
        reason_code="test",
        allowed_actions=["confirm"],
        schema_version=1,
        created_at=now,
        successful_action_id="a1",
        resume_handoff_status=ResumeHandoffStatus.PENDING,
    )
    receipt = TaskActionReceipt(
        task_id="t1",
        suspend_id="s1",
        client_action_id="a1",
        action_type="confirm",
        payload={},
        actor_id="u1",
        status=TaskActionReceiptStatus.ACCEPTED,
        created_at=now,
    )

    suspension_store = FakeSuspensionStore([suspension])
    receipt_store = FakeActionReceiptStore({("t1", "a1"): receipt})
    resume_service = RecordingResumeService()

    service = TaskResumeReconciliationService(
        receipt_store=receipt_store,  # type: ignore
        suspension_store=suspension_store,  # type: ignore
        resume_service=resume_service,  # type: ignore
    )

    processed = await service.reconcile_pending_resumes()

    assert processed == 1

    # Verify logs
    reconcile_logs = [r for r in caplog.records if r.message == "task.resume.reconciled"]
    assert len(reconcile_logs) == 1
    log = reconcile_logs[0]
    assert log.taskbridge["taskId"] == "t1"
    assert log.taskbridge["clientRequestId"] == "a1"
    assert log.taskbridge["details"]["suspendId"] == "s1"
