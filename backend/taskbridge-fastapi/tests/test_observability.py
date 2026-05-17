from __future__ import annotations

import logging
from typing import Any

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from taskbridge.dependencies import get_auth_context_resolver, get_task_creation_service
from taskbridge.models import AuthContext, TaskCreatedResult, TaskStatus
from taskbridge.observability import log_structured
from taskbridge.routes_http import HttpRouteSettings, build_http_router


def test_log_structured_captures_taskbridge_extra(caplog: Any) -> None:
    logger = logging.getLogger("taskbridge.test")
    caplog.set_level(logging.INFO)

    log_structured(
        logger,
        level=logging.INFO,
        message="test.op",
        task_id="t1",
        event_id="e1",
        client_request_id="c1",
        user_id="u1",
        transport="ws",
        outcome="success",
        details={"foo": "bar"},
    )

    assert len(caplog.records) == 1
    record = caplog.records[0]
    assert record.message == "test.op"
    assert hasattr(record, "taskbridge")
    payload = record.taskbridge
    assert payload["taskId"] == "t1"
    assert payload["eventId"] == "e1"
    assert payload["clientRequestId"] == "c1"
    assert payload["userId"] == "u1"
    assert payload["transport"] == "ws"
    assert payload["outcome"] == "success"
    assert payload["details"] == {"foo": "bar"}


def test_http_create_task_logs_structured_data(caplog: Any) -> None:
    # Verify that the real route handler in routes_http uses log_structured correctly.

    # The logger in routes_http is named taskbridge.routes_http
    caplog.set_level(logging.INFO)

    app = FastAPI()
    settings = HttpRouteSettings()

    class FakeCreationService:
        async def create_task(self, command: Any) -> TaskCreatedResult:
            return TaskCreatedResult(
                task_id="task-logs-1",
                status=TaskStatus.ACCEPTED,
                client_request_id="client-req-logs-1",
                deduplicated=False,
            )

    class FakeAuthResolver:
        async def resolve_auth_context(self, request_context: object) -> AuthContext:
            return AuthContext(subject="user-logs-1", scopes=set())

    router = build_http_router(settings)
    app.include_router(router)

    app.dependency_overrides[get_task_creation_service] = lambda: FakeCreationService()
    app.dependency_overrides[get_auth_context_resolver] = lambda: FakeAuthResolver()

    client = TestClient(app)
    response = client.post(
        "/api/v1/tasks",
        json={"clientRequestId": "client-req-logs-1", "taskType": "test", "input": {}},
    )

    assert response.status_code == 201

    # Verify logs
    create_logs = [r for r in caplog.records if r.message == "http.task.create"]
    assert len(create_logs) == 1
    log = create_logs[0]
    assert log.taskbridge["taskId"] == "task-logs-1"
    assert log.taskbridge["clientRequestId"] == "client-req-logs-1"
    assert log.taskbridge["userId"] == "user-logs-1"
    assert log.taskbridge["transport"] == "http"
    assert log.taskbridge["outcome"] == "created"


def test_http_submit_action_logs_structured_data(caplog: Any) -> None:
    # Verify that the real route handler in routes_http uses log_structured correctly for actions.
    from taskbridge.dependencies import get_task_action_service
    from taskbridge.models import SubmitActionResult, SubmitActionResultStatus

    caplog.set_level(logging.INFO)

    app = FastAPI()
    settings = HttpRouteSettings()

    class FakeActionService:
        async def submit_action(self, command: Any) -> SubmitActionResult:
            return SubmitActionResult(
                task_id="task-logs-2",
                suspend_id="sus-1",
                client_action_id="action-logs-1",
                status=SubmitActionResultStatus.ACCEPTED,
            )

    class FakeAuthResolver:
        async def resolve_auth_context(self, request_context: object) -> AuthContext:
            return AuthContext(subject="user-logs-2", scopes=set())

    router = build_http_router(settings)
    app.include_router(router)

    app.dependency_overrides[get_task_action_service] = lambda: FakeActionService()
    app.dependency_overrides[get_auth_context_resolver] = lambda: FakeAuthResolver()

    client = TestClient(app)
    response = client.post(
        "/api/v1/tasks/task-logs-2/actions",
        json={
            "clientActionId": "action-logs-1",
            "suspendId": "sus-1",
            "actionType": "confirm",
        },
    )

    assert response.status_code == 202

    # Verify logs
    action_logs = [r for r in caplog.records if r.message == "http.task.action"]
    assert len(action_logs) == 1
    log = action_logs[0]
    assert log.taskbridge["taskId"] == "task-logs-2"
    assert log.taskbridge["clientRequestId"] == "action-logs-1"
    assert log.taskbridge["userId"] == "user-logs-2"
    assert log.taskbridge["transport"] == "http"
    assert log.taskbridge["outcome"] == "ACCEPTED"


