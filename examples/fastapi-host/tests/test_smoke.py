from __future__ import annotations

from fastapi.testclient import TestClient

from app.main import create_app
from app.wiring import build_demo_services


def test_health_ok(monkeypatch) -> None:
    monkeypatch.setenv("TB_EXECUTOR_MODE", "fake")
    app = create_app(build_demo_services())
    client = TestClient(app)
    assert client.get("/health").json() == {"status": "ok"}


def test_create_task_returns_201(monkeypatch) -> None:
    monkeypatch.setenv("TB_EXECUTOR_MODE", "fake")
    app = create_app(build_demo_services())
    client = TestClient(app)
    response = client.post(
        "/api/v1/tasks",
        json={
            "clientRequestId": "smoke-req-1",
            "taskType": "demo.success",
            "input": {"hello": "world"},
        },
    )
    assert response.status_code == 201
    body = response.json()
    assert "taskId" in body
    assert body["deduplicated"] is False
