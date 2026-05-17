from __future__ import annotations

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from taskbridge.dependencies import (
    get_auth_context_resolver,
    get_readiness_probe,
    get_task_action_service,
    get_task_cancellation_service,
    get_task_creation_service,
    get_task_polling_service,
    get_websocket_subscription_service,
)
from taskbridge.routes_http import build_http_router, install_http_exception_handlers
from taskbridge.routes_ws import build_ws_router

from .wiring import DemoServices, build_demo_services


def create_app(services: DemoServices | None = None) -> FastAPI:
    """Build FastAPI app with TaskBridge routers and demo dependency overrides."""
    svc = services or build_demo_services()

    app = FastAPI(
        title="TaskBridge FastAPI host example",
        version="0.1.0",
    )
    install_http_exception_handlers(app)
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    app.include_router(build_http_router())
    app.include_router(build_ws_router())

    app.dependency_overrides[get_auth_context_resolver] = lambda: svc.auth_resolver
    app.dependency_overrides[get_task_creation_service] = lambda: svc.creation
    app.dependency_overrides[get_task_polling_service] = lambda: svc.polling
    app.dependency_overrides[get_task_cancellation_service] = lambda: svc.cancellation
    app.dependency_overrides[get_websocket_subscription_service] = lambda: (
        svc.websocket_subscription
    )
    app.dependency_overrides[get_task_action_service] = lambda: svc.action
    app.dependency_overrides[get_readiness_probe] = lambda: svc.readiness_probe

    return app


app = create_app()
