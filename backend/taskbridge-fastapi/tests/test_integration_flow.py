from __future__ import annotations

import asyncio

from fastapi import FastAPI
from fastapi.testclient import TestClient

from taskbridge.dependencies import (
    get_auth_context_resolver,
    get_task_cancellation_service,
    get_task_creation_service,
    get_task_polling_service,
    get_websocket_subscription_service,
)
from taskbridge.fakes import DeterministicFakeExecutor
from taskbridge.models import AuthContext
from taskbridge.routes_http import build_http_router, install_http_exception_handlers
from taskbridge.routes_ws import WebSocketRouteSettings, build_ws_router
from taskbridge.services import (
    TaskCancellationService,
    TaskCreationService,
    TaskPollingService,
    WebSocketSubscriptionService,
)

from .test_interfaces import FakeEventStore, FakeOwnershipPolicy, FakeTaskRegistry


class IntegrationAuthResolver:
    async def resolve_auth_context(self, request_context: object) -> AuthContext:
        del request_context
        return AuthContext(subject="integration-user", scopes={"tasks:read", "tasks:write"})


def build_integration_app() -> TestClient:
    app = FastAPI()
    install_http_exception_handlers(app)
    app.include_router(build_http_router())
    app.include_router(build_ws_router(WebSocketRouteSettings(wait_timeout_ms=5)))

    registry = FakeTaskRegistry()
    store = FakeEventStore()
    executor = DeterministicFakeExecutor(
        registry=registry,
        event_store=store,
        progress_ticks=1,
        step_delay_seconds=0,
    )
    ownership = FakeOwnershipPolicy()

    creation = TaskCreationService(registry=registry, executor=executor, ownership_policy=ownership)
    polling = TaskPollingService(registry=registry, event_store=store, ownership_policy=ownership)
    cancellation = TaskCancellationService(
        registry=registry, executor=executor, ownership_policy=ownership
    )
    ws_sub = WebSocketSubscriptionService(
        registry=registry,
        event_store=store,
        ownership_policy=ownership,
    )

    app.dependency_overrides[get_auth_context_resolver] = lambda: IntegrationAuthResolver()
    app.dependency_overrides[get_task_creation_service] = lambda: creation
    app.dependency_overrides[get_task_polling_service] = lambda: polling
    app.dependency_overrides[get_task_cancellation_service] = lambda: cancellation
    app.dependency_overrides[get_websocket_subscription_service] = lambda: ws_sub
    return TestClient(app)


def test_start_to_terminal_over_http_and_ws() -> None:
    client = build_integration_app()

    create = client.post(
        "/api/v1/tasks",
        json={
            "clientRequestId": "integration-req-1",
            "taskType": "demo.echo",
            "input": {"hello": "world"},
        },
    )
    assert create.status_code == 201
    task_id = create.json()["taskId"]

    asyncio.run(asyncio.sleep(0.01))

    with client.websocket_connect("/api/v1/tasks/ws") as websocket:
        websocket.send_json({"action": "subscribe", "taskId": task_id})
        ack = websocket.receive_json()
        assert ack["type"] == "SUBSCRIPTION_CONFIRMED"
        assert ack["taskId"] == task_id

        received_types: list[str] = []
        while True:
            frame = websocket.receive_json()
            received_types.append(frame["type"])
            if frame["type"] in {"TASK_COMPLETED", "TASK_FAILED", "TASK_CANCELLED"}:
                break

    assert "TASK_STARTED" in received_types
    assert "TASK_COMPLETED" in received_types


def test_ws_disconnect_then_polling_replay_is_consistent() -> None:
    client = build_integration_app()

    create = client.post(
        "/api/v1/tasks",
        json={
            "clientRequestId": "integration-req-2",
            "taskType": "demo.echo",
            "input": {"hello": "fallback"},
        },
    )
    task_id = create.json()["taskId"]
    asyncio.run(asyncio.sleep(0.01))

    with client.websocket_connect("/api/v1/tasks/ws") as websocket:
        websocket.send_json({"action": "subscribe", "taskId": task_id})
        websocket.receive_json()  # SUBSCRIPTION_CONFIRMED
        first_event = websocket.receive_json()
        first_event_id = first_event["eventId"]

    poll = client.get(
        f"/api/v1/tasks/{task_id}/events",
        params={"afterEventId": first_event_id, "waitTimeoutMs": 10, "maxEvents": 10},
    )
    assert poll.status_code == 200
    payload = poll.json()
    ids = [event["eventId"] for event in payload["events"]]
    assert all(event_id > first_event_id for event_id in ids)
    if ids:
        assert payload["nextAfterEventId"] == ids[-1]
