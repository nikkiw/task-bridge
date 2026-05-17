"""FastAPI dependencies for TaskBridge.

This module defines common dependency providers and resolver functions
used in HTTP and WebSocket routes.
"""

from __future__ import annotations

from fastapi import Depends, Request, WebSocket
from starlette.websockets import WebSocketDisconnect

from .errors import AuthenticationError
from .models import AuthContext
from .observability import NoOpMetricsSink, NoOpTransportDiagnosticsSink
from .readiness import AlwaysReadyProbe
from .security import AllowAllUploadPolicy


def _missing_override(name: str) -> RuntimeError:
    return RuntimeError(f"{name} dependency must be overrideable by the host application")


def get_task_creation_service():
    """Get the task creation service."""
    raise _missing_override("task creation service")


def get_task_polling_service():
    """Get the task polling service."""
    raise _missing_override("task polling service")


def get_task_cancellation_service():
    """Get the task cancellation service."""
    raise _missing_override("task cancellation service")


def get_task_action_service():
    """Get the task action service."""
    raise _missing_override("task action service")


def get_websocket_subscription_service():
    """Get the websocket subscription service."""
    raise _missing_override("websocket subscription service")


def get_auth_context_resolver():
    """Get the auth context resolver."""
    raise _missing_override("auth context resolver")


def get_ownership_policy():
    """Provide ownership policy for host apps that prefer DI over manual wiring."""
    raise _missing_override("ownership policy")


def get_upload_policy():
    """Get the upload policy."""
    return AllowAllUploadPolicy()


def get_metrics_sink():
    """Get the metrics sink."""
    return NoOpMetricsSink()


def get_transport_diagnostics_sink():
    """Get the transport diagnostics sink."""
    return NoOpTransportDiagnosticsSink()


def get_readiness_probe():
    """Get the readiness probe."""
    return AlwaysReadyProbe()


AUTH_CONTEXT_RESOLVER = Depends(get_auth_context_resolver)


async def resolve_http_auth_context(
    request: Request,
    resolver=AUTH_CONTEXT_RESOLVER,
) -> AuthContext:
    """Resolve authentication context for HTTP request."""
    return await resolver.resolve_auth_context(request)


async def resolve_ws_auth_context(
    websocket: WebSocket,
    resolver=AUTH_CONTEXT_RESOLVER,
) -> AuthContext:
    """Resolve authentication context for WebSocket connection."""
    try:
        return await resolver.resolve_auth_context(websocket)
    except AuthenticationError as exc:
        await websocket.accept()
        await websocket.close(code=1008)
        raise WebSocketDisconnect(code=1008) from exc
