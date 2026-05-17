import asyncio
from datetime import datetime, timezone

from fastapi import FastAPI
from fastapi.testclient import TestClient

from taskbridge.dependencies import get_auth_context_resolver, get_websocket_subscription_service
from taskbridge.models import AuthContext, PollEventsResult, TaskEvent, TaskEventType
from taskbridge.observability import TransportDiagnosticsSink
from taskbridge.routes_http import build_http_router
from taskbridge.stream_settings import StreamRuntimeSettings


class _FakeAuthResolver:
    async def resolve_auth_context(self, request_context: object) -> AuthContext:
        del request_context
        return AuthContext(subject="user-1", scopes={"tasks:read", "tasks:write"})


class _FakeSubscriptionService:
    def __init__(self, *, replay_events=None, live_events=None, idle_once: bool = False):
        self.replay_events = list(replay_events or [])
        self.live_events = list(live_events or [])
        self.idle_once = idle_once
        self.wait_calls = 0

    async def prepare_subscription(self, **kwargs):
        return PollEventsResult(
            taskId=kwargs["task_id"],
            events=list(self.replay_events),
            nextAfterEventId=self.replay_events[-1].event_id if self.replay_events else None,
            hasMore=False,
        )

    async def wait_for_live_events(self, **kwargs):
        del kwargs
        self.wait_calls += 1
        if self.idle_once and self.wait_calls == 1:
            return []
        if self.live_events:
            events = list(self.live_events)
            self.live_events.clear()
            return events
        raise asyncio.CancelledError()


class _RecordingDiagnosticsSink(TransportDiagnosticsSink):
    def __init__(self) -> None:
        self.messages: list[str] = []

    def on_replay_start(self, transport: str, task_id: str, after_event_id: str | None) -> None:
        self.messages.append(f"{transport}.replay.{task_id}.{after_event_id}")

    def on_heartbeat(self, transport: str, task_id: str) -> None:
        self.messages.append(f"{transport}.heartbeat.{task_id}")

    def on_delivery(self, transport: str, task_id: str, event_id: str) -> None:
        self.messages.append(f"{transport}.delivery.{task_id}.{event_id}")


def _client_factory(*, replay_events=None, live_events=None, idle_once: bool = False) -> TestClient:
    app = FastAPI()
    app.include_router(build_http_router())
    app.dependency_overrides[get_auth_context_resolver] = lambda: _FakeAuthResolver()
    app.dependency_overrides[get_websocket_subscription_service] = lambda: _FakeSubscriptionService(
        replay_events=replay_events,
        live_events=live_events,
        idle_once=idle_once,
    )
    return TestClient(app)


def _client_factory_with_diagnostics(
    *,
    replay_events=None,
    live_events=None,
    idle_once: bool = False,
    diagnostics_sink: _RecordingDiagnosticsSink,
) -> TestClient:
    app = FastAPI()
    from taskbridge.dependencies import get_transport_diagnostics_sink

    app.include_router(build_http_router())
    app.dependency_overrides[get_auth_context_resolver] = lambda: _FakeAuthResolver()
    app.dependency_overrides[get_websocket_subscription_service] = lambda: _FakeSubscriptionService(
        replay_events=replay_events,
        live_events=live_events,
        idle_once=idle_once,
    )
    app.dependency_overrides[get_transport_diagnostics_sink] = lambda: diagnostics_sink
    return TestClient(app)


def test_stream_runtime_settings_have_transport_specific_defaults() -> None:
    settings = StreamRuntimeSettings()

    assert settings.sse.wait_timeout_ms > 0
    assert settings.sse.emit_comment_ping is True
    assert settings.sse.emit_json_heartbeat is True
    assert settings.websocket.wait_timeout_ms > 0
    assert settings.websocket.terminal_close_code == 1000


def test_ws_and_sse_share_transport_defaults() -> None:
    settings = StreamRuntimeSettings()

    assert settings.websocket.replay_batch_size == settings.sse.replay_batch_size
    assert settings.websocket.live_batch_size == settings.sse.live_batch_size


def test_sse_runtime_replays_then_streams_live_events() -> None:
    event = TaskEvent(
        taskId="task-1",
        eventId="1-0",
        type=TaskEventType.TASK_STARTED,
        createdAt=datetime.now(timezone.utc),
        payload={},
    )
    client = _client_factory(replay_events=[event], live_events=[])

    with client.stream("GET", "/api/v1/tasks/task-1/events/stream") as response:
        body = list(response.iter_lines())

    assert any(line.startswith("id: 1-0") for line in body)
    assert any("TASK_STARTED" in line for line in body if line.startswith("data:"))


def test_sse_runtime_emits_diagnostics_on_idle_and_delivery() -> None:
    diagnostics_sink = _RecordingDiagnosticsSink()
    event = TaskEvent(
        taskId="task-1",
        eventId="1-0",
        type=TaskEventType.TASK_STARTED,
        createdAt=datetime.now(timezone.utc),
        payload={},
    )
    client = _client_factory_with_diagnostics(
        replay_events=[],
        live_events=[event],
        idle_once=True,
        diagnostics_sink=diagnostics_sink,
    )

    with client.stream("GET", "/api/v1/tasks/task-1/events/stream") as response:
        _ = list(response.iter_lines())

    assert any(msg.startswith("sse.replay.task-1") for msg in diagnostics_sink.messages)
    assert "sse.heartbeat.task-1" in diagnostics_sink.messages
    assert "sse.delivery.task-1.1-0" in diagnostics_sink.messages
