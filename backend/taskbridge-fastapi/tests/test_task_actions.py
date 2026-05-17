from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

import pytest

from taskbridge.errors import IdempotencyConflictError, TaskNotFoundError
from taskbridge.models import (
    AuthContext,
    ResumeHandoffStatus,
    SubmitActionCommand,
    SubmitActionResultStatus,
    TaskActionReceipt,
    TaskActionReceiptStatus,
    TaskRecord,
    TaskStatus,
    TaskSuspensionRecord,
    TaskSuspensionStatus,
)
from taskbridge.services import (
    TaskActionService,
    TaskResumeReconciliationService,
    TaskRetentionService,
)

from .test_interfaces import FakeEventStore, FakeOwnershipPolicy, FakeTaskRegistry


class FakeSuspensionStore:
    def __init__(self, suspension: TaskSuspensionRecord | None = None) -> None:
        self.suspension = suspension
        self.cas_calls: list[str] = []
        self.mark_dispatched_calls: list[str] = []

    async def get_suspension(self, task_id: str, suspend_id: str) -> TaskSuspensionRecord:
        if (
            self.suspension is None
            or self.suspension.task_id != task_id
            or self.suspension.suspend_id != suspend_id
        ):
            raise KeyError(suspend_id)
        return self.suspension

    async def accept_action_if_open(
        self,
        task_id: str,
        suspend_id: str,
        client_action_id: str,
    ) -> TaskSuspensionRecord | None:
        self.cas_calls.append(client_action_id)
        suspension = await self.get_suspension(task_id, suspend_id)
        if suspension.status != TaskSuspensionStatus.OPEN:
            return None
        self.suspension = suspension.model_copy(
            update={
                "successful_action_id": client_action_id,
                "resume_handoff_status": ResumeHandoffStatus.PENDING,
            }
        )
        return self.suspension

    async def mark_resume_dispatched(
        self,
        task_id: str,
        suspend_id: str,
    ) -> TaskSuspensionRecord:
        self.mark_dispatched_calls.append(suspend_id)
        suspension = await self.get_suspension(task_id, suspend_id)
        self.suspension = suspension.model_copy(
            update={"resume_handoff_status": ResumeHandoffStatus.DISPATCHED}
        )
        return self.suspension

    async def list_pending_resume_handoffs(self) -> list[TaskSuspensionRecord]:
        if (
            self.suspension is None
            or self.suspension.successful_action_id is None
            or self.suspension.resume_handoff_status != ResumeHandoffStatus.PENDING
        ):
            return []
        return [self.suspension]

    async def expire_open_suspensions(self, expires_before: datetime) -> int:
        if (
            self.suspension is None
            or self.suspension.status != TaskSuspensionStatus.OPEN
            or self.suspension.expires_at is None
            or self.suspension.expires_at > expires_before
        ):
            return 0
        self.suspension = self.suspension.model_copy(
            update={"status": TaskSuspensionStatus.EXPIRED}
        )
        return 1


class FakeActionReceiptStore:
    def __init__(self) -> None:
        self.receipts: dict[tuple[str, str], TaskActionReceipt] = {}

    async def get_receipt(self, task_id: str, client_action_id: str) -> TaskActionReceipt | None:
        return self.receipts.get((task_id, client_action_id))

    async def save_receipt(self, receipt: TaskActionReceipt) -> TaskActionReceipt:
        self.receipts[(receipt.task_id, receipt.client_action_id)] = receipt
        return receipt

    async def delete_expired_receipts(self, expires_before: datetime) -> int:
        expired_keys = [
            key
            for key, receipt in self.receipts.items()
            if receipt.expires_at is not None and receipt.expires_at <= expires_before
        ]
        for key in expired_keys:
            del self.receipts[key]
        return len(expired_keys)


class RecordingResumeService:
    def __init__(self, *, fail_first: bool = False) -> None:
        self.calls: list[dict[str, Any]] = []
        self.fail_first = fail_first

    async def resume_task(
        self,
        command: SubmitActionCommand,
        suspension: TaskSuspensionRecord,
    ) -> None:
        if self.fail_first:
            self.fail_first = False
            raise RuntimeError("resume dispatch failed")
        self.calls.append({"command": command, "suspension": suspension})


