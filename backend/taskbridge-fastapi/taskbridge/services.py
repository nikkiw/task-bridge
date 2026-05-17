"""Business logic services for task management and event streaming."""

from __future__ import annotations

import logging
from datetime import datetime, timezone
from typing import Any
from uuid import uuid4

from .action_receipt_store import ActionReceiptStore
from .errors import (
    IdempotencyConflictError,
    TaskNotFoundError,
    TaskOwnershipError,
    TaskSubmissionError,
)
from .event_store import EventStore
from .models import (
    AuthContext,
    CancelTaskCommand,
    CancelTaskResult,
    CancelTaskStatus,
    PollEventsResult,
    SubmitActionCommand,
    SubmitActionResult,
    SubmitActionResultStatus,
    TaskActionReceipt,
    TaskActionReceiptStatus,
    TaskCreateCommand,
    TaskCreatedResult,
    TaskEvent,
    TaskEventType,
    TaskRecord,
    TaskStatus,
    TaskSuspensionStatus,
)
from .observability import MetricsSink, NoOpMetricsSink, log_structured
from .security import OwnershipPolicy
from .suspension_store import SuspensionStore
from .task_registry import TaskRegistry
from .worker import TaskExecutor

logger = logging.getLogger(__name__)


class TaskResumeService:
    """Service for resuming tasks after client interaction."""

    async def resume_task(
        self,
        command: SubmitActionCommand,
        suspension: Any,
    ) -> None:
        """Host hook for resuming a suspended task after a durable action accept.

        Args:
            command: The validated action submission command.
            suspension: The suspension record that was resolved.

        """
        del command, suspension


class TaskResumeReconciliationService:
    """Service to retry pending task resumes that failed to dispatch."""

    def __init__(
        self,
        receipt_store: ActionReceiptStore,
        suspension_store: SuspensionStore,
        resume_service: TaskResumeService | None = None,
    ) -> None:
        """Initialize the reconciliation service."""
        self._receipt_store = receipt_store
        self._suspension_store = suspension_store
        self._resume_service = resume_service or TaskResumeService()

    async def reconcile_pending_resumes(self) -> int:
        """Find and retry all task suspensions in PENDING handoff state.

        Returns:
            Number of successfully reconciled resumes.

        """
        processed = 0
        for suspension in await self._suspension_store.list_pending_resume_handoffs():
            if suspension.successful_action_id is None:
                continue
            receipt = await self._receipt_store.get_receipt(
                suspension.task_id,
                suspension.successful_action_id,
            )
            if receipt is None:
                continue
            command = SubmitActionCommand(
                task_id=receipt.task_id,
                client_action_id=receipt.client_action_id,
                suspend_id=receipt.suspend_id,
                action_type=receipt.action_type,
                payload=receipt.payload,
                metadata={},
                auth_context=AuthContext(subject=receipt.actor_id),
            )
            await self._resume_service.resume_task(command, suspension)
            await self._suspension_store.mark_resume_dispatched(
                suspension.task_id,
                suspension.suspend_id,
            )
            log_structured(
                logger,
                level=logging.INFO,
                message="task.resume.reconciled",
                task_id=suspension.task_id,
                client_request_id=receipt.client_action_id,
                user_id=receipt.actor_id,
                transport="internal",
                outcome="ok",
                details={"suspendId": suspension.suspend_id},
            )
            processed += 1
        return processed


class TaskRetentionService:
    """Service for pruning expired task state and receipts."""

    def __init__(
        self,
        receipt_store: ActionReceiptStore,
        suspension_store: SuspensionStore,
    ) -> None:
        """Initialize the retention service."""
        self._receipt_store = receipt_store
        self._suspension_store = suspension_store

    async def prune_expired_state(self, *, now: datetime | None = None) -> dict[str, int]:
        """Delete expired action receipts and expire open suspensions.

        Args:
            now: Optional override for current time.

        Returns:
            Dictionary with counts of pruned records.

        """
        expires_before = now or datetime.now(timezone.utc)
        expired_receipts = await self._receipt_store.delete_expired_receipts(expires_before)
        expired_suspensions = await self._suspension_store.expire_open_suspensions(expires_before)
        counts = {
            "expired_receipts": expired_receipts,
            "expired_suspensions": expired_suspensions,
        }
        log_structured(
            logger,
            level=logging.INFO,
            message="task.retention.pruned",
            transport="internal",
            outcome="ok",
            details=counts,
        )
        return counts


