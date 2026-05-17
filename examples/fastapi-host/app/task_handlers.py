from __future__ import annotations

from typing import Any

from taskbridge.models import TaskRecord


def workflow_name_for_task(task: TaskRecord) -> str:
    # Single workflow entrypoint; behavior selected by task_type in payload.
    return "demo_task_workflow"


def to_workflow_input(task: TaskRecord) -> dict[str, Any]:
    attachment_summaries = [
        {
            "filename": attachment.filename,
            "content_type": attachment.content_type,
            "size_bytes": attachment.size_bytes,
        }
        for attachment in task.attachments
    ]
    return {
        "task_id": task.task_id,
        "task_type": task.task_type,
        "input_payload": task.input_payload,
        "metadata": task.metadata,
        "attachments": attachment_summaries,
        "owner_id": task.owner_id,
    }


def progress_steps_for_task_type(task_type: str) -> list[dict[str, Any]]:
    if task_type == "demo.image.analyze":
        return [
            {"progress": 15, "message": "queued image metadata extraction"},
            {"progress": 45, "message": "running image feature scan"},
            {"progress": 80, "message": "building classification summary"},
        ]
    if task_type == "demo.fail":
        return [
            {"progress": 25, "message": "workflow started"},
            {"progress": 60, "message": "failing demo on purpose"},
        ]
    return [
        {"progress": 20, "message": "workflow started"},
        {"progress": 60, "message": "performing demo work"},
        {"progress": 90, "message": "finalizing result"},
    ]
