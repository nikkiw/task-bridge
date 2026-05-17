# /// script
# dependencies = [
#   "fastapi",
#   "uvicorn",
#   "pydantic",
#   "taskbridge-fastapi",
# ]
# [tool.uv.sources]
# taskbridge-fastapi = { path = "../../backend/taskbridge-fastapi" }
# ///

"""
Minimal Standalone TaskBridge Example: The Greeter Service.

This example demonstrates the core TaskBridge lifecycle using an in-memory
event store and a fake executor. No Redis, Temporal, or Docker required.

Run it:
    uv run --no-project app.py
"""

import asyncio
import logging
from datetime import datetime, timezone
from uuid import uuid4

import uvicorn
from fastapi import APIRouter, Depends, FastAPI
from pydantic import Field
from taskbridge.event_store import EventStore
from taskbridge.fakes import DeterministicFakeExecutor
from taskbridge.handlers import handle_http_polling, handle_websocket_subscription
from taskbridge.models import (
    AuthContext,
    HttpTaskCreateRequest,
    PollEventsResult,
    TaskCreateCommand,
    TaskCreatedResult,
    TaskEvent,
    TaskEventType,
    TaskRecord,
    TaskStatus,
)
from taskbridge.observability import NoOpMetricsSink
from taskbridge.security import AuthContextResolver, OwnershipPolicy
from taskbridge.services import (
    TaskCreationService,
    TaskPollingService,
    WebSocketSubscriptionService,
)
from taskbridge.task_registry import TaskRegistry

# --- 1. Reactive In-Memory Adapters ---


class InMemoryEventStore(EventStore):
    """Event store that stays in RAM and uses an asyncio.Condition for reactivity."""

    def __init__(self):
        self.events: list[TaskEvent] = []
        self._condition = asyncio.Condition()

    async def append_event(self, event: TaskEvent) -> TaskEvent:
        async with self._condition:
            self.events.append(event)
            self._condition.notify_all()
        return event

    async def read_events_after(
        self, task_id: str, after_event_id: str | None, limit: int
    ) -> list[TaskEvent]:
        return [
            e
            for e in self.events
            if e.task_id == task_id
            and (after_event_id is None or e.event_id > after_event_id)
        ][:limit]

    async def wait_for_events(
        self, task_id: str, after_event_id: str | None, limit: int, timeout_ms: int
    ) -> list[TaskEvent]:
        async with self._condition:
            # Try immediate read
            batch = await self.read_events_after(task_id, after_event_id, limit)
            if batch:
                return batch

            # Wait for notification or timeout
            try:
                await asyncio.wait_for(
                    self._condition.wait(), timeout=timeout_ms / 1000.0
                )
            except asyncio.TimeoutError:
                return []

            return await self.read_events_after(task_id, after_event_id, limit)


class InMemoryTaskRegistry(TaskRegistry):
    def __init__(self):
        self.tasks: dict[str, TaskRecord] = {}

    async def create_task(self, task: TaskRecord) -> TaskRecord:
        self.tasks[task.task_id] = task
        return task

    async def get_task(self, task_id: str) -> TaskRecord:
        return self.tasks[task_id]

    async def get_task_by_client_request_id(
        self, owner_id: str, client_request_id: str
    ) -> TaskRecord | None:
        for t in self.tasks.values():
            if t.owner_id == owner_id and t.client_request_id == client_request_id:
                return t
        return None

    async def update_task_status(self, task_id: str, status: TaskStatus) -> TaskRecord:
        task = self.tasks[task_id]
        updated = task.model_copy(
            update={"status": status, "updated_at": datetime.now(timezone.utc)}
        )
        self.tasks[task_id] = updated
        return updated

    async def request_cancellation(self, task_id: str) -> TaskRecord:
        task = self.tasks[task_id]
        updated = task.model_copy(update={"cancellation_requested": True})
        self.tasks[task_id] = updated
        return updated

    async def same_idempotency_payload(
        self, task: TaskRecord, command: TaskCreateCommand
    ) -> bool:
        return (
            task.task_type == command.task_type
            and task.input_payload == command.input_payload
        )


