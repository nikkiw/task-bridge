from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Protocol

from taskbridge.models import TaskRecord


class WorkflowInputMapper(Protocol):
    def to_workflow_input(self, task: TaskRecord) -> Any: ...


class DefaultWorkflowInputMapper:
    """Default mapper keeps payload generic and host-agnostic."""

    def to_workflow_input(self, task: TaskRecord) -> dict[str, Any]:
        return {
            "task_id": task.task_id,
            "task_type": task.task_type,
            "input_payload": task.input_payload,
            "metadata": task.metadata,
            "owner_id": task.owner_id,
        }


@dataclass(slots=True)
class TemporalExecutorConfig:
    task_queue: str
    workflow: str
    workflow_id_prefix: str = "taskbridge"
    namespace: str | None = None
    cancellation_mode: str = "cancel"
    start_timeout_seconds: int | None = None
    workflow_input_mapper: WorkflowInputMapper = field(default_factory=DefaultWorkflowInputMapper)

    def workflow_id_for(self, task: TaskRecord) -> str:
        return f"{self.workflow_id_prefix}:{task.task_id}"
