from __future__ import annotations

from datetime import datetime, timezone

from taskbridge.dependencies import (
    get_websocket_subscription_service,
)
from taskbridge.models import PollEventsResult, TaskEvent, TaskEventType

from .test_http_api import build_app as original_build_app


class FakeWebSocketSubscriptionService:
    def __init__(self, *, replay_result=None, live_events=None):
        self.replay_result = replay_result or PollEventsResult(
            taskId="task-1", events=[], nextAfterEventId=None, hasMore=False
        )
        self.live_events = live_events or []
        self.prepare_calls = []
        self.wait_calls = []

    async def prepare_subscription(self, **kwargs):
        self.prepare_calls.append(kwargs)
        return self.replay_result

    async def wait_for_live_events(self, **kwargs):
        import asyncio

        self.wait_calls.append(kwargs)
        if len(self.wait_calls) > 1:
            # Force the generator to exit in tests to prevent hanging TestClient
            raise asyncio.CancelledError()

        # Return events once, then empty to avoid infinite data but keep connection open
        res = self.live_events
        self.live_events = []
        return res


def build_app(*, subscription_service: FakeWebSocketSubscriptionService | None = None, **kwargs):
    client, create, poll, cancel, action, metrics = original_build_app(**kwargs)
    sub_service = subscription_service or FakeWebSocketSubscriptionService()
    client.app.dependency_overrides[get_websocket_subscription_service] = lambda: sub_service
    return client, create, poll, cancel, action, metrics, sub_service


def test_sse_endpoint_exists_and_returns_event_stream() -> None:
    client, _, _, _, _, _, _ = build_app()

    with client.stream("GET", "/api/v1/tasks/task-1/events/stream") as response:
        assert response.status_code == 200
        assert "text/event-stream" in response.headers["content-type"]


def test_sse_endpoint_requires_auth() -> None:
    from .test_http_api import RejectingAuthResolver

    client, _, _, _, _, _, _ = build_app(auth_resolver=RejectingAuthResolver())

    response = client.get("/api/v1/tasks/task-1/events/stream")
    assert response.status_code == 401


def test_sse_endpoint_streams_events() -> None:
    event = TaskEvent(
        taskId="task-1",
        eventId="1-0",
        type=TaskEventType.TASK_STARTED,
        createdAt=datetime.now(timezone.utc),
        payload={},
    )

    sub_service = FakeWebSocketSubscriptionService(live_events=[event])
    client, _, _, _, _, _, _ = build_app(subscription_service=sub_service)

    with client.stream("GET", "/api/v1/tasks/task-1/events/stream") as response:
        lines = list(response.iter_lines())
        print(f"DEBUG SSE LINES: {lines}")

        found_id = False
        found_data = False
        for line in lines:
            if line.startswith("id: 1-0"):
                found_id = True
            if line.startswith("data:") and "TASK_STARTED" in line:
                found_data = True
            if found_id and found_data:
                break

        assert found_id
        assert found_data
