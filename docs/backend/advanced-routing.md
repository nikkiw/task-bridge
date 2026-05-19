# Advanced Routing & Fallbacks

This guide covers the real advanced-routing use case: a host application wants custom public URLs and host-specific auth/context handling, but still wants TaskBridge to own replay, streaming, and fallback semantics.

This page is intentionally about **custom routing**, not about replacing TaskBridge transport logic.

## When you need this

Use advanced routing when your host needs one or more of these:

- tenant or project identifiers in paths such as `/projects/{project_id}/tasks`;
- custom OpenAPI-facing route layout;
- request-context extraction that depends on host path params or headers;
- a public URL scheme that differs from the default TaskBridge paths.

Do not use advanced routing as a reason to reimplement SSE loops, replay handling, or WebSocket subscription flow from scratch.

## Two customization levels

There are two valid approaches.

### 1. Recommended: customize route settings

This is the preferred path when you want different HTTP and SSE paths without changing TaskBridge internals.

Relevant settings:

- `HttpRouteSettings`
- `WebSocketRouteSettings`
- `StreamRuntimeSettings`

This keeps TaskBridge-owned parsing, replay, and stream runtime behavior intact.

### 2. Manual route assembly

Use this only when route settings are not enough and the host needs handwritten route functions.

Even in that case, the goal should still be:

- reuse TaskBridge services and helpers;
- keep auth and host context in the host layer;
- avoid reimplementing replay and heartbeat mechanics.

## Important fallback reality

For the Android SDK to degrade fully under poor network conditions, the backend must expose three transport capabilities for the same logical task stream:

- WebSocket subscription
- SSE stream
- polling

The logical fallback order on the client is still:

- WebSocket
- SSE
- polling

The public URL shapes do **not** have to match the default paths, but the client and backend must agree on them.

## Auth context with path-aware routing

Path-aware auth extraction is a valid reason for custom routing.

Realistic host example:

```python
from fastapi import APIRouter, Header, HTTPException

from taskbridge.models import AuthContext

router = APIRouter()

async def get_project_auth_context(
    project_id: str,
    authorization: str | None = Header(default=None),
) -> AuthContext:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Missing or invalid token")

    token = authorization.split(" ", 1)[1]
    user_id = "extracted_user_id"

    return AuthContext(
        subject=user_id,
        scopes={"tasks:write", "tasks:read"},
        attributes={"project_id": project_id, "token_hint": token[:8]},
    )
```

The important boundary is that the host extracts request-specific context and TaskBridge receives normalized `AuthContext`.

## Recommended path customization with route settings

When your main goal is custom HTTP and SSE paths, prefer route settings over copied route implementations.

```python
from fastapi import FastAPI

from taskbridge import HttpRouteSettings, build_http_router, build_ws_router

http_settings = HttpRouteSettings(
    create_task_path="/api/v1/projects/{project_id}/tasks",
    poll_events_path="/api/v1/projects/{project_id}/tasks/{taskId}/events",
    stream_events_path="/api/v1/projects/{project_id}/tasks/{taskId}/events/stream",
    cancel_task_path="/api/v1/projects/{project_id}/tasks/{taskId}/cancel",
    submit_action_path="/api/v1/projects/{project_id}/tasks/{taskId}/actions",
)

app = FastAPI()
app.include_router(build_http_router(http_settings))
app.include_router(build_ws_router())
```

This is the cleanest solution if the only customization you need is path layout for HTTP-family routes.

## Manual task creation route

If you truly need a handwritten create route, this is the current service-level shape:

```python
from fastapi import Depends

from taskbridge import HttpTaskCreateRequest, TaskCreateCommand, TaskCreationService, get_task_creation_service

@router.post("/api/v1/projects/{project_id}/tasks")
async def create_task(
    project_id: str,
    request: HttpTaskCreateRequest,
    auth: AuthContext = Depends(get_project_auth_context),
    service: TaskCreationService = Depends(get_task_creation_service),
):
    command = TaskCreateCommand(
        client_request_id=request.client_request_id,
        task_type=request.task_type,
        input_payload=request.input_payload,
        metadata=request.metadata,
        auth_context=auth,
    )
    return await service.create_task(command)
```

This is valid because it still delegates creation semantics to `TaskCreationService`.

## Manual polling route

Manual polling is still a supported integration style when needed, but use the current helper signature:

