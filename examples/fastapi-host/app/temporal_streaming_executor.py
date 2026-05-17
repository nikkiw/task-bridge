from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from itertools import count
from typing import Any

from taskbridge.models import (
    ResumeHandoffStatus,
    TaskEvent,
    TaskEventType,
    TaskRecord,
    TaskStatus,
    TaskSuspensionKind,
    TaskSuspensionRecord,
    TaskSuspensionStatus,
)
from taskbridge.suspension_store import SuspensionStore
from taskbridge.task_registry import TaskRegistry
from taskbridge.worker import TaskExecutor
from taskbridge_temporal import TemporalExecutorConfig, TemporalTaskExecutor
from temporalio.api.workflowservice.v1 import GetSystemInfoRequest
from temporalio.client import Client

from .task_handlers import progress_steps_for_task_type, to_workflow_input


class _ExampleWorkflowInputMapper:
    def to_workflow_input(self, task: TaskRecord) -> dict[str, Any]:
        return to_workflow_input(task)


class StreamingTemporalExecutor(TaskExecutor):
    def __init__(
        self,
        *,
        task_registry: TaskRegistry,
        event_store: Any,
        suspension_store: SuspensionStore,
        temporal_target: str,
        temporal_namespace: str,
        task_queue: str,
    ) -> None:
        self._task_registry = task_registry
        self._event_store = event_store
        self._suspension_store = suspension_store
        self._temporal_target = temporal_target
        self._temporal_namespace = temporal_namespace
        self._client: Client | None = None
        self._client_lock = asyncio.Lock()
        self._task_queue = task_queue
        self._workflow_id_prefix = "taskbridge"
        self._monitor_jobs: dict[str, asyncio.Task[None]] = {}
        self._event_seq = count(1)
        self._base_executor: TemporalTaskExecutor | None = None

    async def submit_task(self, task: TaskRecord) -> None:
        client = await self._get_client()

        # Decide workflow based on task type
        workflow_name = "demo_task_workflow"
        if task.task_type == "enterprise.document.review":
            workflow_name = "enterprise_document_workflow"

        executor = TemporalTaskExecutor(
            temporal_client=client,
            config=TemporalExecutorConfig(
                task_queue=self._task_queue,
                workflow=workflow_name,
                workflow_input_mapper=_ExampleWorkflowInputMapper(),
            ),
        )

        await executor.submit_task(task)
        await self._task_registry.update_task_status(task.task_id, TaskStatus.RUNNING)
        await self._append_event(
            task_id=task.task_id,
            event_type=TaskEventType.TASK_STARTED,
            payload={"source": "temporal", "taskType": task.task_type},
        )
        monitor = asyncio.create_task(self._monitor_task(task))
        self._monitor_jobs[task.task_id] = monitor

    async def request_cancellation(self, task: TaskRecord) -> None:
        # We need a temporary executor to request cancellation
        client = await self._get_client()
        executor = TemporalTaskExecutor(
            temporal_client=client,
            config=TemporalExecutorConfig(
                task_queue=self._task_queue,
                workflow="dummy", # Workflow name doesn't matter for cancellation by ID
                workflow_input_mapper=_ExampleWorkflowInputMapper(),
            ),
        )
        await executor.request_cancellation(task)

    async def check_health(self) -> bool:
        try:
            client = await self._get_client()
            await client.workflow_service.get_system_info(GetSystemInfoRequest())
            return True
        except Exception:
            return False

    async def _monitor_task(self, task: TaskRecord) -> None:
        client = await self._get_client()
        handle = client.get_workflow_handle(self._workflow_id_for(task.task_id))
        try:
            for step in progress_steps_for_task_type(task.task_type):
                await asyncio.sleep(0.35)
                await self._append_event(
                    task_id=task.task_id,
                    event_type=TaskEventType.TASK_PROGRESS,
                    payload=step,
                )

            # Special logic for enterprise review
            if task.task_type == "enterprise.document.review":
                await asyncio.sleep(1.0)
                suspend_id = f"review-{task.task_id}"

                # Create suspension record in store so it can be resolved
                suspension = TaskSuspensionRecord(
                    task_id=task.task_id,
                    suspend_id=suspend_id,
                    owner_id=task.owner_id,
                    status=TaskSuspensionStatus.OPEN,
                    kind=TaskSuspensionKind.USER_ACTION_REQUIRED,
                    reason_code="manual_review",
                    schema_version=1,
                    allowed_actions=["approve", "reject"],
                    resume_handoff_status=ResumeHandoffStatus.NONE,
                    created_at=datetime.now(UTC),
                )
                # Hack: Accessing private dict for the in-memory store in the example
                if hasattr(self._suspension_store, "suspensions"):
                    self._suspension_store.suspensions[(task.task_id, suspend_id)] = suspension

                await self._append_event(
                    task_id=task.task_id,
                    event_type=TaskEventType.TASK_SUSPENDED,
                    payload={
                        "suspendId": suspend_id,
                        "kind": TaskSuspensionKind.USER_ACTION_REQUIRED,
                        "reasonCode": "manual_review",
                        "schemaVersion": 1,
                        "message": "Manual review required: Document content looks suspicious.",
                        "allowedActions": ["approve", "reject"]
                    }
                )

            result = await handle.result()
            await self._task_registry.update_task_status(task.task_id, TaskStatus.COMPLETED)

            # Ensure 'message' is present for the Android sample UI
            completion_payload = {"result": result}
            if isinstance(result, dict) and "final_note" in result:
                completion_payload["message"] = result["final_note"]
            else:
                completion_payload["message"] = str(result)

            await self._append_event(
                task_id=task.task_id,
                event_type=TaskEventType.TASK_COMPLETED,
                payload=completion_payload,
            )
        except asyncio.CancelledError:
            await self._task_registry.update_task_status(task.task_id, TaskStatus.CANCELLED)
            await self._append_event(
                task_id=task.task_id,
                event_type=TaskEventType.TASK_CANCELLED,
                payload={"reason": "monitor_cancelled"},
            )
            raise
        except Exception as exc:
            await self._task_registry.update_task_status(task.task_id, TaskStatus.FAILED)
            await self._append_event(
                task_id=task.task_id,
                event_type=TaskEventType.TASK_FAILED,
                payload={"message": str(exc)},
            )
        finally:
            self._monitor_jobs.pop(task.task_id, None)

    async def _append_event(
        self,
        *,
        task_id: str,
        event_type: TaskEventType,
        payload: dict[str, Any],
    ) -> None:
        event = TaskEvent(
            type=event_type,
            taskId=task_id,
            eventId=f"local-{next(self._event_seq)}",
            createdAt=datetime.now(UTC),
            payload=payload,
        )
        await self._event_store.append_event(event)

    async def _get_client(self) -> Client:
        if self._client is not None:
            return self._client
        async with self._client_lock:
            if self._client is None:
                self._client = await Client.connect(
                    self._temporal_target,
                    namespace=self._temporal_namespace,
                )
            return self._client

    def _workflow_id_for(self, task_id: str) -> str:
        return f"{self._workflow_id_prefix}:{task_id}"