class TaskActionService:
    """Service for handling client interactions with suspended tasks."""

    def __init__(
        self,
        registry: TaskRegistry,
        suspension_store: SuspensionStore,
        receipt_store: ActionReceiptStore,
        event_store: EventStore,
        ownership_policy: OwnershipPolicy,
        resume_service: TaskResumeService | None = None,
        metrics_sink: MetricsSink | None = None,
    ) -> None:
        """Initialize the action service."""
        self._registry = registry
        self._suspension_store = suspension_store
        self._receipt_store = receipt_store
        self._event_store = event_store
        self._ownership_policy = ownership_policy
        self._resume_service = resume_service or TaskResumeService()
        self._metrics_sink = metrics_sink or NoOpMetricsSink()

    async def submit_action(self, command: SubmitActionCommand) -> SubmitActionResult:
        """Validate and apply a client action to a suspended task.

        Args:
            command: The action submission request.

        Returns:
            Outcome of the submission.

        Raises:
            TaskNotFoundError: If task or suspension is missing.
            IdempotencyConflictError: If request conflicts with existing receipt.

        """
        await _get_owned_task_or_raise_not_found(
            registry=self._registry,
            ownership_policy=self._ownership_policy,
            task_id=command.task_id,
            auth_context=command.auth_context,
        )
        suspension = await self._get_suspension_or_not_found(
            task_id=command.task_id,
            suspend_id=command.suspend_id,
        )
        if command.action_type not in suspension.allowed_actions:
            raise IdempotencyConflictError(f"Action type not allowed: {command.action_type}")

        existing = await self._receipt_store.get_receipt(
            command.task_id,
            command.client_action_id,
        )
        if existing is not None:
            if self._same_action_receipt(existing, command):
                return SubmitActionResult(
                    taskId=command.task_id,
                    suspendId=command.suspend_id,
                    clientActionId=command.client_action_id,
                    status=SubmitActionResultStatus.DEDUPLICATED,
                )
            raise IdempotencyConflictError(
                f"Conflicting idempotent task action for {command.client_action_id}"
            )

        if suspension.status == TaskSuspensionStatus.EXPIRED:
            return self._result(command, SubmitActionResultStatus.REJECTED_EXPIRED)
        if suspension.status != TaskSuspensionStatus.OPEN:
            return self._result(command, SubmitActionResultStatus.REJECTED_ALREADY_RESOLVED)

        accepted = await self._suspension_store.accept_action_if_open(
            command.task_id,
            command.suspend_id,
            command.client_action_id,
        )
        if accepted is None:
            current = await self._get_suspension_or_not_found(command.task_id, command.suspend_id)
            if current.successful_action_id == command.client_action_id:
                return self._result(command, SubmitActionResultStatus.DEDUPLICATED)
            if current.status == TaskSuspensionStatus.EXPIRED:
                return self._result(command, SubmitActionResultStatus.REJECTED_EXPIRED)
            return self._result(command, SubmitActionResultStatus.REJECTED_ALREADY_RESOLVED)

        now = datetime.now(timezone.utc)
        await self._receipt_store.save_receipt(
            TaskActionReceipt(
                task_id=command.task_id,
                suspend_id=command.suspend_id,
                client_action_id=command.client_action_id,
                action_type=command.action_type,
                payload=command.payload,
                actor_id=command.auth_context.subject,
                status=TaskActionReceiptStatus.ACCEPTED,
                created_at=now,
                processed_at=now,
            )
        )
        event = await self._event_store.append_event(
            TaskEvent(
                type=TaskEventType.TASK_ACTION_ACCEPTED,
                taskId=command.task_id,
                eventId="0-0",
                createdAt=now,
                payload={
                    "suspendId": command.suspend_id,
                    "clientActionId": command.client_action_id,
                    "actionType": command.action_type,
                    "acceptedAt": now.isoformat(),
                },
            )
        )
        log_structured(
            logger,
            level=logging.INFO,
            message="task.event.appended",
            task_id=command.task_id,
            event_id=event.event_id,
            client_request_id=command.client_action_id,
            user_id=command.auth_context.subject,
            transport="internal",
            outcome="ok",
            details={"type": event.type.value},
        )
        try:
            await self._resume_service.resume_task(command, accepted)
        except Exception:
            self._metrics_sink.increment_counter("actions_resume_pending_total")
        else:
            await self._suspension_store.mark_resume_dispatched(
                command.task_id,
                command.suspend_id,
            )
        self._metrics_sink.increment_counter("actions_accepted_total")
        return self._result(command, SubmitActionResultStatus.ACCEPTED)

    async def _get_suspension_or_not_found(self, task_id: str, suspend_id: str):
        try:
            return await self._suspension_store.get_suspension(task_id, suspend_id)
        except KeyError as exc:
            raise TaskNotFoundError(suspend_id) from exc

    @staticmethod
    def _same_action_receipt(receipt: TaskActionReceipt, command: SubmitActionCommand) -> bool:
        return (
            receipt.suspend_id == command.suspend_id
            and receipt.action_type == command.action_type
            and receipt.payload == command.payload
        )

    @staticmethod
    def _result(
        command: SubmitActionCommand,
        status: SubmitActionResultStatus,
    ) -> SubmitActionResult:
        return SubmitActionResult(
            taskId=command.task_id,
            suspendId=command.suspend_id,
            clientActionId=command.client_action_id,
            status=status,
        )


