from __future__ import annotations

import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from taskbridge.errors import IdempotencyConflictError, InvalidTaskStateError
from taskbridge.models import (
    AuthContext,
    CancelTaskResult,
    CancelTaskStatus,
    HttpCancelTaskRequest,
    HttpTaskCreateRequest,
    PollEventsResult,
    TaskCreateCommand,
    TaskCreatedResult,
    TaskEvent,
    TaskEventType,
    TaskRecord,
    TaskStatus,
    WebSocketHeartbeat,
    WebSocketSubscribeRequest,
    WebSocketSubscriptionConfirmed,
    ensure_transition,
    same_idempotency_payload,
)

FIXTURES_DIR = Path(__file__).resolve().parents[3] / "protocol" / "examples"


def load_fixture(name: str) -> dict:
    return json.loads((FIXTURES_DIR / name).read_text())


def build_auth_context(subject: str = "user-1") -> AuthContext:
    return AuthContext(subject=subject, scopes={"tasks:read", "tasks:write"})


def build_task_command(
    *,
    client_request_id: str = "req-1",
    task_type: str = "vision.classify",
    input_payload: dict | None = None,
    metadata: dict | None = None,
    auth_context: AuthContext | None = None,
) -> TaskCreateCommand:
    return TaskCreateCommand(
        client_request_id=client_request_id,
        task_type=task_type,
        input_payload=input_payload or {"image_id": "img-1"},
        metadata=metadata or {"source": "android"},
        auth_context=auth_context or build_auth_context(),
    )


def build_task_record(
    *,
    status: TaskStatus = TaskStatus.ACCEPTED,
    client_request_id: str = "req-1",
    auth_context: AuthContext | None = None,
) -> TaskRecord:
    command = build_task_command(
        client_request_id=client_request_id,
        auth_context=auth_context or build_auth_context(),
    )
    return TaskRecord.from_command(task_id="task-1", command=command)


def test_task_event_accepts_protocol_examples() -> None:
    fixtures = [
        "task_started.json",
        "task_progress.json",
        "task_message.json",
        "task_completed.json",
        "task_failed.json",
        "task_cancelled.json",
    ]

    parsed = [TaskEvent.model_validate(load_fixture(name)) for name in fixtures]

    assert [event.type for event in parsed] == [
        TaskEventType.TASK_STARTED,
        TaskEventType.TASK_PROGRESS,
        TaskEventType.TASK_MESSAGE,
        TaskEventType.TASK_COMPLETED,
        TaskEventType.TASK_FAILED,
        TaskEventType.TASK_CANCELLED,
    ]


def test_task_event_requires_created_at() -> None:
    fixture = load_fixture("task_started.json")
    fixture.pop("createdAt")

    with pytest.raises(ValidationError):
        TaskEvent.model_validate(fixture)


def test_poll_events_result_accepts_protocol_fixture() -> None:
    result = PollEventsResult.model_validate(load_fixture("poll_events_response.json"))

    assert result.task_id == "task_123"
    assert result.next_after_event_id == "4"
    assert len(result.events) == 2


def test_task_record_round_trips_from_command() -> None:
    command = build_task_command()

    record = TaskRecord.from_command(task_id="task-123", command=command)

    assert record.task_id == "task-123"
    assert record.client_request_id == command.client_request_id
    assert record.owner_id == command.auth_context.subject
    assert record.status is TaskStatus.ACCEPTED


def test_same_idempotency_payload_accepts_equivalent_payload() -> None:
    command = build_task_command()
    record = TaskRecord.from_command(task_id="task-123", command=command)

    assert same_idempotency_payload(record, command) is True


def test_same_idempotency_payload_ignores_metadata_differences() -> None:
    original = build_task_command(metadata={"source": "android", "trace_id": "trace-1"})
    replay = build_task_command(metadata={"source": "android", "trace_id": "trace-2"})
    record = TaskRecord.from_command(task_id="task-123", command=original)

    assert same_idempotency_payload(record, replay) is True


def test_same_idempotency_payload_rejects_conflicting_payload() -> None:
    original = build_task_command()
    conflicting = build_task_command(input_payload={"image_id": "img-2"})
    record = TaskRecord.from_command(task_id="task-123", command=original)

    assert same_idempotency_payload(record, conflicting) is False


def test_ensure_transition_rejects_terminal_to_running() -> None:
    with pytest.raises(InvalidTaskStateError):
        ensure_transition(TaskStatus.COMPLETED, TaskStatus.RUNNING)


def test_cancel_task_result_supports_terminal_outcome() -> None:
    result = CancelTaskResult(
        task_id="task-1",
        status=CancelTaskStatus.ALREADY_TERMINAL,
    )

    assert result.status is CancelTaskStatus.ALREADY_TERMINAL


def test_task_created_result_tracks_deduplication() -> None:
    result = TaskCreatedResult(
        task_id="task-1",
        status=TaskStatus.ACCEPTED,
        client_request_id="req-1",
        deduplicated=True,
    )

    assert result.deduplicated is True


def test_idempotency_conflict_error_has_stable_code() -> None:
    error = IdempotencyConflictError("conflict")

    assert error.code == "IDEMPOTENCY_CONFLICT"


def test_http_task_create_request_does_not_embed_auth_context() -> None:
    request = HttpTaskCreateRequest(
        clientRequestId="req-1",
        taskType="vision.classify",
        input={"image_id": "img-1"},
        metadata={"source": "android"},
    )

    assert request.client_request_id == "req-1"
    assert "auth_context" not in request.model_dump()


def test_http_cancel_request_does_not_require_auth_context() -> None:
    request = HttpCancelTaskRequest(reason="cancelled_by_user")

    assert request.reason == "cancelled_by_user"


def test_websocket_subscribe_request_accepts_protocol_fixture() -> None:
    request = WebSocketSubscribeRequest.model_validate(
        load_fixture("websocket_subscribe_request.json")
    )

    assert request.action == "subscribe"
    assert request.task_id == "task_123"
    assert request.last_event_id == "4"


def test_websocket_subscription_confirmed_accepts_protocol_fixture() -> None:
    response = WebSocketSubscriptionConfirmed.model_validate(
        load_fixture("websocket_subscribe_ack.json")
    )

    assert response.type == "SUBSCRIPTION_CONFIRMED"
    assert response.task_id == "task_123"
    assert response.live is True


def test_websocket_heartbeat_accepts_protocol_fixture() -> None:
    heartbeat = WebSocketHeartbeat.model_validate(load_fixture("websocket_heartbeat.json"))

    assert heartbeat.type == "HEARTBEAT"
    assert heartbeat.task_id == "task_123"
    assert heartbeat.live is True
