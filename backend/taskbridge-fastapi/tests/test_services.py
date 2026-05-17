from __future__ import annotations

from taskbridge.errors import (
    IdempotencyConflictError,
    TaskNotFoundError,
    TaskOwnershipError,
    TaskSubmissionError,
)
from taskbridge.models import (
    AuthContext,
    CancelTaskCommand,
    CancelTaskStatus,
    TaskCreateCommand,
    TaskEvent,
    TaskEventType,
    TaskStatus,
)
from taskbridge.services import (
    TaskCancellationService,
    TaskCreationService,
    TaskPollingService,
    WebSocketSubscriptionService,
)

from .test_interfaces import (
    FakeEventStore,
    FakeOwnershipPolicy,
    FakeTaskExecutor,
    FakeTaskRegistry,
    RecordingMetricsSink,
)


def build_auth_context(subject: str = "user-1") -> AuthContext:
    return AuthContext(subject=subject, scopes={"tasks:read", "tasks:write"})


def build_command(
    *,
    client_request_id: str = "req-1",
    auth_context: AuthContext | None = None,
    input_payload: dict | None = None,
) -> TaskCreateCommand:
    return TaskCreateCommand(
        client_request_id=client_request_id,
        task_type="vision.classify",
        input_payload=input_payload or {"image_id": "img-1"},
        metadata={"source": "android"},
        auth_context=auth_context or build_auth_context(),
    )


async def test_task_creation_service_creates_and_submits_task() -> None:
    registry = FakeTaskRegistry()
    executor = FakeTaskExecutor()
    service = TaskCreationService(
        registry=registry,
        executor=executor,
        ownership_policy=FakeOwnershipPolicy(),
    )

    result = await service.create_task(build_command())

    assert result.task_id in registry.tasks
    assert result.status is TaskStatus.ACCEPTED
    assert result.deduplicated is False
    assert len(executor.submitted) == 1


async def test_task_creation_service_returns_existing_task_for_same_payload() -> None:
    registry = FakeTaskRegistry()
    executor = FakeTaskExecutor()
    service = TaskCreationService(
        registry=registry,
        executor=executor,
        ownership_policy=FakeOwnershipPolicy(),
    )
    command = build_command()

    first = await service.create_task(command)
    second = await service.create_task(command)

    assert second.task_id == first.task_id
    assert second.deduplicated is True
    assert len(executor.submitted) == 1


async def test_task_creation_service_rejects_conflicting_idempotency_reuse() -> None:
    registry = FakeTaskRegistry()
    executor = FakeTaskExecutor()
    service = TaskCreationService(
        registry=registry,
        executor=executor,
        ownership_policy=FakeOwnershipPolicy(),
    )
    command = build_command()

    await service.create_task(command)

    conflicting = build_command(input_payload={"image_id": "img-2"})

    try:
        await service.create_task(conflicting)
    except IdempotencyConflictError:
        pass
    else:
        raise AssertionError("Expected IdempotencyConflictError")


class FailingTaskExecutor(FakeTaskExecutor):
    async def submit_task(self, task) -> None:
        raise RuntimeError("executor unavailable")


async def test_task_creation_service_marks_task_failed_when_submit_fails() -> None:
    registry = FakeTaskRegistry()
    executor = FailingTaskExecutor()
    service = TaskCreationService(
        registry=registry,
        executor=executor,
        ownership_policy=FakeOwnershipPolicy(),
    )

    try:
        await service.create_task(build_command())
    except TaskSubmissionError:
        pass
    else:
        raise AssertionError("Expected TaskSubmissionError")

    assert len(registry.tasks) == 1
    stored_task = next(iter(registry.tasks.values()))
    assert stored_task.status is TaskStatus.FAILED


async def test_task_polling_service_checks_ownership() -> None:
    registry = FakeTaskRegistry()
    executor = FakeTaskExecutor()
    create_service = TaskCreationService(
        registry=registry,
        executor=executor,
        ownership_policy=FakeOwnershipPolicy(),
    )
    create_result = await create_service.create_task(build_command())
    task = await registry.get_task(create_result.task_id)

    store = FakeEventStore()
    await store.append_event(
        TaskEvent(
            type=TaskEventType.TASK_STARTED,
            task_id=task.task_id,
            event_id="1",
            created_at="2026-05-05T12:00:00Z",
            payload={"message": "started"},
        )
    )
    service = TaskPollingService(
        registry=registry,
        event_store=store,
        ownership_policy=FakeOwnershipPolicy(),
    )

    try:
        await service.poll_events(
            task_id=task.task_id,
            auth_context=build_auth_context("user-2"),
            after_event_id=None,
            limit=100,
        )
    except TaskNotFoundError:
        pass
    else:
        raise AssertionError("Expected TaskNotFoundError")