class TaskCreationService:
    """Service for orchestrating new task creation and submission."""

    def __init__(
        self,
        registry: TaskRegistry,
        executor: TaskExecutor,
        ownership_policy: OwnershipPolicy,
        metrics_sink: MetricsSink | None = None,
    ) -> None:
        """Initialize the creation service."""
        self._registry = registry
        self._executor = executor
        self._ownership_policy = ownership_policy
        self._metrics_sink = metrics_sink or NoOpMetricsSink()

    async def create_task(self, command: TaskCreateCommand) -> TaskCreatedResult:
        """Validate, persist, and submit a new task.

        Args:
            command: The task creation request.

        Returns:
            Assigned task ID and initial status.

        Raises:
            TaskOwnershipError: If user is not allowed to create tasks.
            TaskSubmissionError: If submission to executor fails.

        """
        try:
            await self._ownership_policy.assert_task_create(command.auth_context)
        except PermissionError as exc:
            log_structured(
                logger,
                level=logging.WARNING,
                message="task.create.denied",
                client_request_id=command.client_request_id,
                user_id=command.auth_context.subject,
                transport="internal",
                outcome="denied",
            )
            raise TaskOwnershipError(str(exc)) from exc

        existing = await self._registry.get_task_by_client_request_id(
            owner_id=command.auth_context.subject,
            client_request_id=command.client_request_id,
        )
        if existing is not None:
            if await self._registry.same_idempotency_payload(existing, command):
                self._metrics_sink.increment_counter("duplicates_total")
                log_structured(
                    logger,
                    level=logging.INFO,
                    message="task.create.duplicate",
                    task_id=existing.task_id,
                    client_request_id=existing.client_request_id,
                    user_id=command.auth_context.subject,
                    transport="internal",
                    outcome="deduplicated",
                )
                return TaskCreatedResult(
                    task_id=existing.task_id,
                    status=existing.status,
                    client_request_id=existing.client_request_id,
                    deduplicated=True,
                )
            raise IdempotencyConflictError(
                f"Conflicting idempotent task create for {command.client_request_id}"
            )

        task = TaskRecord.from_command(task_id=uuid4().hex, command=command)
        created = await self._registry.create_task(task)
        try:
            await self._executor.submit_task(created)
        except Exception as exc:
            failed = await self._registry.update_task_status(created.task_id, TaskStatus.FAILED)
            self._metrics_sink.increment_counter("task_failures_total")
            log_structured(
                logger,
                level=logging.ERROR,
                message="task.create.submit_failed",
                task_id=failed.task_id,
                client_request_id=failed.client_request_id,
                user_id=command.auth_context.subject,
                transport="internal",
                outcome="failed",
            )
            raise TaskSubmissionError(f"Failed to submit task {failed.task_id}") from exc
        self._metrics_sink.increment_counter("task_starts_total")
        log_structured(
            logger,
            level=logging.INFO,
            message="task.create.accepted",
            task_id=created.task_id,
            client_request_id=created.client_request_id,
            user_id=command.auth_context.subject,
            transport="internal",
            outcome="accepted",
        )
        return TaskCreatedResult(
            task_id=created.task_id,
            status=created.status,
            client_request_id=created.client_request_id,
            deduplicated=False,
        )


