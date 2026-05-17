from __future__ import annotations

import logging
from typing import Any

from taskbridge.models import SubmitActionCommand
from taskbridge.services import TaskResumeService
from temporalio.client import Client

logger = logging.getLogger(__name__)

class TemporalTaskResumeService(TaskResumeService):
    """Resumes a Temporal workflow by sending a signal."""

    def __init__(self, client_factory: Any, workflow_id_fn: Any):
        self._get_client = client_factory
        self._workflow_id_fn = workflow_id_fn

    async def resume_task(
        self,
        command: SubmitActionCommand,
        suspension: Any,
    ) -> None:
        """Send a signal to the Temporal workflow to resume it."""
        client = await self._get_client()
        workflow_id = self._workflow_id_fn(command.task_id)
        handle = client.get_workflow_handle(workflow_id)
        
        logger.info(f"Resuming task {command.task_id} via Temporal signal 'action_submitted'")
        
        await handle.signal(
            "action_submitted",
            {
                "suspend_id": command.suspend_id,
                "action_type": command.action_type,
                "payload": command.payload
            }
        )
