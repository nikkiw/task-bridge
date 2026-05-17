from __future__ import annotations

from typing import Any

from fastapi import FastAPI
from fastapi.testclient import TestClient

from taskbridge.dependencies import (
    get_auth_context_resolver,
    get_metrics_sink,
    get_readiness_probe,
    get_task_action_service,
    get_task_cancellation_service,
    get_task_creation_service,
    get_task_polling_service,
    get_upload_policy,
)
from taskbridge.errors import (
    AuthenticationError,
    IdempotencyConflictError,
    TaskNotFoundError,
    UploadValidationError,
)
from taskbridge.models import (
    AuthContext,
    CancelTaskResult,
    CancelTaskStatus,
    PollEventsResult,
    SubmitActionResult,
    SubmitActionResultStatus,
    TaskCreatedResult,
    TaskStatus,
)
from taskbridge.routes_http import (
    HttpRouteSettings,
    build_http_router,
    install_http_exception_handlers,
)
from taskbridge.security import AllowAllUploadPolicy

from .test_interfaces import RecordingMetricsSink


class FakeAuthResolver:
    async def resolve_auth_context(self, request_context: object) -> AuthContext:
        del request_context
        return AuthContext(subject="user-1", scopes={"tasks:read", "tasks:write"})


class RejectingAuthResolver:
    async def resolve_auth_context(self, request_context: object) -> AuthContext:
        del request_context
        raise AuthenticationError("missing bearer token")


class RecordingUploadPolicy(AllowAllUploadPolicy):
    def __init__(self, *, error: Exception | None = None) -> None:
        self.error = error
        self.calls: list[dict[str, Any]] = []

    async def assert_upload_allowed(
        self,
        auth_context: AuthContext,
        attachments: list[Any],
    ) -> None:
        self.calls.append({"auth_context": auth_context, "attachments": attachments})
        if self.error is not None:
            raise self.error


class FakeReadinessProbe:
    def __init__(self, *, ready: bool = True, details: dict[str, Any] | None = None) -> None:
        self.ready = ready
        self.details = details or {}

    async def check_readiness(self) -> tuple[bool, dict[str, Any]]:
        return self.ready, self.details


class FakeTaskCreationService:
    def __init__(
        self,
        *,
        result: TaskCreatedResult | None = None,
        error: Exception | None = None,
    ) -> None:
        self.result = result or TaskCreatedResult(
            task_id="task-1",
            status=TaskStatus.ACCEPTED,
            client_request_id="req-1",
            deduplicated=False,
        )
        self.error = error
        self.commands: list[Any] = []

    async def create_task(self, command: Any) -> TaskCreatedResult:
        self.commands.append(command)
        if self.error is not None:
            raise self.error
        return self.result


class FakeTaskPollingService:
    def __init__(
        self,
        *,
        result: PollEventsResult | None = None,
        error: Exception | None = None,
    ) -> None:
        self.result = result or PollEventsResult(
            taskId="task-1",
            events=[],
            nextAfterEventId=None,
            hasMore=False,
        )
        self.error = error
        self.calls: list[dict[str, Any]] = []

    async def poll_events(self, **kwargs: Any) -> PollEventsResult:
        self.calls.append(kwargs)
        if self.error is not None:
            raise self.error
        return self.result


class FakeTaskCancellationService:
    def __init__(self, *, error: Exception | None = None) -> None:
        self.error = error
        self.commands: list[Any] = []

    async def cancel_task(self, command: Any) -> CancelTaskResult:
        self.commands.append(command)
        if self.error is not None:
            raise self.error
        return CancelTaskResult(
            task_id=command.task_id,
            status=CancelTaskStatus.CANCELLATION_REQUESTED,
        )


class FakeTaskActionService:
    def __init__(
        self,
        *,
        result: SubmitActionResult | None = None,
        error: Exception | None = None,
    ) -> None:
        self.error = error
        self.result = result or SubmitActionResult(
            taskId="task-1",
            suspendId="suspend-1",
            clientActionId="action-1",
            status=SubmitActionResultStatus.ACCEPTED,
        )
        self.commands: list[Any] = []

    async def submit_action(self, command: Any) -> SubmitActionResult:
        self.commands.append(command)
        if self.error is not None:
            raise self.error
        return self.result


