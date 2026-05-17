from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from taskbridge.dependencies import (
    get_auth_context_resolver,
    get_metrics_sink,
    get_websocket_subscription_service,
)
from taskbridge.errors import AuthenticationError
from taskbridge.models import AuthContext, TaskEvent, TaskEventType, TaskRecord
from taskbridge.routes_ws import WebSocketRouteSettings, build_ws_router
from taskbridge.services import WebSocketSubscriptionService

from .test_interfaces import FakeOwnershipPolicy, FakeTaskRegistry, RecordingMetricsSink
from .test_services import build_auth_context, build_command


class FakeAuthResolver:
    async def resolve_auth_context(self, request_context: object) -> AuthContext:
        del request_context
        return AuthContext(subject="user-1", scopes={"tasks:read", "tasks:write"})


class RejectingAuthResolver:
    async def resolve_auth_context(self, request_context: object) -> AuthContext:
        del request_context
        raise AuthenticationError("missing bearer token")


class SequencedEventStore:
    def __init__(
        self,
        *,
        replay_events: list[TaskEvent] | None = None,
        live_batches: list[list[TaskEvent]] | None = None,
    ) -> None:
        self.replay_events = replay_events or []
        self.live_batches = live_batches or []
        self.wait_calls: list[dict[str, Any]] = []

    async def append_event(self, event: TaskEvent) -> TaskEvent:
        self.replay_events.append(event)
        return event

    async def read_events_after(
        self, task_id: str, after_event_id: str | None, limit: int
    ) -> list[TaskEvent]:
        return self._filter_events(self.replay_events, task_id, after_event_id)[:limit]

    async def wait_for_events(
        self, task_id: str, after_event_id: str | None, limit: int, timeout_ms: int
    ) -> list[TaskEvent]:
        self.wait_calls.append(
            {
                "task_id": task_id,
                "after_event_id": after_event_id,
                "limit": limit,
                "timeout_ms": timeout_ms,
            }
        )
        if not self.live_batches:
            return []
        batch = self.live_batches.pop(0)
        return self._filter_events(batch, task_id, after_event_id)[:limit]

    @staticmethod
    def _filter_events(
        events: list[TaskEvent],
        task_id: str,
        after_event_id: str | None,
    ) -> list[TaskEvent]:
        filtered = [event for event in events if event.task_id == task_id]
        if after_event_id is None:
            return filtered
        return [event for event in filtered if _is_newer(event.event_id, after_event_id)]


def make_event(task_id: str, event_id: str, event_type: TaskEventType) -> TaskEvent:
    return TaskEvent(
        type=event_type,
        taskId=task_id,
        eventId=event_id,
        createdAt=datetime(2026, 5, 5, 12, 0, 0, tzinfo=UTC),
        payload={"event": event_type.value},
    )


def build_ws_client(
    *,
    replay_events: list[TaskEvent] | None = None,
    live_batches: list[list[TaskEvent]] | None = None,
    settings: WebSocketRouteSettings | None = None,
    auth_resolver: object | None = None,
    owner_subject: str = "user-1",
) -> tuple[TestClient, SequencedEventStore, str, RecordingMetricsSink]:
    app = FastAPI()
    app.include_router(build_ws_router(settings or WebSocketRouteSettings(wait_timeout_ms=5)))

    registry = FakeTaskRegistry()
    task_id = "task-ws-1"
    registry.tasks[task_id] = TaskRecord.from_command(
        task_id=task_id,
        command=build_command(auth_context=build_auth_context(owner_subject)),
    )

    store = SequencedEventStore(replay_events=replay_events, live_batches=live_batches)
    service = WebSocketSubscriptionService(
        registry=registry,
        event_store=store,
        ownership_policy=FakeOwnershipPolicy(),
    )

    app.dependency_overrides[get_auth_context_resolver] = lambda: (
        auth_resolver or FakeAuthResolver()
    )
    app.dependency_overrides[get_websocket_subscription_service] = lambda: service
    metrics = RecordingMetricsSink()
    app.dependency_overrides[get_metrics_sink] = lambda: metrics

    return TestClient(app), store, task_id, metrics


