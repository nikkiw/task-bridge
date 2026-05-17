from __future__ import annotations

from datetime import timedelta
from typing import Any

from taskbridge.worker import TaskExecutor

from .types import TemporalExecutorConfig

try:
    from temporalio.exceptions import WorkflowAlreadyStartedError
except Exception:  # pragma: no cover - import fallback for static analysis
    WorkflowAlreadyStartedError = RuntimeError


class TemporalTaskExecutor(TaskExecutor):
    """Temporal-backed TaskExecutor.

    The adapter only bridges submit/cancel orchestration. Emitting progress and
    terminal TaskBridge events should be done by host workflow callbacks that
    append to EventStore and update TaskRegistry.
    """

    def __init__(self, *, temporal_client: Any, config: TemporalExecutorConfig) -> None:
        self._client = temporal_client
        self._config = config

    async def submit_task(self, task) -> None:
        workflow_id = self._config.workflow_id_for(task)
        input_payload = self._config.workflow_input_mapper.to_workflow_input(task)
        timeout = (
            timedelta(seconds=self._config.start_timeout_seconds)
            if self._config.start_timeout_seconds is not None
            else None
        )

        try:
            await self._client.start_workflow(
                self._config.workflow,
                input_payload,
                id=workflow_id,
                task_queue=self._config.task_queue,
                execution_timeout=timeout,
            )
        except WorkflowAlreadyStartedError:
            # Idempotent create semantics: if workflow already exists for task_id,
            # treat submit as successful and let poll/replay paths continue.
            return

    async def request_cancellation(self, task) -> None:
        workflow_id = self._config.workflow_id_for(task)
        handle = self._client.get_workflow_handle(workflow_id)
        try:
            if self._config.cancellation_mode == "signal":
                await handle.signal("request_cancel")
            else:
                await handle.cancel()
        except Exception:
            # Fail-safe by design: registry already tracks cancellation_requested.
            return
