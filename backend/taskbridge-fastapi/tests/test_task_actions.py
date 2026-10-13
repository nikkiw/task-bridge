from __future__ import annotations

import asyncio
from datetime import datetime, timezone
from typing import Any

import pytest

from taskbridge import ActionValidationPolicy
from taskbridge.errors import (
    IdempotencyConflictError,
    InvalidRequestError,
    TaskConflictError,
    TaskNotFoundError,
)
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
    TaskResumeService,
    TaskRetentionService,
)

from .test_interfaces import FakeEventStore, FakeOwnershipPolicy, FakeTaskRegistry


class RecordingActionValidationPolicy(ActionValidationPolicy):
    def __init__(self, *, reject: bool = False) -> None:
        self.reject = reject
        self.calls: list[tuple[SubmitActionCommand, TaskRecord, TaskSuspensionRecord]] = []

    async def validate_action(
        self,
        command: SubmitActionCommand,
        task: TaskRecord,
        suspension: TaskSuspensionRecord,
    ) -> None:
        self.calls.append((command, task, suspension))
        if self.reject:
            raise InvalidRequestError("host rejected the action")


class BlockedActionValidationPolicy(RecordingActionValidationPolicy):
    def __init__(self, blocked_command: SubmitActionCommand) -> None:
        super().__init__()
        self.blocked_command = blocked_command
        self.blocked_call_waiting = asyncio.Event()
        self.allow_blocked_call = asyncio.Event()

    async def validate_action(
        self,
        command: SubmitActionCommand,
        task: TaskRecord,
        suspension: TaskSuspensionRecord,
    ) -> None:
        await super().validate_action(command, task, suspension)
        if command is self.blocked_command:
            self.blocked_call_waiting.set()
            await self.allow_blocked_call.wait()


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
                "status": TaskSuspensionStatus.RESOLVED,
                "successful_action_id": client_action_id,
                "resume_handoff_status": ResumeHandoffStatus.PENDING,
                "resolved_at": datetime.now(timezone.utc),
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
        receipt = self.receipts.get((task_id, client_action_id))
        return receipt.model_copy(deep=True) if receipt is not None else None

    async def save_receipt(self, receipt: TaskActionReceipt) -> TaskActionReceipt:
        snapshot = receipt.model_copy(deep=True)
        self.receipts[(receipt.task_id, receipt.client_action_id)] = snapshot
        return snapshot.model_copy(deep=True)

    async def delete_expired_receipts(self, expires_before: datetime) -> int:
        expired_keys = [
            key
            for key, receipt in self.receipts.items()
            if receipt.expires_at is not None and receipt.expires_at <= expires_before
        ]
        for key in expired_keys:
            del self.receipts[key]
        return len(expired_keys)


class InitialReceiptReadBarrier(FakeActionReceiptStore):
    def __init__(self) -> None:
        super().__init__()
        self.initial_reads = 0
        self.initial_reads_ready = asyncio.Event()
        self.first_read_completed = asyncio.Event()
        self.second_read_completed = asyncio.Event()
        self.first_read_returned = asyncio.Event()
        self.initial_read_results: list[TaskActionReceipt | None] = []

    async def get_receipt(
        self,
        task_id: str,
        client_action_id: str,
    ) -> TaskActionReceipt | None:
        read_number = 0
        if self.initial_reads < 2:
            self.initial_reads += 1
            read_number = self.initial_reads
            if read_number == 2:
                self.initial_reads_ready.set()
            await self.initial_reads_ready.wait()
            if read_number == 2:
                await self.first_read_completed.wait()

        receipt = await super().get_receipt(task_id, client_action_id)
        if read_number == 1:
            self.initial_read_results.append(receipt)
            self.first_read_completed.set()
            await self.second_read_completed.wait()
            self.first_read_returned.set()
        elif read_number == 2:
            self.initial_read_results.append(receipt)
            self.second_read_completed.set()
            await self.first_read_returned.wait()
        return receipt


