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
from taskbridge.observability import MetricsSink
from taskbridge.security import (
    AuthContextResolver,
    DenyAllAccessPolicy,
    OwnershipPolicy,
)
from taskbridge.task_registry import TaskRegistry
from taskbridge.worker import TaskExecutor


class FakeEventStore(EventStore):
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
        _ = timeout_ms
        return await self.read_events_after(task_id, after_event_id, limit)


class FakeTaskRegistry(TaskRegistry):
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


class FakeTaskExecutor(TaskExecutor):
    def __init__(self) -> None:
        self.submitted: list[TaskRecord] = []
        self.cancelled: list[str] = []

    async def submit_task(self, task: TaskRecord) -> None:
        self.submitted.append(task)

    async def request_cancellation(self, task: TaskRecord) -> None:
        self.cancelled.append(task.task_id)


class FakeAuthContextResolver(AuthContextResolver):
    async def resolve_auth_context(self, request_context: object) -> AuthContext:
        return AuthContext(subject="user-1", scopes={"tasks:read"})


class FakeOwnershipPolicy(OwnershipPolicy):
    async def assert_task_create(self, auth_context: AuthContext) -> None:
        if "tasks:write" not in auth_context.scopes:
            raise PermissionError(auth_context.subject)

    async def assert_task_access(self, auth_context: AuthContext, task: TaskRecord) -> None:
        if auth_context.subject != task.owner_id:
            raise PermissionError(task.task_id)


class RecordingMetricsSink(MetricsSink):
    def __init__(self) -> None:
        self.counters: list[tuple[str, int, dict[str, str] | None]] = []
        self.gauges: list[tuple[str, float, dict[str, str] | None]] = []

    def increment_counter(
        self,
        metric_name: str,
        value: int = 1,
        *,
        tags: dict[str, str] | None = None,
    ) -> None:
        self.counters.append((metric_name, value, tags))

    def set_gauge(
        self,
        metric_name: str,
        value: float,
        *,
        tags: dict[str, str] | None = None,
    ) -> None:
        self.gauges.append((metric_name, value, tags))


def test_fake_interfaces_satisfy_contracts() -> None:
    store = FakeEventStore()
    registry = FakeTaskRegistry()
    executor = FakeTaskExecutor()
    auth = FakeAuthContextResolver()
    ownership = FakeOwnershipPolicy()

    assert isinstance(store, EventStore)
    assert isinstance(registry, TaskRegistry)
    assert isinstance(executor, TaskExecutor)
    assert isinstance(auth, AuthContextResolver)
    assert isinstance(ownership, OwnershipPolicy)


async def test_deny_all_access_policy_fails_closed() -> None:
    policy = DenyAllAccessPolicy()
    task = TaskRecord.model_validate(
        {
            "task_id": "task-1",
            "client_request_id": "req-1",
            "task_type": "vision.classify",
            "input_payload": {"image_id": "img-1"},
            "owner_id": "user-1",
            "created_at": "2026-05-05T12:00:00Z",
            "updated_at": "2026-05-05T12:00:00Z",
        }
    )

    try:
        await policy.assert_task_create(AuthContext(subject="user-1", scopes={"tasks:write"}))
    except PermissionError:
        pass
    else:
        raise AssertionError("Expected PermissionError for create access")

    try:
        await policy.assert_task_access(AuthContext(subject="user-1", scopes={"tasks:read"}), task)
    except PermissionError:
        pass
    else:
        raise AssertionError("Expected PermissionError for task access")


def _is_newer_event(candidate: str, checkpoint: str | None) -> bool:
    if checkpoint is None:
        return True
    left = tuple(int(part) for part in candidate.split("-", maxsplit=1))
    right = tuple(int(part) for part in checkpoint.split("-", maxsplit=1))
    return left > right