def task_record() -> TaskRecord:
    now = datetime.now(timezone.utc)
    return TaskRecord(
        task_id="task-1",
        client_request_id="req-1",
        task_type="demo",
        input_payload={},
        owner_id="user-1",
        status=TaskStatus.RUNNING,
        created_at=now,
        updated_at=now,
    )


def suspension_record() -> TaskSuspensionRecord:
    now = datetime.now(timezone.utc)
    return TaskSuspensionRecord(
        task_id="task-1",
        suspend_id="suspend-1",
        owner_id="user-1",
        status=TaskSuspensionStatus.OPEN,
        kind="USER_ACTION_REQUIRED",
        reason_code="clarification_required",
        allowed_actions=["submit_form"],
        schema_version=1,
        interaction_payload={"question": "Continue?"},
        ui_hints={},
        resume_token="internal-token",
        successful_action_id=None,
        resume_handoff_status=ResumeHandoffStatus.NONE,
        created_at=now,
        expires_at=None,
        resolved_at=None,
    )


def submit_command(client_action_id: str = "action-1") -> SubmitActionCommand:
    return SubmitActionCommand(
        task_id="task-1",
        client_action_id=client_action_id,
        suspend_id="suspend-1",
        action_type="submit_form",
        payload={"answer": "yes"},
        metadata={},
        auth_context=AuthContext(subject="user-1", scopes={"tasks:read", "tasks:write"}),
    )


async def build_service() -> tuple[
    TaskActionService,
    FakeSuspensionStore,
    FakeActionReceiptStore,
    FakeEventStore,
    RecordingResumeService,
]:
    registry = FakeTaskRegistry()
    await registry.create_task(task_record())
    suspension_store = FakeSuspensionStore(suspension_record())
    receipt_store = FakeActionReceiptStore()
    event_store = FakeEventStore()
    resume_service = RecordingResumeService()
    service = TaskActionService(
        registry=registry,
        suspension_store=suspension_store,
        receipt_store=receipt_store,
        event_store=event_store,
        ownership_policy=FakeOwnershipPolicy(),
        resume_service=resume_service,
    )
    return service, suspension_store, receipt_store, event_store, resume_service


@pytest.mark.anyio
async def test_submit_action_accepts_with_cas_appends_ack_event_and_resumes() -> None:
    service, suspension_store, receipt_store, event_store, resume_service = await build_service()

    result = await service.submit_action(submit_command())

    assert result.status == SubmitActionResultStatus.ACCEPTED
    assert suspension_store.suspension.successful_action_id == "action-1"
    assert suspension_store.suspension.resume_handoff_status == ResumeHandoffStatus.DISPATCHED
    assert receipt_store.receipts[("task-1", "action-1")].status == TaskActionReceiptStatus.ACCEPTED
    assert event_store.events[0].type == "TASK_ACTION_ACCEPTED"
    assert event_store.events[0].payload["suspendId"] == "suspend-1"
    assert event_store.events[0].payload["clientActionId"] == "action-1"
    assert len(resume_service.calls) == 1
    assert suspension_store.suspension.resume_handoff_status == ResumeHandoffStatus.DISPATCHED
    assert suspension_store.mark_dispatched_calls == ["suspend-1"]


@pytest.mark.anyio
async def test_submit_action_deduplicates_same_client_action_without_duplicate_event() -> None:
    service, _, _, event_store, resume_service = await build_service()
    await service.submit_action(submit_command())

    result = await service.submit_action(submit_command())

    assert result.status == SubmitActionResultStatus.DEDUPLICATED
    assert len(event_store.events) == 1
    assert len(resume_service.calls) == 1