class ReceiptSavedBarrierStore(InitialReceiptReadBarrier):
    def __init__(self) -> None:
        super().__init__()
        self.receipt_saved = asyncio.Event()
        self.allow_save_to_return = asyncio.Event()

    async def save_receipt(self, receipt: TaskActionReceipt) -> TaskActionReceipt:
        saved = await super().save_receipt(receipt)
        self.receipt_saved.set()
        await self.allow_save_to_return.wait()
        return saved


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


async def build_service(
    *,
    action_validation_policy: ActionValidationPolicy | None = None,
    suspension: TaskSuspensionRecord | None = None,
) -> tuple[
    TaskActionService,
    FakeSuspensionStore,
    FakeActionReceiptStore,
    FakeEventStore,
    RecordingResumeService,
]:
    registry = FakeTaskRegistry()
    await registry.create_task(task_record())
    suspension_store = FakeSuspensionStore(
        suspension if suspension is not None else suspension_record()
    )
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
        action_validation_policy=action_validation_policy,
    )
    return service, suspension_store, receipt_store, event_store, resume_service


@pytest.mark.anyio
async def test_submit_action_accepts_with_cas_appends_ack_event_and_resumes() -> None:
    service, suspension_store, receipt_store, event_store, resume_service = await build_service()

    result = await service.submit_action(submit_command())

    assert result.status == SubmitActionResultStatus.ACCEPTED
    assert suspension_store.suspension.status == TaskSuspensionStatus.RESOLVED
    assert suspension_store.suspension.resolved_at is not None
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
async def test_configured_action_validation_policy_allows_new_action() -> None:
    policy = RecordingActionValidationPolicy()
    service, _, _, _, _ = await build_service(action_validation_policy=policy)
    command = submit_command()

    result = await service.submit_action(command)

    assert result.status == SubmitActionResultStatus.ACCEPTED
    assert len(policy.calls) == 1
    validated_command, task, suspension = policy.calls[0]
    assert validated_command.action_type == "submit_form"
    assert task.task_id == command.task_id
    assert suspension.suspend_id == command.suspend_id


@pytest.mark.anyio
async def test_action_validation_policy_rejection_precedes_all_mutations() -> None:
    policy = RecordingActionValidationPolicy(reject=True)
    service, suspension_store, receipt_store, event_store, resume_service = await build_service(
        action_validation_policy=policy
    )

    with pytest.raises(InvalidRequestError, match="host rejected the action"):
        await service.submit_action(submit_command())

    assert len(policy.calls) == 1
    assert suspension_store.cas_calls == []
    assert receipt_store.receipts == {}
    assert event_store.events == []
    assert resume_service.calls == []
    assert suspension_store.suspension.status == TaskSuspensionStatus.OPEN


@pytest.mark.anyio
async def test_a2ui_without_policy_rejects_missing_snapshot_even_for_legacy_receipt() -> None:
    suspension = suspension_record().model_copy(update={"allowed_actions": ["a2ui_action"]})
    service, suspension_store, receipt_store, event_store, resume_service = await build_service(
        suspension=suspension
    )
    legacy_receipt = TaskActionReceipt(
        task_id="task-1",
        suspend_id="suspend-1",
        client_action_id="action-1",
        action_type="a2ui_action",
        payload={"name": "submit_input"},
        actor_id="user-1",
        status=TaskActionReceiptStatus.ACCEPTED,
        created_at=datetime.now(timezone.utc),
    )
    receipt_store.receipts[("task-1", "action-1")] = legacy_receipt
    missing_snapshot = submit_command()
    missing_snapshot.action_type = "a2ui_action"

    with pytest.raises(InvalidRequestError, match="action metadata"):
        await service.submit_action(missing_snapshot)

    no_policy = submit_command("action-2")
    no_policy.action_type = "a2ui_action"
    no_policy.metadata = {"modelSnapshot": {"answer": "yes"}}
    with pytest.raises(InvalidRequestError, match="host action validation policy"):
        await service.submit_action(no_policy)

    assert list(receipt_store.receipts) == [("task-1", "action-1")]
    assert suspension_store.cas_calls == []
    assert event_store.events == []
    assert resume_service.calls == []
    assert suspension_store.suspension.status == TaskSuspensionStatus.OPEN


