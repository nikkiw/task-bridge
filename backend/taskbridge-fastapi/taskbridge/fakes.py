"""Reference and test doubles for TaskBridge integration.

`DeterministicFakeExecutor` is a small asyncio-backed executor that emits a predictable
event sequence through ``EventStore``. It exists for tests, examples, and local demos —
not for production load.
"""

from __future__ import annotations

import asyncio
from collections import defaultdict
from collections.abc import Awaitable, Callable
from datetime import UTC, datetime

from .event_store import EventStore
from .models import TaskEvent, TaskEventType, TaskRecord, TaskStatus
from .task_registry import TaskRegistry
from .worker import TaskExecutor


class DeterministicFakeExecutor(TaskExecutor):
    """Asyncio executor that emits TASK_STARTED, TASK_PROGRESS, then a terminal event.

    Uses monotonic string ``eventId`` values per task (``"1-0"``, ``"2-0"``, …) suitable
    for replay ordering in tests. When backed by ``RedisStreamEventStore``, appended IDs
    are replaced by Redis stream IDs as usual.

    Cancellation: call ``request_cancellation`` to stop after the current progress step
    and emit ``TASK_CANCELLED`` (registry status ``CANCELLED``).

    Failure: set ``fail_after_progress_emissions`` to emit ``TASK_FAILED`` after that
    many ``TASK_PROGRESS`` events (registry ``FAILED``).
    """

    def __init__(
        self,
        *,
        registry: TaskRegistry,
        event_store: EventStore,
        progress_ticks: int = 2,
        step_delay_seconds: float = 0.001,
        fail_after_progress_emissions: int | None = None,
        sleep_fn: Callable[[float], Awaitable[None]] | None = None,
    ) -> None:
        """Initialize deterministic fake executor.

        Args:
            registry: Task registry for status updates.
            event_store: Event store for appending events.
            progress_ticks: Number of progress events to emit.
            step_delay_seconds: Delay between events.
            fail_after_progress_emissions: Emit failure after this many progress ticks.
            sleep_fn: Custom sleep function (asyncio.sleep by default).

        """
        if progress_ticks < 0:
            raise ValueError("progress_ticks must be >= 0")
        self._registry = registry
        self._store = event_store
        self._progress_ticks = progress_ticks
        self._step_delay_seconds = step_delay_seconds
        self._fail_after_progress_emissions = fail_after_progress_emissions
        self._sleep = sleep_fn if sleep_fn is not None else asyncio.sleep

        self._cancel_requested: set[str] = set()
        self._event_seq: dict[str, int] = defaultdict(int)
        self._tasks: dict[str, asyncio.Task[None]] = {}

    async def submit_task(self, task: TaskRecord) -> None:
        """Submit a task for deterministic execution.

        Args:
            task: The task to run.

        """
        if task.task_id in self._tasks:
            raise RuntimeError(f"Task {task.task_id} already submitted to this executor")
        self._tasks[task.task_id] = asyncio.create_task(self._run(task.task_id))

    async def request_cancellation(self, task: TaskRecord) -> None:
        """Request cancellation of a running task.

        Args:
            task: The task to cancel.

        """
        self._cancel_requested.add(task.task_id)

    async def _run(self, task_id: str) -> None:
        try:
            await self._registry.update_task_status(task_id, TaskStatus.RUNNING)
            await self._emit(task_id, TaskEventType.TASK_STARTED, {"message": "started"})

            progress_emitted = 0
            for _ in range(self._progress_ticks):
                if task_id in self._cancel_requested:
                    await self._emit_cancelled(task_id)
                    return
                await self._sleep(self._step_delay_seconds)
                await self._emit(
                    task_id,
                    TaskEventType.TASK_PROGRESS,
                    {"progress": progress_emitted + 1, "message": "tick"},
                )
                progress_emitted += 1
                if (
                    self._fail_after_progress_emissions is not None
                    and progress_emitted >= self._fail_after_progress_emissions
                ):
                    await self._emit_failed(task_id, {"message": "injected failure"})
                    return

            if task_id in self._cancel_requested:
                await self._emit_cancelled(task_id)
                return

            await self._registry.update_task_status(task_id, TaskStatus.COMPLETED)
            await self._emit(
                task_id,
                TaskEventType.TASK_COMPLETED,
                {"message": "completed"},
            )
        finally:
            self._tasks.pop(task_id, None)

    async def _emit_cancelled(self, task_id: str) -> None:
        await self._registry.update_task_status(task_id, TaskStatus.CANCELLED)
        await self._emit(task_id, TaskEventType.TASK_CANCELLED, {"message": "cancelled"})

    async def _emit_failed(self, task_id: str, payload: dict) -> None:
        await self._registry.update_task_status(task_id, TaskStatus.FAILED)
        await self._emit(task_id, TaskEventType.TASK_FAILED, payload)

    async def _emit(self, task_id: str, event_type: TaskEventType, payload: dict) -> TaskEvent:
        self._event_seq[task_id] += 1
        event_id = f"{self._event_seq[task_id]}-0"
        event = TaskEvent(
            type=event_type,
            taskId=task_id,
            eventId=event_id,
            createdAt=datetime.now(UTC),
            payload=payload,
        )
        return await self._store.append_event(event)