def test_http_poll_events_logs_structured_data(caplog: Any) -> None:
    # Verify that the polling service uses log_structured correctly.
    from datetime import datetime, timezone

    from taskbridge.dependencies import get_task_polling_service
    from taskbridge.models import PollEventsResult, TaskEvent, TaskEventType

    caplog.set_level(logging.INFO)

    app = FastAPI()
    settings = HttpRouteSettings()

    now = datetime.now(timezone.utc)
    mock_event = TaskEvent(
        type=TaskEventType.TASK_PROGRESS,
        taskId="task-logs-3",
        eventId="evt-1",
        createdAt=now,
        payload={"progress": 50},
    )

    class FakePollingService:
        async def poll_events(
            self,
            task_id: str,
            auth_context: AuthContext,
            after_event_id: str | None,
            limit: int,
            wait_timeout_ms: int,
        ) -> PollEventsResult:
            return PollEventsResult(
                taskId=task_id,
                events=[mock_event],
                nextAfterEventId="evt-1",
                hasMore=False,
            )

    class FakeAuthResolver:
        async def resolve_auth_context(self, request_context: object) -> AuthContext:
            return AuthContext(subject="user-logs-3", scopes=set())

    router = build_http_router(settings)
    app.include_router(router)

    app.dependency_overrides[get_task_polling_service] = lambda: FakePollingService()
    app.dependency_overrides[get_auth_context_resolver] = lambda: FakeAuthResolver()

    client = TestClient(app)
    response = client.get("/api/v1/tasks/task-logs-3/events")

    assert response.status_code == 200

    # Verify logs
    poll_logs = [r for r in caplog.records if r.message == "http.task.poll"]
    assert len(poll_logs) == 1
    log = poll_logs[0]
    assert log.taskbridge["taskId"] == "task-logs-3"
    assert log.taskbridge["userId"] == "user-logs-3"
    assert log.taskbridge["transport"] == "http"


@pytest.mark.asyncio
async def test_task_action_service_logs_event_appended(caplog: Any) -> None:
    # Verify that the service logs when it appends a TASK_ACTION_ACCEPTED event.
    from datetime import datetime, timezone

    from taskbridge.models import (
        AuthContext,
        ResumeHandoffStatus,
        SubmitActionCommand,
        TaskActionReceipt,
        TaskRecord,
        TaskStatus,
        TaskSuspensionKind,
        TaskSuspensionRecord,
        TaskSuspensionStatus,
    )
    from taskbridge.observability import NoOpMetricsSink
    from taskbridge.services import TaskActionService

    from .test_interfaces import FakeEventStore, FakeOwnershipPolicy, FakeTaskRegistry

    class FakeSuspensionStore:
        def __init__(self, suspension: TaskSuspensionRecord | None = None) -> None:
            self.suspension = suspension

        async def get_suspension(self, task_id: str, suspend_id: str) -> TaskSuspensionRecord:
            if self.suspension is None:
                raise KeyError(suspend_id)
            return self.suspension

        async def accept_action_if_open(
            self, task_id: str, suspend_id: str, client_action_id: str
        ) -> TaskSuspensionRecord | None:
            return self.suspension

        async def mark_resume_dispatched(
            self, task_id: str, suspend_id: str
        ) -> TaskSuspensionRecord:
            return self.suspension

    class FakeActionReceiptStore:
        async def get_receipt(self, task_id: str, client_id: str) -> TaskActionReceipt | None:
            return None

        async def save_receipt(self, receipt: TaskActionReceipt) -> TaskActionReceipt:
            return receipt

    caplog.set_level(logging.INFO)

    registry = FakeTaskRegistry()
    event_store = FakeEventStore()
    receipt_store = FakeActionReceiptStore()

    class FakeResumeService:
        async def resume_task(self, command: Any, suspension: Any) -> None:
            pass

    # Setup state
    task_id = "t1"
    suspend_id = "s1"
    now = datetime.now(timezone.utc)
    task = TaskRecord(
        task_id=task_id,
        owner_id="user1",
        client_request_id="creq-1",
        task_type="test",
        input_payload={},
        status=TaskStatus.RUNNING,
        created_at=now,
        updated_at=now,
    )
    await registry.create_task(task)

    suspension = TaskSuspensionRecord(
        task_id=task_id,
        suspend_id=suspend_id,
        owner_id="user1",
        kind=TaskSuspensionKind.USER_ACTION_REQUIRED,
        reason_code="test",
        allowed_actions=["confirm"],
        status=TaskSuspensionStatus.OPEN,
        resume_handoff_status=ResumeHandoffStatus.NONE,
        schema_version=1,
        created_at=now,
    )
    suspension_store = FakeSuspensionStore(suspension)

    service = TaskActionService(
        registry=registry,
        event_store=event_store,
        suspension_store=suspension_store,  # type: ignore
        receipt_store=receipt_store,  # type: ignore
        resume_service=FakeResumeService(),  # type: ignore
        ownership_policy=FakeOwnershipPolicy(),
        metrics_sink=NoOpMetricsSink(),
    )

    command = SubmitActionCommand(
        task_id=task_id,
        client_action_id="a1",
        suspend_id=suspend_id,
        action_type="confirm",
        payload={},
        metadata={},
        auth_context=AuthContext(subject="user1", scopes={"tasks:write"}),
    )

    await service.submit_action(command)

    appended_logs = [r for r in caplog.records if r.message == "task.event.appended"]
    assert len(appended_logs) == 1
    log = appended_logs[0]
    assert log.taskbridge["taskId"] == "t1"
    assert log.taskbridge["clientRequestId"] == "a1"
    assert "eventId" in log.taskbridge