def _is_newer(candidate: str, checkpoint: str) -> bool:
    left = tuple(int(part) for part in candidate.split("-", maxsplit=1))
    right = tuple(int(part) for part in checkpoint.split("-", maxsplit=1))
    return left > right


@pytest.mark.anyio
async def test_websocket_route_replays_from_last_event_id_and_then_streams_live_events() -> None:
    replay_event = make_event("task-ws-1", "2-0", TaskEventType.TASK_PROGRESS)
    live_event = make_event("task-ws-1", "3-0", TaskEventType.TASK_COMPLETED)
    client, store, task_id, metrics = build_ws_client(
        replay_events=[replay_event],
        live_batches=[[live_event]],
    )

    with client.websocket_connect("/api/v1/tasks/ws") as websocket:
        websocket.send_json({"action": "subscribe", "taskId": task_id, "lastEventId": "1-0"})

        ack = websocket.receive_json()
        assert ack == {
            "type": "SUBSCRIPTION_CONFIRMED",
            "taskId": task_id,
            "replayStartedAfterEventId": "1-0",
            "live": True,
        }

        assert websocket.receive_json()["eventId"] == "2-0"
        assert websocket.receive_json()["eventId"] == "3-0"

        with pytest.raises(WebSocketDisconnect):
            websocket.receive_json()

    assert store.wait_calls[0]["after_event_id"] == "2-0"
    assert ("streams_active", 1, {"transport": "ws"}) in metrics.gauges


def test_websocket_route_closes_after_terminal_replay_event() -> None:
    client, _, task_id, _ = build_ws_client(
        replay_events=[make_event("task-ws-1", "2-0", TaskEventType.TASK_COMPLETED)]
    )

    with client.websocket_connect("/api/v1/tasks/ws") as websocket:
        websocket.send_json({"action": "subscribe", "taskId": task_id})
        websocket.receive_json()
        terminal_event = websocket.receive_json()
        assert terminal_event["type"] == TaskEventType.TASK_COMPLETED.value

        with pytest.raises(WebSocketDisconnect):
            websocket.receive_json()


def test_websocket_route_sends_heartbeat_control_frame_on_idle_wait() -> None:
    client, _, task_id, _ = build_ws_client(
        live_batches=[[], [make_event("task-ws-1", "3-0", TaskEventType.TASK_COMPLETED)]]
    )

    with client.websocket_connect("/api/v1/tasks/ws") as websocket:
        websocket.send_json({"action": "subscribe", "taskId": task_id})

        assert websocket.receive_json()["type"] == "SUBSCRIPTION_CONFIRMED"
        heartbeat = websocket.receive_json()
        assert heartbeat == {"type": "HEARTBEAT", "taskId": task_id, "live": True}
        assert websocket.receive_json()["eventId"] == "3-0"

        with pytest.raises(WebSocketDisconnect):
            websocket.receive_json()


def test_websocket_route_rejects_invalid_subscribe_frame() -> None:
    client, _, task_id, _ = build_ws_client()

    with client.websocket_connect("/api/v1/tasks/ws") as websocket:
        websocket.send_json({"action": "unsupported", "taskId": task_id})

        with pytest.raises(WebSocketDisconnect) as exc_info:
            websocket.receive_json()

    assert exc_info.value.code == 1008


def test_websocket_route_rejects_subscription_when_auth_fails() -> None:
    client, _, task_id, _ = build_ws_client(auth_resolver=RejectingAuthResolver())
    del task_id

    with pytest.raises(WebSocketDisconnect) as exc_info:
        with client.websocket_connect("/api/v1/tasks/ws"):
            pass

    assert exc_info.value.code == 1008


def test_websocket_route_closes_without_ack_for_foreign_task() -> None:
    client, _, task_id, _ = build_ws_client(owner_subject="user-owned-by-someone-else")

    with client.websocket_connect("/api/v1/tasks/ws") as websocket:
        websocket.send_json({"action": "subscribe", "taskId": task_id})

        with pytest.raises(WebSocketDisconnect) as exc_info:
            websocket.receive_json()

    assert exc_info.value.code == 1008