def build_app(
    *,
    route_settings: HttpRouteSettings | None = None,
    create_service: FakeTaskCreationService | None = None,
    polling_service: FakeTaskPollingService | None = None,
    cancellation_service: FakeTaskCancellationService | None = None,
    action_service: FakeTaskActionService | None = None,
    readiness_probe: FakeReadinessProbe | None = None,
    auth_resolver: object | None = None,
    upload_policy: RecordingUploadPolicy | None = None,
) -> tuple[
    TestClient,
    FakeTaskCreationService,
    FakeTaskPollingService,
    FakeTaskCancellationService,
    FakeTaskActionService,
    RecordingMetricsSink,
]:
    app = FastAPI()
    install_http_exception_handlers(app)
    app.include_router(build_http_router(route_settings or HttpRouteSettings()))

    create = create_service or FakeTaskCreationService()
    poll = polling_service or FakeTaskPollingService()
    cancel = cancellation_service or FakeTaskCancellationService()
    action = action_service or FakeTaskActionService()
    metrics = RecordingMetricsSink()

    app.dependency_overrides[get_auth_context_resolver] = lambda: (
        auth_resolver or FakeAuthResolver()
    )
    app.dependency_overrides[get_task_creation_service] = lambda: create
    app.dependency_overrides[get_task_polling_service] = lambda: poll
    app.dependency_overrides[get_task_cancellation_service] = lambda: cancel
    app.dependency_overrides[get_task_action_service] = lambda: action
    app.dependency_overrides[get_readiness_probe] = lambda: readiness_probe or FakeReadinessProbe()
    app.dependency_overrides[get_upload_policy] = lambda: upload_policy or RecordingUploadPolicy()
    app.dependency_overrides[get_metrics_sink] = lambda: metrics

    return TestClient(app), create, poll, cancel, action, metrics


def test_create_task_accepts_json_request_body() -> None:
    client, create_service, _, _, _, _ = build_app()

    response = client.post(
        "/api/v1/tasks",
        json={
            "clientRequestId": "req-1",
            "taskType": "vision.classify",
            "input": {"image_id": "img-1"},
            "metadata": {"source": "android"},
        },
    )

    assert response.status_code == 201
    assert response.json()["taskId"] == "task-1"
    assert create_service.commands[0].attachments == []


def test_create_task_accepts_multipart_request_and_normalizes_attachment_name() -> None:
    upload_policy = RecordingUploadPolicy()
    client, create_service, _, _, _, _ = build_app(upload_policy=upload_policy)

    response = client.post(
        "/api/v1/tasks",
        data={
            "clientRequestId": "req-1",
            "taskType": "vision.classify",
            "input": '{"image_id":"img-1"}',
            "metadata": '{"source":"android"}',
        },
        files={"attachment": ("../unsafe/photo.png", b"binary-image", "image/png")},
    )

    assert response.status_code == 201
    attachment = create_service.commands[0].attachments[0]
    assert attachment.filename == "photo.png"
    assert attachment.content_type == "image/png"
    assert attachment.size_bytes == len(b"binary-image")
    assert upload_policy.calls[0]["attachments"][0].filename == "photo.png"


def test_create_task_returns_200_for_deduplicated_request() -> None:
    client, _, _, _, _, _ = build_app(
        create_service=FakeTaskCreationService(
            result=TaskCreatedResult(
                task_id="task-1",
                status=TaskStatus.ACCEPTED,
                client_request_id="req-1",
                deduplicated=True,
            )
        )
    )

    response = client.post(
        "/api/v1/tasks",
        json={
            "clientRequestId": "req-1",
            "taskType": "vision.classify",
            "input": {"image_id": "img-1"},
        },
    )

    assert response.status_code == 200


def test_create_task_rejects_disallowed_upload_type_with_stable_error_envelope() -> None:
    client, _, _, _, _, _ = build_app(
        route_settings=HttpRouteSettings(allowed_upload_content_types=frozenset({"image/png"}))
    )

    response = client.post(
        "/api/v1/tasks",
        data={
            "clientRequestId": "req-1",
            "taskType": "vision.classify",
            "input": '{"image_id":"img-1"}',
        },
        files={"attachment": ("note.txt", b"plain-text", "text/plain")},
    )

    assert response.status_code == 400
    assert response.json()["code"] == UploadValidationError.code


def test_create_task_rejects_invalid_json_multipart_field() -> None:
    client, _, _, _, _, _ = build_app()

    response = client.post(
        "/api/v1/tasks",
        data={
            "clientRequestId": "req-1",
            "taskType": "vision.classify",
            "input": "{not-json}",
        },
    )

    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_REQUEST"


def test_create_task_returns_401_when_auth_context_resolution_fails() -> None:
    client, _, _, _, _, _ = build_app(auth_resolver=RejectingAuthResolver())

    response = client.post(
        "/api/v1/tasks",
        json={
            "clientRequestId": "req-1",
            "taskType": "vision.classify",
            "input": {"image_id": "img-1"},
        },
    )

    assert response.status_code == 401
    assert response.json()["code"] == AuthenticationError.code


def test_create_task_rejects_upload_when_policy_denies_it() -> None:
    client, _, _, _, _, _ = build_app(
        upload_policy=RecordingUploadPolicy(error=UploadValidationError("upload quota exceeded"))
    )

    response = client.post(
        "/api/v1/tasks",
        data={
            "clientRequestId": "req-1",
            "taskType": "vision.classify",
            "input": '{"image_id":"img-1"}',
        },
        files={"attachment": ("photo.png", b"binary-image", "image/png")},
    )

    assert response.status_code == 400
    assert response.json()["code"] == UploadValidationError.code


