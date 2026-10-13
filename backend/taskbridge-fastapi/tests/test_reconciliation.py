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
from taskbridge.services import TaskResumeReconciliationService, TaskResumeService


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
        self.receipts = {key: receipt.model_copy(deep=True) for key, receipt in receipts.items()}

    async def get_receipt(self, task_id: str, client_action_id: str) -> TaskActionReceipt | None:
        receipt = self.receipts.get((task_id, client_action_id))
        return receipt.model_copy(deep=True) if receipt is not None else None


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
        payload={"context": {"answer": "yes", "nested": ["a"]}},
        metadata={"modelSnapshot": {"answer": "yes", "nested": {"selection": ["a"]}}},
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
    command, dispatched_suspension = resume_service.calls[0]
    assert command.action_type == "confirm"
    assert command.payload == {"context": {"answer": "yes", "nested": ["a"]}}
    assert command.metadata == {"modelSnapshot": {"answer": "yes", "nested": {"selection": ["a"]}}}
    assert command.auth_context.subject == "u1"
    assert command.auth_context.scopes == set()
    assert command.auth_context.app_id is None
    assert dispatched_suspension.suspend_id == "s1"

    # Verify logs
    reconcile_logs = [r for r in caplog.records if r.message == "task.resume.reconciled"]
    assert len(reconcile_logs) == 1
    log = reconcile_logs[0]
    assert log.taskbridge["taskId"] == "t1"
    assert log.taskbridge["clientRequestId"] == "a1"
    assert log.taskbridge["details"]["suspendId"] == "s1"


@pytest.mark.asyncio
async def test_reconciliation_skips_legacy_a2ui_snapshot_and_continues_other_handoffs() -> None:
    now = datetime.now(timezone.utc)
    legacy = TaskSuspensionRecord(
        task_id="t1",
        suspend_id="s-a2ui",
        owner_id="u1",
        status=TaskSuspensionStatus.RESOLVED,
        kind=TaskSuspensionKind.USER_ACTION_REQUIRED,
        reason_code="test",
        allowed_actions=["a2ui_action"],
        schema_version=1,
        created_at=now,
        successful_action_id="a-a2ui",
        resume_handoff_status=ResumeHandoffStatus.PENDING,
    )
    ordinary = TaskSuspensionRecord(
        task_id="t1",
        suspend_id="s-confirm",
        owner_id="u1",
        status=TaskSuspensionStatus.RESOLVED,
        kind=TaskSuspensionKind.USER_ACTION_REQUIRED,
        reason_code="test",
        allowed_actions=["confirm"],
        schema_version=1,
        created_at=now,
        successful_action_id="a-confirm",
        resume_handoff_status=ResumeHandoffStatus.PENDING,
    )
    legacy_receipt = TaskActionReceipt(
        task_id="t1",
        suspend_id="s-a2ui",
        client_action_id="a-a2ui",
        action_type="a2ui_action",
        payload={"name": "submit_input"},
        actor_id="u1",
        status=TaskActionReceiptStatus.ACCEPTED,
        created_at=now,
    )
    ordinary_receipt = TaskActionReceipt(
        task_id="t1",
        suspend_id="s-confirm",
        client_action_id="a-confirm",
        action_type="confirm",
        payload={},
        actor_id="u1",
        status=TaskActionReceiptStatus.ACCEPTED,
        created_at=now,
    )
    suspension_store = FakeSuspensionStore([legacy, ordinary])
    receipt_store = FakeActionReceiptStore(
        {
            ("t1", "a-a2ui"): legacy_receipt,
            ("t1", "a-confirm"): ordinary_receipt,
        }
    )
    resume_service = RecordingResumeService()
    service = TaskResumeReconciliationService(
        receipt_store=receipt_store,  # type: ignore[arg-type]
        suspension_store=suspension_store,  # type: ignore[arg-type]
        resume_service=resume_service,  # type: ignore[arg-type]
    )

    processed = await service.reconcile_pending_resumes()

    assert processed == 1
    assert suspension_store.suspensions[0].resume_handoff_status == ResumeHandoffStatus.PENDING
    assert suspension_store.suspensions[1].resume_handoff_status == ResumeHandoffStatus.DISPATCHED
    assert suspension_store.marked_dispatched == [("t1", "s-confirm")]
    assert len(resume_service.calls) == 1
    assert resume_service.calls[0][0].action_type == "confirm"


@pytest.mark.asyncio
async def test_a2ui_reconciliation_requires_host_resume_override_and_recovers() -> None:
    class InheritedNoOpResumeService(TaskResumeService):
        pass

    now = datetime.now(timezone.utc)
    suspension = TaskSuspensionRecord(
        task_id="t1",
        suspend_id="s1",
        owner_id="u1",
        status=TaskSuspensionStatus.RESOLVED,
        kind=TaskSuspensionKind.USER_ACTION_REQUIRED,
        reason_code="test",
        allowed_actions=["a2ui_action"],
        schema_version=1,
        created_at=now,
        successful_action_id="a1",
        resume_handoff_status=ResumeHandoffStatus.PENDING,
    )
    receipt = TaskActionReceipt(
        task_id="t1",
        suspend_id="s1",
        client_action_id="a1",
        action_type="a2ui_action",
        payload={"name": "submit_input"},
        metadata={"modelSnapshot": {"answer": "yes", "nested": ["a"]}},
        actor_id="u1",
        status=TaskActionReceiptStatus.ACCEPTED,
        created_at=now,
    )
    suspension_store = FakeSuspensionStore([suspension])
    receipt_store = FakeActionReceiptStore({("t1", "a1"): receipt})
    no_op_service = TaskResumeReconciliationService(
        receipt_store=receipt_store,  # type: ignore[arg-type]
        suspension_store=suspension_store,  # type: ignore[arg-type]
        resume_service=InheritedNoOpResumeService(),
    )

    with pytest.raises(RuntimeError, match="A2UI actions require a host resume service"):
        await no_op_service.reconcile_pending_resumes()
    assert suspension_store.suspensions[0].resume_handoff_status == ResumeHandoffStatus.PENDING
    assert suspension_store.marked_dispatched == []

    host_resume_service = RecordingResumeService()
    hosted_service = TaskResumeReconciliationService(
        receipt_store=receipt_store,  # type: ignore[arg-type]
        suspension_store=suspension_store,  # type: ignore[arg-type]
        resume_service=host_resume_service,  # type: ignore[arg-type]
    )
    assert await hosted_service.reconcile_pending_resumes() == 1
    assert suspension_store.suspensions[0].resume_handoff_status == ResumeHandoffStatus.DISPATCHED
    assert suspension_store.marked_dispatched == [("t1", "s1")]
    assert host_resume_service.calls[0][0].metadata == {
        "modelSnapshot": {"answer": "yes", "nested": ["a"]}
    }
    assert await hosted_service.reconcile_pending_resumes() == 0