@pytest.mark.anyio
async def test_submit_action_rejects_reused_client_action_with_conflicting_payload() -> None:
    service, _, _, _, _ = await build_service()
    await service.submit_action(submit_command())
    conflicting = submit_command()
    conflicting.payload["answer"] = "no"

    with pytest.raises(IdempotencyConflictError):
        await service.submit_action(conflicting)


@pytest.mark.anyio
async def test_submit_action_masks_unknown_suspension_as_not_found() -> None:
    service, suspension_store, _, _, _ = await build_service()
    suspension_store.suspension = None

    with pytest.raises(TaskNotFoundError):
        await service.submit_action(submit_command())


@pytest.mark.anyio
async def test_submit_action_leaves_pending_handoff_for_reconciliation_when_resume_fails() -> None:
    registry = FakeTaskRegistry()
    await registry.create_task(task_record())
    suspension_store = FakeSuspensionStore(suspension_record())
    receipt_store = FakeActionReceiptStore()
    event_store = FakeEventStore()
    resume_service = RecordingResumeService(fail_first=True)
    service = TaskActionService(
        registry=registry,
        suspension_store=suspension_store,
        receipt_store=receipt_store,
        event_store=event_store,
        ownership_policy=FakeOwnershipPolicy(),
        resume_service=resume_service,
    )

    result = await service.submit_action(submit_command())

    assert result.status == SubmitActionResultStatus.ACCEPTED
    assert suspension_store.suspension.resume_handoff_status == ResumeHandoffStatus.PENDING
    assert suspension_store.mark_dispatched_calls == []
    assert len(event_store.events) == 1


@pytest.mark.anyio
async def test_reconciliation_retries_pending_resume_and_marks_dispatched() -> None:
    registry = FakeTaskRegistry()
    await registry.create_task(task_record())
    suspension_store = FakeSuspensionStore(suspension_record())
    receipt_store = FakeActionReceiptStore()
    now = datetime.now(timezone.utc)
    receipt = TaskActionReceipt(
        task_id="task-1",
        suspend_id="suspend-1",
        client_action_id="action-1",
        action_type="submit_form",
        payload={"answer": "yes"},
        actor_id="user-1",
        status=TaskActionReceiptStatus.ACCEPTED,
        created_at=now,
        processed_at=now,
    )
    await receipt_store.save_receipt(receipt)
    suspension_store.suspension = suspension_store.suspension.model_copy(
        update={
            "successful_action_id": "action-1",
            "resume_handoff_status": ResumeHandoffStatus.PENDING,
        }
    )
    resume_service = RecordingResumeService()
    reconciliation = TaskResumeReconciliationService(
        receipt_store=receipt_store,
        suspension_store=suspension_store,
        resume_service=resume_service,
    )

    processed = await reconciliation.reconcile_pending_resumes()

    assert processed == 1
    assert suspension_store.suspension.resume_handoff_status == ResumeHandoffStatus.DISPATCHED
    assert suspension_store.mark_dispatched_calls == ["suspend-1"]
    assert len(resume_service.calls) == 1


@pytest.mark.anyio
async def test_retention_service_prunes_expired_receipts_and_suspensions() -> None:
    now = datetime.now(timezone.utc)
    suspension_store = FakeSuspensionStore(
        suspension_record().model_copy(
            update={"expires_at": now, "status": TaskSuspensionStatus.OPEN}
        )
    )
    receipt_store = FakeActionReceiptStore()
    await receipt_store.save_receipt(
        TaskActionReceipt(
            task_id="task-1",
            suspend_id="suspend-1",
            client_action_id="action-1",
            action_type="submit_form",
            payload={"answer": "yes"},
            actor_id="user-1",
            status=TaskActionReceiptStatus.ACCEPTED,
            created_at=now,
            processed_at=now,
            expires_at=now,
        )
    )
    retention = TaskRetentionService(
        receipt_store=receipt_store,
        suspension_store=suspension_store,
    )

    result = await retention.prune_expired_state(now=now)

    assert result == {"expired_receipts": 1, "expired_suspensions": 1}
    assert receipt_store.receipts == {}
    assert suspension_store.suspension.status == TaskSuspensionStatus.EXPIRED