@pytest.mark.anyio
async def test_a2ui_requires_host_resume_override_and_recovers_when_configured() -> None:
    class InheritedNoOpResumeService(TaskResumeService):
        pass

    registry = FakeTaskRegistry()
    await registry.create_task(task_record())
    suspension_store = FakeSuspensionStore(
        suspension_record().model_copy(update={"allowed_actions": ["a2ui_action"]})
    )
    receipt_store = FakeActionReceiptStore()
    event_store = FakeEventStore()
    policy = RecordingActionValidationPolicy()
    command = submit_command()
    command.action_type = "a2ui_action"
    command.metadata = {"modelSnapshot": {"answer": "yes", "nested": {"selection": ["a"]}}}
    no_op_service = TaskActionService(
        registry=registry,
        suspension_store=suspension_store,
        receipt_store=receipt_store,
        event_store=event_store,
        ownership_policy=FakeOwnershipPolicy(),
        resume_service=InheritedNoOpResumeService(),
        action_validation_policy=policy,
    )

    with pytest.raises(RuntimeError, match="A2UI actions require a host resume service"):
        await no_op_service.submit_action(command)

    assert len(policy.calls) == 1
    assert suspension_store.cas_calls == []
    assert receipt_store.receipts == {}
    assert event_store.events == []
    assert suspension_store.suspension.status == TaskSuspensionStatus.OPEN

    host_resume_service = RecordingResumeService()
    hosted_service = TaskActionService(
        registry=registry,
        suspension_store=suspension_store,
        receipt_store=receipt_store,
        event_store=event_store,
        ownership_policy=FakeOwnershipPolicy(),
        resume_service=host_resume_service,
        action_validation_policy=policy,
    )
    result = await hosted_service.submit_action(command)

    assert result.status == SubmitActionResultStatus.ACCEPTED
    assert suspension_store.suspension.resume_handoff_status == ResumeHandoffStatus.DISPATCHED
    assert len(receipt_store.receipts) == 1
    assert len(event_store.events) == 1
    assert len(host_resume_service.calls) == 1


@pytest.mark.anyio
async def test_submit_action_compares_metadata_for_idempotency() -> None:
    service, _, _, _, _ = await build_service()
    accepted = submit_command()
    accepted.metadata = {"snapshot": {"answer": "yes"}}
    await service.submit_action(accepted)

    replay = submit_command()
    replay.metadata = {"snapshot": {"answer": "no"}}

    with pytest.raises(IdempotencyConflictError):
        await service.submit_action(replay)


@pytest.mark.anyio
async def test_submit_action_receipt_isolated_from_mutated_command_metadata() -> None:
    service, _, receipt_store, _, _ = await build_service()
    command = submit_command()
    command.metadata = {"snapshot": {"nested": {"values": ["original"]}}}

    await service.submit_action(command)
    command.metadata["snapshot"]["nested"]["values"][0] = "mutated"

    assert receipt_store.receipts[("task-1", "action-1")].metadata == {
        "snapshot": {"nested": {"values": ["original"]}}
    }


@pytest.mark.anyio
async def test_submit_action_only_accepts_one_action_for_a_suspension() -> None:
    service, suspension_store, receipt_store, event_store, resume_service = await build_service()
    first = await service.submit_action(submit_command("action-1"))
    second = await service.submit_action(submit_command("action-2"))

    assert first.status == SubmitActionResultStatus.ACCEPTED
    assert second.status == SubmitActionResultStatus.REJECTED_ALREADY_RESOLVED
    assert suspension_store.cas_calls == ["action-1"]
    assert len(receipt_store.receipts) == 1
    assert len(event_store.events) == 1
    assert len(resume_service.calls) == 1