class TaskPollingService:
    """Service for long-polling task events."""

    def __init__(
        self,
        registry: TaskRegistry,
        event_store: EventStore,
        ownership_policy: OwnershipPolicy,
        metrics_sink: MetricsSink | None = None,
    ) -> None:
        """Initialize the polling service."""
        self._registry = registry
        self._event_store = event_store
        self._ownership_policy = ownership_policy
        self._metrics_sink = metrics_sink or NoOpMetricsSink()

    async def poll_events(
        self,
        task_id: str,
        auth_context: AuthContext,
        after_event_id: str | None,
        limit: int,
        wait_timeout_ms: int | None = None,
    ) -> PollEventsResult:
        """Poll for new events for a specific task.

        Args:
            task_id: Unique task identifier.
            auth_context: Authenticated user context.
            after_event_id: Resume marker for events.
            limit: Maximum number of events to return.
            wait_timeout_ms: Optional blocking wait time for new events.

        Returns:
            Batch of events and the next resume marker.

        """
        await _get_owned_task_or_raise_not_found(
            registry=self._registry,
            ownership_policy=self._ownership_policy,
            task_id=task_id,
            auth_context=auth_context,
        )

        if wait_timeout_ms and wait_timeout_ms > 0:
            events = await self._event_store.wait_for_events(
                task_id,
                after_event_id,
                limit,
                wait_timeout_ms,
            )
        else:
            events = await self._event_store.read_events_after(task_id, after_event_id, limit)
        if after_event_id is not None:
            self._metrics_sink.increment_counter("replays_total")
        log_structured(
            logger,
            level=logging.INFO,
            message="task.events.polled",
            task_id=task_id,
            event_id=events[-1].event_id if events else None,
            user_id=auth_context.subject,
            transport="internal",
            outcome="ok",
            details={"batchSize": len(events), "afterEventId": after_event_id},
        )
        next_after = events[-1].event_id if events else after_event_id
        return PollEventsResult(
            taskId=task_id,
            events=events,
            nextAfterEventId=next_after,
            hasMore=len(events) == limit,
        )


class TaskCancellationService:
    """Service for requesting task cancellation."""

    def __init__(
        self,
        registry: TaskRegistry,
        executor: TaskExecutor,
        ownership_policy: OwnershipPolicy,
        metrics_sink: MetricsSink | None = None,
    ) -> None:
        """Initialize the cancellation service."""
        self._registry = registry
        self._executor = executor
        self._ownership_policy = ownership_policy
        self._metrics_sink = metrics_sink or NoOpMetricsSink()

    async def cancel_task(self, command: CancelTaskCommand) -> CancelTaskResult:
        """Request cancellation of an active task.

        Args:
            command: The cancellation request.

        Returns:
            Result of the cancellation request.

        """
        task = await _get_owned_task_or_raise_not_found(
            registry=self._registry,
            ownership_policy=self._ownership_policy,
            task_id=command.task_id,
            auth_context=command.auth_context,
        )

        if task.status.is_terminal:
            log_structured(
                logger,
                level=logging.INFO,
                message="task.cancel.skipped_terminal",
                task_id=task.task_id,
                user_id=command.auth_context.subject,
                transport="internal",
                outcome="already_terminal",
            )
            return CancelTaskResult(
                task_id=task.task_id,
                status=CancelTaskStatus.ALREADY_TERMINAL,
            )

        updated = await self._registry.request_cancellation(task.task_id)
        await self._executor.request_cancellation(updated)
        self._metrics_sink.increment_counter("cancels_total")
        log_structured(
            logger,
            level=logging.INFO,
            message="task.cancel.requested",
            task_id=updated.task_id,
            user_id=command.auth_context.subject,
            transport="internal",
            outcome="requested",
        )
        return CancelTaskResult(
            task_id=updated.task_id,
            status=CancelTaskStatus.CANCELLATION_REQUESTED,
        )


