"""In-memory demo adapters (same semantics as backend test fakes, kept local to the example)."""

from __future__ import annotations

from taskbridge.event_store import EventStore
from taskbridge.models import (
    AuthContext,
    TaskCreateCommand,
    TaskEvent,
    TaskRecord,
    TaskStatus,
    same_idempotency_payload,
)
from taskbridge.security import AuthContextResolver, OwnershipPolicy
from taskbridge.task_registry import TaskRegistry


def _is_newer_event(candidate: str, checkpoint: str | None) -> bool:
    if checkpoint is None:
        return True
    left = tuple(int(part) for part in candidate.split("-", maxsplit=1))
    right = tuple(int(part) for part in checkpoint.split("-", maxsplit=1))
    return left > right


class DemoEventStore(EventStore):
    def __init__(self) -> None:
        self.events: list[TaskEvent] = []

    async def append_event(self, event: TaskEvent) -> TaskEvent:
        self.events.append(event)
        return event

    async def read_events_after(
        self, task_id: str, after_event_id: str | None, limit: int
    ) -> list[TaskEvent]:
        return [
            event
            for event in self.events
            if event.task_id == task_id and _is_newer_event(event.event_id, after_event_id)
        ][:limit]

    async def wait_for_events(
        self, task_id: str, after_event_id: str | None, limit: int, timeout_ms: int
    ) -> list[TaskEvent]:
        del timeout_ms
        return await self.read_events_after(task_id, after_event_id, limit)


class DemoTaskRegistry(TaskRegistry):
    def __init__(self) -> None:
        self.tasks: dict[str, TaskRecord] = {}

    async def create_task(self, task: TaskRecord) -> TaskRecord:
        self.tasks[task.task_id] = task
        return task

    async def get_task(self, task_id: str) -> TaskRecord:
        return self.tasks[task_id]

    async def get_task_by_client_request_id(
        self, owner_id: str, client_request_id: str
    ) -> TaskRecord | None:
        for task in self.tasks.values():
            if task.owner_id == owner_id and task.client_request_id == client_request_id:
                return task
        return None

    async def update_task_status(self, task_id: str, status: TaskStatus) -> TaskRecord:
        task = self.tasks[task_id]
        updated = task.model_copy(update={"status": status})
        self.tasks[task_id] = updated
        return updated

    async def request_cancellation(self, task_id: str) -> TaskRecord:
        task = self.tasks[task_id]
        updated = task.model_copy(update={"cancellation_requested": True})
        self.tasks[task_id] = updated
        return updated

    async def same_idempotency_payload(self, task: TaskRecord, command: TaskCreateCommand) -> bool:
        return same_idempotency_payload(task, command)


class DemoAuthResolver(AuthContextResolver):
    """Demo identity; replace with JWT/API-key wiring in production."""

    async def resolve_auth_context(self, request_context: object) -> AuthContext:
        del request_context
        return AuthContext(subject="demo-user", scopes={"tasks:read", "tasks:write"})


class DemoOwnershipPolicy(OwnershipPolicy):
    async def assert_task_create(self, auth_context: AuthContext) -> None:
        if "tasks:write" not in auth_context.scopes:
            raise PermissionError(auth_context.subject)

    async def assert_task_access(self, auth_context: AuthContext, task: TaskRecord) -> None:
        if auth_context.subject != task.owner_id:
            raise PermissionError(task.task_id)