@pytest.mark.anyio
async def test_same_action_id_racing_before_receipt_persistence_conflicts_then_replays() -> None:

    class PausedWinnerSuspensionStore(FakeSuspensionStore):
        def __init__(self, suspension: TaskSuspensionRecord) -> None:
            super().__init__(suspension)
            self.winner_accepted = asyncio.Event()
            self.allow_winner_to_continue = asyncio.Event()

        async def accept_action_if_open(
            self,
            task_id: str,
            suspend_id: str,
            client_action_id: str,
        ) -> TaskSuspensionRecord | None:
            accepted = await super().accept_action_if_open(
                task_id,
                suspend_id,
                client_action_id,
            )
            if accepted is not None:
                self.winner_accepted.set()
                await self.allow_winner_to_continue.wait()
            return accepted

    registry = FakeTaskRegistry()
    await registry.create_task(task_record())
    suspension_store = PausedWinnerSuspensionStore(suspension_record())
    receipt_store = InitialReceiptReadBarrier()
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
    winner_command = submit_command()
    winner_command.metadata = {"snapshot": {"answer": "accepted"}}
    conflicting_command = submit_command()
    conflicting_command.metadata = {"snapshot": {"answer": "conflicting"}}

    winner_task = asyncio.create_task(service.submit_action(winner_command))
    conflicting_task = asyncio.create_task(service.submit_action(conflicting_command))
    await suspension_store.winner_accepted.wait()

    with pytest.raises(TaskConflictError) as before_receipt:
        await conflicting_task
    assert before_receipt.value.code == "TASK_CONFLICT"
    assert receipt_store.receipts == {}
    assert event_store.events == []

    with pytest.raises(TaskConflictError) as post_cas_retry:
        await service.submit_action(winner_command)
    assert post_cas_retry.value.code == "TASK_CONFLICT"
    assert receipt_store.receipts == {}

    suspension_store.allow_winner_to_continue.set()
    winner_result = await winner_task
    replay_result = await service.submit_action(winner_command)

    assert winner_result.status == SubmitActionResultStatus.ACCEPTED
    assert replay_result.status == SubmitActionResultStatus.DEDUPLICATED
    with pytest.raises(IdempotencyConflictError):
        await service.submit_action(conflicting_command)
    assert len(receipt_store.receipts) == 1
    assert len(event_store.events) == 1
    assert len(resume_service.calls) == 1