# --- 2. Custom Greeter Executor ---


class GreeterExecutor(DeterministicFakeExecutor):
    """Custom executor that uses the 'name' from the task input."""

    async def _run(self, task_id: str) -> None:
        try:
            task = await self._registry.get_task(task_id)
            name = task.input_payload.get("name", "Stranger")

            await self._registry.update_task_status(task_id, TaskStatus.RUNNING)
            await self._emit(
                task_id,
                TaskEventType.TASK_STARTED,
                {"message": f"Starting to greet {name}..."},
            )

            for i in range(self._progress_ticks):
                if task_id in self._cancel_requested:
                    await self._emit_cancelled(task_id)
                    return
                await self._sleep(self._step_delay_seconds)
                progress = int((i + 1) / self._progress_ticks * 100)
                await self._emit(
                    task_id,
                    TaskEventType.TASK_PROGRESS,
                    {
                        "progress": progress,
                        "message": f"Preparing warm words for {name}...",
                    },
                )

            await self._registry.update_task_status(task_id, TaskStatus.COMPLETED)
            await self._emit(
                task_id,
                TaskEventType.TASK_COMPLETED,
                {"message": f"Hello, {name}! Have a great day with TaskBridge!"},
            )
        finally:
            self._tasks.pop(task_id, None)


# --- 3. Minimal Security Policy ---


class FreeForAllPolicy(OwnershipPolicy):
    """Allow anyone to do anything - only for this minimal demo!"""

    async def assert_task_create(self, auth_context: AuthContext) -> None:
        pass

    async def assert_task_access(
        self, auth_context: AuthContext, task: TaskRecord
    ) -> None:
        pass


class StaticAuthResolver(AuthContextResolver):
    async def resolve_auth_context(self, request_context: object) -> AuthContext:
        return AuthContext(subject="tester", scopes={"tasks:all"})


# --- 4. Wiring & FastAPI App ---

app = FastAPI(title="TaskBridge Minimal Greeter")
router = APIRouter()

# Global state for the demo
registry = InMemoryTaskRegistry()
event_store = InMemoryEventStore()
executor = GreeterExecutor(
    registry=registry, event_store=event_store, progress_ticks=3, step_delay_seconds=1.0
)
policy = FreeForAllPolicy()
metrics = NoOpMetricsSink()


@router.post("/api/v1/tasks", response_model=TaskCreatedResult)
async def create_task(
    request: HttpTaskCreateRequest,
    auth: AuthContext = Depends(lambda: AuthContext(subject="tester")),
    svc: TaskCreationService = Depends(
        lambda: TaskCreationService(registry, executor, policy, metrics)
    ),
):
    command = TaskCreateCommand(
        client_request_id=request.client_request_id,
        task_type=request.task_type,
        input_payload=request.input_payload,
        metadata=request.metadata,
        auth_context=auth,
    )
    return await svc.create_task(command)


@router.get("/api/v1/tasks/{task_id}/events", response_model=PollEventsResult)
async def poll_events(
    task_id: str,
    after_event_id: str | None = None,
    limit: int = 50,
    wait_timeout_ms: int = 20000,
    auth: AuthContext = Depends(lambda: AuthContext(subject="tester")),
    svc: TaskPollingService = Depends(
        lambda: TaskPollingService(registry, event_store, policy, metrics)
    ),
):
    return await handle_http_polling(
        task_id, svc, auth, metrics, after_event_id, limit, wait_timeout_ms
    )


app.include_router(router)

if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    print("\n\u261e TaskBridge Minimal Greeter started!")
    print("\u261e URL: http://127.0.0.1:8000")
    print("\u261e To connect Android Emulator: adb reverse tcp:8000 tcp:8000\n")
    uvicorn.run(app, host="0.0.0.0", port=8000)
