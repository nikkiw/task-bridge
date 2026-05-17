from __future__ import annotations

import asyncio

import pytest

from taskbridge.fakes import DeterministicFakeExecutor
from taskbridge.models import CancelTaskCommand, TaskEventType, TaskRecord, TaskStatus
from taskbridge.services import TaskCancellationService, TaskCreationService

from .test_interfaces import FakeEventStore, FakeOwnershipPolicy, FakeTaskRegistry
from .test_services import build_auth_context, build_command


@pytest.fixture
def registry_store_executor():
    registry = FakeTaskRegistry()
    store = FakeEventStore()
    executor = DeterministicFakeExecutor(
        registry=registry,
        event_store=store,
        progress_ticks=2,
        step_delay_seconds=0,
    )
    return registry, store, executor


async def test_deterministic_executor_happy_path(registry_store_executor) -> None:
    registry, store, executor = registry_store_executor
    service = TaskCreationService(
        registry=registry,
        executor=executor,
        ownership_policy=FakeOwnershipPolicy(),
    )
    result = await service.create_task(build_command())
    await asyncio.sleep(0.05)

    task = await registry.get_task(result.task_id)
    assert task.status is TaskStatus.COMPLETED

    types = [e.type for e in store.events]
    assert types == [
        TaskEventType.TASK_STARTED,
        TaskEventType.TASK_PROGRESS,
        TaskEventType.TASK_PROGRESS,
        TaskEventType.TASK_COMPLETED,
    ]


async def test_deterministic_executor_injected_failure() -> None:
    registry = FakeTaskRegistry()
    store = FakeEventStore()
    executor = DeterministicFakeExecutor(
        registry=registry,
        event_store=store,
        progress_ticks=3,
        step_delay_seconds=0,
        fail_after_progress_emissions=1,
    )
    service = TaskCreationService(
        registry=registry,
        executor=executor,
        ownership_policy=FakeOwnershipPolicy(),
    )
    result = await service.create_task(build_command(client_request_id="fail-req"))
    await asyncio.sleep(0.05)

    task = await registry.get_task(result.task_id)
    assert task.status is TaskStatus.FAILED
    assert store.events[-1].type is TaskEventType.TASK_FAILED


async def test_deterministic_executor_cancel_mid_run() -> None:
    registry = FakeTaskRegistry()
    store = FakeEventStore()
    executor = DeterministicFakeExecutor(
        registry=registry,
        event_store=store,
        progress_ticks=100,
        step_delay_seconds=0.05,
    )
    create_service = TaskCreationService(
        registry=registry,
        executor=executor,
        ownership_policy=FakeOwnershipPolicy(),
    )
    cancel_service = TaskCancellationService(
        registry=registry,
        executor=executor,
        ownership_policy=FakeOwnershipPolicy(),
    )

    result = await create_service.create_task(build_command(client_request_id="cancel-req"))
    await asyncio.sleep(0)
    await cancel_service.cancel_task(
        CancelTaskCommand(
            task_id=result.task_id,
            auth_context=build_auth_context(),
        )
    )
    await asyncio.sleep(0.2)

    task = await registry.get_task(result.task_id)
    assert task.status is TaskStatus.CANCELLED
    assert any(e.type is TaskEventType.TASK_CANCELLED for e in store.events)


async def test_deterministic_executor_rejects_double_submit() -> None:
    registry = FakeTaskRegistry()
    store = FakeEventStore()
    executor = DeterministicFakeExecutor(
        registry=registry,
        event_store=store,
        progress_ticks=0,
        step_delay_seconds=0,
    )
    cmd = build_command(client_request_id="double-submit")
    task = TaskRecord.from_command(task_id="tid-1", command=cmd)
    await registry.create_task(task)
    await executor.submit_task(task)
    with pytest.raises(RuntimeError, match="already submitted"):
        await executor.submit_task(task)