async def test_task_cancellation_service_returns_terminal_status_without_executor_call() -> None:
    registry = FakeTaskRegistry()
    executor = FakeTaskExecutor()
    create_service = TaskCreationService(
        registry=registry,
        executor=executor,
        ownership_policy=FakeOwnershipPolicy(),
    )
    created = await create_service.create_task(build_command())
    await registry.update_task_status(created.task_id, TaskStatus.COMPLETED)

    service = TaskCancellationService(
        registry=registry,
        executor=executor,
        ownership_policy=FakeOwnershipPolicy(),
    )

    result = await service.cancel_task(
        CancelTaskCommand(task_id=created.task_id, auth_context=build_auth_context())
    )

    assert result.status is CancelTaskStatus.ALREADY_TERMINAL
    assert executor.cancelled == []


async def test_websocket_subscription_service_returns_replay_batch() -> None:
    registry = FakeTaskRegistry()
    executor = FakeTaskExecutor()
    create_service = TaskCreationService(
        registry=registry,
        executor=executor,
        ownership_policy=FakeOwnershipPolicy(),
    )
    created = await create_service.create_task(build_command())

    store = FakeEventStore()
    await store.append_event(
        TaskEvent(
            type=TaskEventType.TASK_PROGRESS,
            task_id=created.task_id,
            event_id="2",
            created_at="2026-05-05T12:00:01Z",
            payload={"progress": 10, "message": "progress"},
        )
    )
    service = WebSocketSubscriptionService(
        registry=registry,
        event_store=store,
        ownership_policy=FakeOwnershipPolicy(),
    )

    result = await service.prepare_subscription(
        task_id=created.task_id,
        auth_context=build_auth_context(),
        last_event_id=None,
        limit=100,
    )

    assert result.task_id == created.task_id
    assert len(result.events) == 1


async def test_websocket_subscription_service_waits_for_live_events() -> None:
    registry = FakeTaskRegistry()
    executor = FakeTaskExecutor()
    create_service = TaskCreationService(
        registry=registry,
        executor=executor,
        ownership_policy=FakeOwnershipPolicy(),
    )
    created = await create_service.create_task(build_command())

    store = FakeEventStore()
    await store.append_event(
        TaskEvent(
            type=TaskEventType.TASK_PROGRESS,
            task_id=created.task_id,
            event_id="2-0",
            created_at="2026-05-05T12:00:01Z",
            payload={"progress": 10, "message": "progress"},
        )
    )
    service = WebSocketSubscriptionService(
        registry=registry,
        event_store=store,
        ownership_policy=FakeOwnershipPolicy(),
    )

    result = await service.wait_for_live_events(
        task_id=created.task_id,
        after_event_id="1-0",
        limit=100,
        timeout_ms=500,
    )

    assert len(result) == 1
    assert result[0].event_id == "2-0"


async def test_task_creation_service_denies_create_without_policy_access() -> None:
    registry = FakeTaskRegistry()
    executor = FakeTaskExecutor()
    service = TaskCreationService(
        registry=registry,
        executor=executor,
        ownership_policy=FakeOwnershipPolicy(),
    )

    try:
        await service.create_task(
            build_command(auth_context=AuthContext(subject="user-1", scopes=set()))
        )
    except TaskOwnershipError:
        pass
    else:
        raise AssertionError("Expected TaskOwnershipError")


async def test_task_creation_service_emits_start_metric() -> None:
    registry = FakeTaskRegistry()
    executor = FakeTaskExecutor()
    metrics = RecordingMetricsSink()
    service = TaskCreationService(
        registry=registry,
        executor=executor,
        ownership_policy=FakeOwnershipPolicy(),
        metrics_sink=metrics,
    )

    await service.create_task(build_command())

    assert ("task_starts_total", 1, None) in metrics.counters


async def test_task_creation_service_emits_duplicate_metric() -> None:
    registry = FakeTaskRegistry()
    executor = FakeTaskExecutor()
    metrics = RecordingMetricsSink()
    service = TaskCreationService(
        registry=registry,
        executor=executor,
        ownership_policy=FakeOwnershipPolicy(),
        metrics_sink=metrics,
    )
    command = build_command()

    await service.create_task(command)
    await service.create_task(command)

    assert ("duplicates_total", 1, None) in metrics.counters


async def test_task_cancellation_service_emits_cancel_metric() -> None:
    registry = FakeTaskRegistry()
    executor = FakeTaskExecutor()
    metrics = RecordingMetricsSink()
    create_service = TaskCreationService(
        registry=registry,
        executor=executor,
        ownership_policy=FakeOwnershipPolicy(),
    )
    created = await create_service.create_task(build_command())
    service = TaskCancellationService(
        registry=registry,
        executor=executor,
        ownership_policy=FakeOwnershipPolicy(),
        metrics_sink=metrics,
    )

    await service.cancel_task(
        CancelTaskCommand(task_id=created.task_id, auth_context=build_auth_context())
    )

    assert ("cancels_total", 1, None) in metrics.counters
