from __future__ import annotations

from datetime import timedelta
from typing import Any

from temporalio import activity, workflow


@activity.defn
async def run_demo_task_activity(payload: dict[str, Any]) -> dict[str, Any]:
    task_type = payload.get("task_type", "demo.success")
    input_payload = payload.get("input_payload") or {}
    attachments = payload.get("attachments") or []

    if task_type == "demo.fail":
        raise RuntimeError("Intentional failure for demo.fail scenario")

    if task_type == "demo.image.analyze":
        first = attachments[0] if attachments else {}
        return {
            "kind": "image_analysis",
            "summary": "demo-analysis-complete",
            "attachment": {
                "filename": first.get("filename"),
                "content_type": first.get("content_type"),
                "size_bytes": first.get("size_bytes"),
            },
            "labels": ["sample", "preview", "non-production"],
            "input_echo": input_payload,
        }

    return {
        "kind": "success",
        "echo": input_payload,
        "details": {"note": "Completed by Temporal demo workflow"},
    }


@workflow.defn(name="demo_task_workflow")
class DemoTaskWorkflow:
    @workflow.run
    async def run(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await workflow.execute_activity(
            run_demo_task_activity,
            payload,
            start_to_close_timeout=timedelta(seconds=30),
        )


@workflow.defn(name="enterprise_document_workflow")
class EnterpriseDocumentWorkflow:
    def __init__(self) -> None:
        self._action_result: dict[str, Any] | None = None

    @workflow.signal(name="action_submitted")
    async def submit_action(self, payload: dict[str, Any]) -> None:
        self._action_result = payload

    @workflow.run
    async def run(self, payload: dict[str, Any]) -> dict[str, Any]:
        # Step 1: Initial analysis
        await workflow.execute_activity(
            run_demo_task_activity,
            {**payload, "step": "initial_analysis"},
            start_to_close_timeout=timedelta(seconds=10),
        )

        # Step 2: Suspend and wait for human review
        workflow.logger.info("Suspending workflow for human review")
        
        # In a real system, we'd emit TASK_SUSPENDED here.
        # For this demo, we assume the StreamingTemporalExecutor or 
        # a dedicated Activity will handle the SuspensionStore entry.
        
        await workflow.wait_condition(lambda: self._action_result is not None)

        # Step 3: Final processing based on human input
        action = self._action_result["action_type"]
        return {
            "status": "processed",
            "human_decision": action,
            "final_note": f"Document was {action} by user"
        }