class WebSocketSubscriptionService:
    """Service for managing WebSocket-based event streaming."""

    def __init__(
        self,
        registry: TaskRegistry,
        event_store: EventStore,
        ownership_policy: OwnershipPolicy,
        metrics_sink: MetricsSink | None = None,
    ) -> None:
        """Initialize the WebSocket service."""
        self._registry = registry
        self._event_store = event_store
        self._ownership_policy = ownership_policy
        self._metrics_sink = metrics_sink or NoOpMetricsSink()

    async def prepare_subscription(
        self,
        task_id: str,
        auth_context: AuthContext,
        last_event_id: str | None,
        limit: int,
    ) -> PollEventsResult:
        """Validate ownership and read replay events for a new subscription.

        Args:
            task_id: Unique task identifier.
            auth_context: Authenticated user context.
            last_event_id: Client's last seen event ID for replay.
            limit: Maximum replay batch size.

        Returns:
            Initial set of replay events.

        """
        await _get_owned_task_or_raise_not_found(
            registry=self._registry,
            ownership_policy=self._ownership_policy,
            task_id=task_id,
            auth_context=auth_context,
        )

        events = await self._event_store.read_events_after(task_id, last_event_id, limit)
        if last_event_id is not None:
            self._metrics_sink.increment_counter("replays_total")
        log_structured(
            logger,
            level=logging.INFO,
            message="task.ws.subscription_prepared",
            task_id=task_id,
            event_id=events[-1].event_id if events else None,
            user_id=auth_context.subject,
            transport="ws",
            outcome="ok",
            details={"batchSize": len(events), "lastEventId": last_event_id},
        )
        next_after = events[-1].event_id if events else last_event_id
        return PollEventsResult(
            taskId=task_id,
            events=events,
            nextAfterEventId=next_after,
            hasMore=len(events) == limit,
        )

    async def wait_for_live_events(
        self,
        task_id: str,
        after_event_id: str | None,
        limit: int,
        timeout_ms: int,
    ) -> list[TaskEvent]:
        """Block until new live events are available for delivery.

        Args:
            task_id: Unique task identifier.
            after_event_id: Marker after which to read new events.
            limit: Maximum number of events to return.
            timeout_ms: Maximum blocking time.

        Returns:
            Batch of new live events.

        """
        return await self._event_store.wait_for_events(
            task_id=task_id,
            after_event_id=after_event_id,
            limit=limit,
            timeout_ms=timeout_ms,
        )


async def _get_task_or_raise_not_found(registry: TaskRegistry, task_id: str) -> TaskRecord:
    try:
        return await registry.get_task(task_id)
    except KeyError as exc:
        raise TaskNotFoundError(task_id) from exc


async def _get_owned_task_or_raise_not_found(
    registry: TaskRegistry,
    ownership_policy: OwnershipPolicy,
    task_id: str,
    auth_context: AuthContext,
) -> TaskRecord:
    task = await _get_task_or_raise_not_found(registry, task_id)
    try:
        await ownership_policy.assert_task_access(auth_context, task)
    except PermissionError as exc:
        raise TaskNotFoundError(task_id) from exc
    return task