def test_poll_events_forwards_query_parameters_to_service() -> None:
    client, _, polling_service, _, _, metrics = build_app()

    response = client.get(
        "/api/v1/tasks/task-1/events",
        params={"afterEventId": "10-0", "waitTimeoutMs": 2500, "maxEvents": 25},
    )

    assert response.status_code == 200
    assert polling_service.calls[0]["after_event_id"] == "10-0"
    assert polling_service.calls[0]["wait_timeout_ms"] == 2500
    assert polling_service.calls[0]["limit"] == 25
    assert ("fallbacks_total", 1, None) in metrics.counters


def test_cancel_task_returns_202() -> None:
    client, _, _, cancellation_service, _, metrics = build_app()

    response = client.post("/api/v1/tasks/task-1/cancel", json={"reason": "user_requested"})

    assert response.status_code == 202
    assert cancellation_service.commands[0].reason == "user_requested"
    assert ("cancels_total", 1, None) in metrics.counters


def test_submit_action_accepts_request_body_and_returns_202() -> None:
    client, _, _, _, action_service, _ = build_app()

    response = client.post(
        "/api/v1/tasks/task-1/actions",
        json={
            "clientActionId": "action-1",
            "suspendId": "suspend-1",
            "actionType": "submit_form",
            "payload": {"answer": "yes"},
            "metadata": {"source": "android"},
        },
    )

    assert response.status_code == 202
    assert response.json()["status"] == "ACCEPTED"
    assert action_service.commands[0].task_id == "task-1"
    assert action_service.commands[0].client_action_id == "action-1"
    assert action_service.commands[0].suspend_id == "suspend-1"
    assert action_service.commands[0].payload == {"answer": "yes"}


def test_submit_action_returns_200_for_deduplicated_request() -> None:
    client, _, _, _, _, _ = build_app(
        action_service=FakeTaskActionService(
            result=SubmitActionResult(
                taskId="task-1",
                suspendId="suspend-1",
                clientActionId="action-1",
                status=SubmitActionResultStatus.DEDUPLICATED,
            )
        )
    )

    response = client.post(
        "/api/v1/tasks/task-1/actions",
        json={
            "clientActionId": "action-1",
            "suspendId": "suspend-1",
            "actionType": "submit_form",
            "payload": {"answer": "yes"},
        },
    )

    assert response.status_code == 200


def test_poll_events_masks_foreign_task_as_not_found() -> None:
    client, _, polling_service, _, _, _ = build_app(
        polling_service=FakeTaskPollingService(error=TaskNotFoundError("task-foreign"))
    )

    response = client.get("/api/v1/tasks/task-foreign/events")

    assert response.status_code == 404
    assert polling_service.calls[0]["task_id"] == "task-foreign"


def test_cancel_task_masks_foreign_task_as_not_found() -> None:
    client, _, _, cancellation_service, _, _ = build_app(
        cancellation_service=FakeTaskCancellationService(error=TaskNotFoundError("task-foreign"))
    )

    response = client.post("/api/v1/tasks/task-foreign/cancel", json={"reason": "user_requested"})

    assert response.status_code == 404
    assert cancellation_service.commands[0].task_id == "task-foreign"


def test_health_returns_ok() -> None:
    client, _, _, _, _, _ = build_app()

    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_ready_uses_readiness_probe() -> None:
    client, _, _, _, _, _ = build_app(
        readiness_probe=FakeReadinessProbe(ready=False, details={"redis": "down"})
    )

    response = client.get("/ready")

    assert response.status_code == 503
    assert response.json()["status"] == "not_ready"
    assert response.json()["details"] == {"redis": "down"}


def test_taskbridge_errors_are_mapped_to_stable_json_envelope() -> None:
    client, _, _, _, _, _ = build_app(
        create_service=FakeTaskCreationService(error=IdempotencyConflictError("conflict"))
    )

    response = client.post(
        "/api/v1/tasks",
        json={
            "clientRequestId": "req-1",
            "taskType": "vision.classify",
            "input": {"image_id": "img-1"},
        },
    )

    assert response.status_code == 409
    assert response.json()["code"] == IdempotencyConflictError.code
    assert response.json()["message"] == "conflict"


def test_not_found_errors_are_mapped_to_404() -> None:
    client, _, polling_service, _, _, _ = build_app(
        polling_service=FakeTaskPollingService(error=TaskNotFoundError("task-404"))
    )

    response = client.get("/api/v1/tasks/task-404/events")

    assert response.status_code == 404
    assert polling_service.calls[0]["task_id"] == "task-404"
    assert response.json()["code"] == TaskNotFoundError.code
