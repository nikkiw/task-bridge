from __future__ import annotations

from fastapi.routing import APIRoute, APIWebSocketRoute

from taskbridge.dependencies import (
    get_auth_context_resolver,
    get_ownership_policy,
    get_task_cancellation_service,
    get_task_creation_service,
    get_task_polling_service,
    get_websocket_subscription_service,
)
from taskbridge.routes_http import build_http_router
from taskbridge.routes_ws import build_ws_router


def test_http_router_exposes_expected_paths() -> None:
    router = build_http_router()
    routes = {
        (route.path, tuple(sorted(route.methods)))
        for route in router.routes
        if isinstance(route, APIRoute)
    }

    assert ("/api/v1/tasks", ("POST",)) in routes
    assert ("/api/v1/tasks/{taskId}/events", ("GET",)) in routes
    assert ("/api/v1/tasks/{taskId}/cancel", ("POST",)) in routes
    assert ("/health", ("GET",)) in routes
    assert ("/ready", ("GET",)) in routes

    route_by_path = {route.path: route for route in router.routes if isinstance(route, APIRoute)}
    for path in (
        "/api/v1/tasks",
        "/api/v1/tasks/{taskId}/events",
        "/api/v1/tasks/{taskId}/cancel",
    ):
        dependency_names = {
            dependency.call.__name__
            for dependency in route_by_path[path].dependant.dependencies
            if hasattr(dependency.call, "__name__")
        }
        assert "resolve_http_auth_context" in dependency_names


def test_websocket_router_exposes_expected_path() -> None:
    router = build_ws_router()
    route_map = {
        route.path: route for route in router.routes if isinstance(route, APIWebSocketRoute)
    }

    assert "/api/v1/tasks/ws" in route_map
    dependency_names = {
        dependency.call.__name__
        for dependency in route_map["/api/v1/tasks/ws"].dependant.dependencies
        if hasattr(dependency.call, "__name__")
    }
    assert "resolve_ws_auth_context" in dependency_names


def test_default_dependency_providers_fail_loudly() -> None:
    providers = [
        get_task_creation_service,
        get_task_polling_service,
        get_task_cancellation_service,
        get_websocket_subscription_service,
        get_auth_context_resolver,
        get_ownership_policy,
    ]

    for provider in providers:
        try:
            provider()
        except RuntimeError as exc:
            assert "override" in str(exc).lower()
        else:
            raise AssertionError(f"{provider.__name__} should require override")
