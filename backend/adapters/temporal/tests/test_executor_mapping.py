from __future__ import annotations

from datetime import UTC, datetime

import pytest
from taskbridge.models import AuthContext, TaskCreateCommand, TaskEventType, TaskRecord

from taskbridge_temporal import (
    TemporalExecutorConfig,
    TemporalTaskExecutor,
    TemporalWorkflowUpdate,
    map_temporal_update_to_task_event,
)


class FakeWorkflowHandle:
    def __init__(self) -> None:
        self.cancel_calls = 0
        self.signal_calls: list[str] = []

    async def cancel(self) -> None:
        self.cancel_calls += 1

    async def signal(self, name: str) -> None:
        self.signal_calls.append(name)


class FakeTemporalClient:
    def __init__(self) -> None:
        self.start_calls: list[dict] = []
        self._handle = FakeWorkflowHandle()

    async def start_workflow(self, workflow, workflow_input, **kwargs) -> None:
        self.start_calls.append(
            {"workflow": workflow, "workflow_input": workflow_input, "kwargs": kwargs}
        )

    def get_workflow_handle(self, workflow_id: str) -> FakeWorkflowHandle:
        self.last_workflow_id = workflow_id
        return self._handle


def _task_record(task_id: str = "task-1") -> TaskRecord:
    command = TaskCreateCommand(
        client_request_id="req-1",
        task_type="demo.echo",
        input_payload={"hello": "world"},
        metadata={"source": "test"},
        auth_context=AuthContext(subject="user-1", scopes={"tasks:write"}),
    )
    return TaskRecord.from_command(task_id=task_id, command=command)


@pytest.mark.asyncio
async def test_submit_maps_to_start_workflow_call() -> None:
    client = FakeTemporalClient()
    config = TemporalExecutorConfig(task_queue="tb-q", workflow="DemoWorkflow")
    executor = TemporalTaskExecutor(temporal_client=client, config=config)

    await executor.submit_task(_task_record("task-42"))

    assert len(client.start_calls) == 1
    call = client.start_calls[0]
    assert call["workflow"] == "DemoWorkflow"
    assert call["kwargs"]["id"] == "taskbridge:task-42"
    assert call["kwargs"]["task_queue"] == "tb-q"
    assert call["workflow_input"]["task_id"] == "task-42"


@pytest.mark.asyncio
async def test_cancel_maps_to_workflow_cancel_by_default() -> None:
    client = FakeTemporalClient()
    config = TemporalExecutorConfig(task_queue="tb-q", workflow="DemoWorkflow")
    executor = TemporalTaskExecutor(temporal_client=client, config=config)

    await executor.request_cancellation(_task_record("task-100"))

    assert client.last_workflow_id == "taskbridge:task-100"
    assert client._handle.cancel_calls == 1
    assert client._handle.signal_calls == []


@pytest.mark.asyncio
async def test_cancel_uses_signal_mode_when_configured() -> None:
    client = FakeTemporalClient()
    config = TemporalExecutorConfig(
        task_queue="tb-q", workflow="DemoWorkflow", cancellation_mode="signal"
    )
    executor = TemporalTaskExecutor(temporal_client=client, config=config)

    await executor.request_cancellation(_task_record("task-200"))

    assert client._handle.cancel_calls == 0
    assert client._handle.signal_calls == ["request_cancel"]


def test_temporal_update_mapping_for_terminal_and_progress_events() -> None:
    progress = map_temporal_update_to_task_event(
        task_id="task-1",
        event_id="1-0",
        update=TemporalWorkflowUpdate(kind="progress", payload={"pct": 10}),
        created_at=datetime(2026, 5, 5, 12, 0, 0, tzinfo=UTC),
    )
    completed = map_temporal_update_to_task_event(
        task_id="task-1",
        event_id="2-0",
        update=TemporalWorkflowUpdate(kind="completed", payload={"result": {"ok": True}}),
    )

    assert progress.type is TaskEventType.TASK_PROGRESS
    assert progress.payload == {"pct": 10}
    assert completed.type is TaskEventType.TASK_COMPLETED


def test_temporal_update_mapping_rejects_unknown_kind() -> None:
    with pytest.raises(ValueError, match="Unsupported Temporal update kind"):
        map_temporal_update_to_task_event(
            task_id="task-1",
            event_id="9-0",
            update=TemporalWorkflowUpdate(kind="custom"),
        )