```python
from fastapi import Depends

from taskbridge import TaskPollingService, get_metrics_sink, get_task_polling_service
from taskbridge.handlers import handle_http_polling

@router.get("/api/v1/projects/{project_id}/tasks/{task_id}/events")
async def task_events_polling(
    project_id: str,
    task_id: str,
    after_event_id: str | None = None,
    limit: int = 50,
    wait_timeout_ms: int = 20_000,
    auth: AuthContext = Depends(get_project_auth_context),
    polling_service: TaskPollingService = Depends(get_task_polling_service),
    metrics_sink = Depends(get_metrics_sink),
):
    return await handle_http_polling(
        task_id=task_id,
        polling_service=polling_service,
        auth_context=auth,
        metrics_sink=metrics_sink,
        after_event_id=after_event_id,
        limit=limit,
        wait_timeout_ms=wait_timeout_ms,
    )
```

## Manual SSE route

SSE can also be assembled manually with the current runtime helper API:

```python
from fastapi import Depends, Header, Request

from taskbridge import build_sse_stream_response, get_task_polling_service, get_transport_diagnostics_sink, sse_event_generator

@router.get("/api/v1/projects/{project_id}/tasks/{task_id}/events/stream")
async def task_events_sse(
    project_id: str,
    task_id: str,
    request: Request,
    last_event_id: str | None = Header(default=None, alias="Last-Event-ID"),
    auth: AuthContext = Depends(get_project_auth_context),
    service = Depends(get_task_polling_service),
    diagnostics_sink = Depends(get_transport_diagnostics_sink),
):
    settings = HttpRouteSettings().stream_runtime.sse

    event_iterator = sse_event_generator(
        request=request,
        task_id=task_id,
        last_event_id=last_event_id,
        auth_context=auth,
        service=service,
        settings=settings,
        diagnostics=diagnostics_sink,
    )

    return build_sse_stream_response(
        event_iterator,
        settings,
    )
```

The critical point here is that the helper API is built around `last_event_id`, `service`, and `diagnostics`, not the older `after_event_id` / `metrics` shape.

## WebSocket custom routing caveat

WebSocket customization is the one place where you need to be more careful.

Current TaskBridge WebSocket behavior is based on:

- a single socket endpoint;
- a subscribe frame with `action="subscribe"`;
- `taskId` and optional `lastEventId` in that frame.

That contract is defined by `WebSocketSubscribeRequest`, not by a path parameter alone.

So if you expose a custom WebSocket path such as `/projects/{project_id}/tasks/ws`, that is fine, but the client still needs to send the expected subscribe frame unless you fully replace the TaskBridge WebSocket flow.

## Manual WebSocket route

If you handwrite a WebSocket route, use the current helper signature:

```python
from fastapi import Depends, WebSocket

from taskbridge import get_metrics_sink, get_websocket_subscription_service
from taskbridge.handlers import handle_websocket_subscription

@router.websocket("/api/v1/projects/{project_id}/tasks/ws")
async def task_events_ws(
    project_id: str,
    websocket: WebSocket,
    auth: AuthContext = Depends(get_project_auth_context),
    ws_service = Depends(get_websocket_subscription_service),
    metrics_sink = Depends(get_metrics_sink),
):
    await handle_websocket_subscription(
        websocket=websocket,
        ws_service=ws_service,
        auth_context=auth,
        metrics_sink=metrics_sink,
    )
```

Important limitation:

- this does **not** move task selection into the path by itself;
- the subscribe frame still carries `taskId`;
- if you want the Android SDK to use this custom WS path, you must align the client route resolver with the backend path scheme.

## Recommended production guidance

- prefer `HttpRouteSettings` first;
- keep `AuthContext` extraction in host code;
- reuse `TaskCreationService`, `handle_http_polling`, `sse_event_generator`, and `handle_websocket_subscription` when handwriting routes;
- preserve the WebSocket subscribe-frame contract unless you intentionally fork the protocol behavior;
- document any custom path scheme together with the matching Android `TaskBridgeRouteResolver`.

## Summary

Advanced routing is a valid and useful guide topic. The safe way to think about it is:

- custom paths are supported;
- host-specific auth extraction is supported;
- manual route assembly is supported;
- replay, SSE, and WebSocket subscription semantics should still stay TaskBridge-owned unless you intentionally replace them.

## Related docs

- [Host Integration](host-integration.md)
- [Services and Routes](services-and-routes.md)
- [Security, Readiness, and Observability](security-readiness-observability.md)
- [Android Client and Config](../android/client-config.md)