@pytest.mark.anyio
@pytest.mark.parametrize(
    ("loser_metadata", "expect_conflict"),
    [
        ({"snapshot": {"answer": "accepted"}}, False),
        ({"snapshot": {"answer": "different"}}, True),
    ],
)
async def test_cas_loser_compares_persisted_receipt_metadata(
    loser_metadata: dict[str, Any],
    expect_conflict: bool,
) -> None:
    registry = FakeTaskRegistry()
    await registry.create_task(task_record())
    suspension_store = FakeSuspensionStore(suspension_record())
    receipt_store = ReceiptSavedBarrierStore()
    event_store = FakeEventStore()
    resume_service = RecordingResumeService()
    winner_command = submit_command()
    winner_command.metadata = {"snapshot": {"answer": "accepted"}}
    loser_command = submit_command()
    loser_command.metadata = loser_metadata
    validation_policy = BlockedActionValidationPolicy(loser_command)
    service = TaskActionService(
        registry=registry,
        suspension_store=suspension_store,
        receipt_store=receipt_store,
        event_store=event_store,
        ownership_policy=FakeOwnershipPolicy(),
        resume_service=resume_service,
        action_validation_policy=validation_policy,
    )

    winner_task = asyncio.create_task(service.submit_action(winner_command))
    loser_task = asyncio.create_task(service.submit_action(loser_command))
    await receipt_store.receipt_saved.wait()
    await validation_policy.blocked_call_waiting.wait()

    assert receipt_store.initial_read_results == [None, None]
    assert [call[2].status for call in validation_policy.calls] == [
        TaskSuspensionStatus.OPEN,
        TaskSuspensionStatus.OPEN,
    ]
    assert suspension_store.cas_calls == [winner_command.client_action_id]
    assert len(receipt_store.receipts) == 1
    assert event_store.events == []
    assert resume_service.calls == []

    validation_policy.allow_blocked_call.set()
    if expect_conflict:
        with pytest.raises(IdempotencyConflictError):
            await loser_task
    else:
        loser_result = await loser_task
        assert loser_result.status == SubmitActionResultStatus.DEDUPLICATED

    assert suspension_store.cas_calls == [
        winner_command.client_action_id,
        loser_command.client_action_id,
    ]
    assert event_store.events == []
    assert resume_service.calls == []

    receipt_store.allow_save_to_return.set()
    winner_result = await winner_task

    assert winner_result.status == SubmitActionResultStatus.ACCEPTED
    assert len(event_store.events) == 1
    assert len(resume_service.calls) == 1


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
async def test_serialized_receipt_snapshot_survives_resume_failures_and_restart() -> None:
    class JsonActionReceiptStore:
        def __init__(self, records: dict[tuple[str, str], str] | None = None) -> None:
            self.records = records if records is not None else {}

        async def get_receipt(
            self,
            task_id: str,
            client_action_id: str,
        ) -> TaskActionReceipt | None:
            raw = self.records.get((task_id, client_action_id))
            return TaskActionReceipt.model_validate_json(raw) if raw is not None else None

        async def save_receipt(self, receipt: TaskActionReceipt) -> TaskActionReceipt:
            raw = receipt.model_dump_json()
            self.records[(receipt.task_id, receipt.client_action_id)] = raw
            return TaskActionReceipt.model_validate_json(raw)

        async def delete_expired_receipts(self, expires_before: datetime) -> int:
            expired = [
                key
                for key, raw in self.records.items()
                if (
                    (expires_at := TaskActionReceipt.model_validate_json(raw).expires_at)
                    is not None
                    and expires_at <= expires_before
                )
            ]
            for key in expired:
                del self.records[key]
            return len(expired)

    registry = FakeTaskRegistry()
    await registry.create_task(task_record())
    suspension_store = FakeSuspensionStore(suspension_record())
    persisted_receipts = JsonActionReceiptStore()
    event_store = FakeEventStore()
    first_resume_service = RecordingResumeService(fail_first=True)
    accepting_service = TaskActionService(
        registry=registry,
        suspension_store=suspension_store,
        receipt_store=persisted_receipts,
        event_store=event_store,
        ownership_policy=FakeOwnershipPolicy(),
        resume_service=first_resume_service,
    )
    command = submit_command()
    command.payload = {"answer": "yes", "nested": {"selection": ["a"]}}
    command.metadata = {"modelSnapshot": {"answer": "yes", "nested": {"selection": ["a"]}}}

    result = await accepting_service.submit_action(command)

    assert result.status == SubmitActionResultStatus.ACCEPTED
    assert suspension_store.suspension.resume_handoff_status == ResumeHandoffStatus.PENDING
    assert event_store.events[0].type == "TASK_ACTION_ACCEPTED"
    assert len(persisted_receipts.records) == 1

    restarted_receipts = JsonActionReceiptStore(dict(persisted_receipts.records))
    retrying_resume_service = RecordingResumeService(fail_first=True)
    retrying_reconciliation = TaskResumeReconciliationService(
        receipt_store=restarted_receipts,
        suspension_store=suspension_store,
        resume_service=retrying_resume_service,
    )
    with pytest.raises(RuntimeError, match="resume dispatch failed"):
        await retrying_reconciliation.reconcile_pending_resumes()
    assert suspension_store.suspension.resume_handoff_status == ResumeHandoffStatus.PENDING
    assert suspension_store.mark_dispatched_calls == []

    successful_resume_service = RecordingResumeService()
    successful_reconciliation = TaskResumeReconciliationService(
        receipt_store=JsonActionReceiptStore(dict(persisted_receipts.records)),
        suspension_store=suspension_store,
        resume_service=successful_resume_service,
    )
    processed = await successful_reconciliation.reconcile_pending_resumes()

    assert processed == 1
    assert suspension_store.suspension.resume_handoff_status == ResumeHandoffStatus.DISPATCHED
    assert suspension_store.mark_dispatched_calls == ["suspend-1"]
    assert len(successful_resume_service.calls) == 1
    resumed = successful_resume_service.calls[0]["command"]
    assert resumed.action_type == "submit_form"
    assert resumed.payload == {"answer": "yes", "nested": {"selection": ["a"]}}
    assert resumed.metadata == {"modelSnapshot": {"answer": "yes", "nested": {"selection": ["a"]}}}
    assert resumed.auth_context.subject == "user-1"
    assert resumed.auth_context.scopes == set()
    assert resumed.auth_context.app_id is None
    assert await successful_reconciliation.reconcile_pending_resumes() == 0
    assert len(successful_resume_service.calls) == 1


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
