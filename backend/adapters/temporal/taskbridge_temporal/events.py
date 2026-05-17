from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

from taskbridge.models import TaskEvent, TaskEventType


@dataclass(slots=True)
class TemporalWorkflowUpdate:
    kind: str
    payload: dict[str, Any] = field(default_factory=dict)


def map_temporal_update_to_task_event(
    *,
    task_id: str,
    event_id: str,
    update: TemporalWorkflowUpdate,
    created_at: datetime | None = None,
) -> TaskEvent:
    event_type = _event_type_for_kind(update.kind)
    return TaskEvent(
        type=event_type,
        taskId=task_id,
        eventId=event_id,
        createdAt=created_at or datetime.now(UTC),
        payload=update.payload,
    )


def _event_type_for_kind(kind: str) -> TaskEventType:
    normalized = kind.lower()
    if normalized == "progress":
        return TaskEventType.TASK_PROGRESS
    if normalized == "message":
        return TaskEventType.TASK_MESSAGE
    if normalized == "completed":
        return TaskEventType.TASK_COMPLETED
    if normalized == "failed":
        return TaskEventType.TASK_FAILED
    if normalized == "cancelled":
        return TaskEventType.TASK_CANCELLED
    raise ValueError(f"Unsupported Temporal update kind: {kind}")
