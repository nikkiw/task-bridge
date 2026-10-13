from __future__ import annotations

import asyncio
from typing import Any

from taskbridge.models import AuthContext, SubmitActionCommand

from app.temporal_resume_service import TemporalTaskResumeService


def test_resume_signal_preserves_action_id_payload_and_metadata() -> None:
    signals: list[tuple[str, dict[str, Any]]] = []

    class WorkflowHandle:
        async def signal(self, name: str, payload: dict[str, Any]) -> None:
            signals.append((name, payload))

    class TemporalClient:
        def get_workflow_handle(self, workflow_id: str) -> WorkflowHandle:
            assert workflow_id == "workflow-task-1"
            return WorkflowHandle()

    async def client_factory() -> TemporalClient:
        return TemporalClient()

    service = TemporalTaskResumeService(
        client_factory=client_factory,
        workflow_id_fn=lambda task_id: f"workflow-{task_id}",
    )
    command = SubmitActionCommand(
        task_id="task-1",
        client_action_id="action-1",
        suspend_id="suspend-1",
        action_type="a2ui_action",
        payload={"answer": "yes", "nested": {"selection": ["a"]}},
        metadata={"modelSnapshot": {"answer": "yes", "nested": {"selection": ["a"]}}},
        auth_context=AuthContext(subject="user-1"),
    )

    asyncio.run(service.resume_task(command, suspension=None))

    assert signals == [
        (
            "action_submitted",
            {
                "suspend_id": "suspend-1",
                "client_action_id": "action-1",
                "action_type": "a2ui_action",
                "payload": {"answer": "yes", "nested": {"selection": ["a"]}},
                "metadata": {"modelSnapshot": {"answer": "yes", "nested": {"selection": ["a"]}}},
            },
        )
    ]
