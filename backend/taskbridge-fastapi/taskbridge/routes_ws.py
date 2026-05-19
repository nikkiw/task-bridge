"""WebSocket routes and infrastructure for TaskBridge.

This module provides the APIRouter for TaskBridge WebSocket endpoints,
enabling real-time task event streaming.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Annotated

from fastapi import APIRouter, Depends, WebSocket
from pydantic import ValidationError
from starlette.websockets import WebSocketDisconnect

from .dependencies import (
    get_metrics_sink,
    get_websocket_subscription_service,
    resolve_ws_auth_context,
)
from .errors import AuthenticationError, TaskBridgeError
from .models import (
    AuthContext,
    TaskEvent,
    TaskEventType,
    WebSocketHeartbeat,
    WebSocketSubscribeRequest,
    WebSocketSubscriptionConfirmed,
)
from .observability import MetricsSink, log_structured
from .stream_settings import StreamRuntimeSettings

logger = logging.getLogger(__name__)

WEBSOCKET_SUBSCRIPTION_SERVICE = Depends(get_websocket_subscription_service)
METRICS_SINK = Depends(get_metrics_sink)


@dataclass(slots=True)
class WebSocketRouteSettings:
    """Settings for WebSocket routes."""

    websocket_path: str = "/api/v1/tasks/ws"
    replay_batch_size: int = 100
    live_batch_size: int = 100
    wait_timeout_ms: int = 25_000
    invalid_frame_close_code: int = 1008
    terminal_close_code: int = 1000
    stream_runtime: StreamRuntimeSettings = field(default_factory=StreamRuntimeSettings)


def build_ws_router(settings: WebSocketRouteSettings | None = None) -> APIRouter:
    """Build and return an APIRouter with TaskBridge WebSocket routes.

    Args:
        settings: Optional WebSocket route settings.

    Returns:
        APIRouter configured with TaskBridge WebSocket routes.

    """
    actual_settings = settings or WebSocketRouteSettings()
    ws_settings = actual_settings.stream_runtime.websocket
    router = APIRouter()

    @router.websocket(actual_settings.websocket_path)
    async def task_events_ws(
        websocket: WebSocket,
        auth_context: Annotated[AuthContext, Depends(resolve_ws_auth_context)],
        service=WEBSOCKET_SUBSCRIPTION_SERVICE,
        metrics_sink: MetricsSink = METRICS_SINK,
    ) -> None:
        await websocket.accept()
        try:
            subscribe_request = await _receive_subscribe_request(
                websocket,
                invalid_frame_close_code=ws_settings.invalid_frame_close_code,
            )
            replay = await service.prepare_subscription(
                task_id=subscribe_request.task_id,
                auth_context=auth_context,
                last_event_id=subscribe_request.last_event_id,
                limit=ws_settings.replay_batch_size,
            )
            await websocket.send_json(
                WebSocketSubscriptionConfirmed(
                    type="SUBSCRIPTION_CONFIRMED",
                    taskId=subscribe_request.task_id,
                    replayStartedAfterEventId=subscribe_request.last_event_id,
                    live=True,
                ).model_dump(mode="json", by_alias=True)
            )
            metrics_sink.set_gauge("streams_active", 1, tags={"transport": "ws"})
            if subscribe_request.last_event_id is not None:
                metrics_sink.increment_counter("replays_total", tags={"transport": "ws"})
            log_structured(
                logger,
                level=logging.INFO,
                message="ws.subscription.confirmed",
                task_id=subscribe_request.task_id,
                event_id=subscribe_request.last_event_id,
                user_id=auth_context.subject,
                transport="ws",
                outcome="confirmed",
            )
            after_event_id = replay.next_after_event_id
            should_close = await _send_events_and_check_terminal(websocket, replay.events)
            if should_close:
                await websocket.close(code=ws_settings.terminal_close_code)
                return

            while True:
                live_events = await service.wait_for_live_events(
                    task_id=subscribe_request.task_id,
                    after_event_id=after_event_id,
                    limit=ws_settings.live_batch_size,
                    timeout_ms=ws_settings.wait_timeout_ms,
                )
                if not live_events:
                    metrics_sink.increment_counter("fallbacks_total", tags={"transport": "ws"})
                    if ws_settings.emit_heartbeat_frame:
                        await websocket.send_json(
                            WebSocketHeartbeat(
                                type="HEARTBEAT",
                                taskId=subscribe_request.task_id,
                                live=True,
                            ).model_dump(mode="json", by_alias=True)
                        )
                    log_structured(
                        logger,
                        level=logging.DEBUG,
                        message="ws.heartbeat.sent",
                        task_id=subscribe_request.task_id,
                        user_id=auth_context.subject,
                        transport="ws",
                        outcome="idle",
                    )
                    continue
                after_event_id = live_events[-1].event_id
                should_close = await _send_events_and_check_terminal(websocket, live_events)
                if should_close:
                    await websocket.close(code=ws_settings.terminal_close_code)
                    return
        except WebSocketDisconnect:
            return
        except (AuthenticationError, TaskBridgeError) as exc:
            logger.warning("WS closed due to %s: %s", type(exc).__name__, exc)
            await websocket.close(code=ws_settings.invalid_frame_close_code)
            return
        finally:
            metrics_sink.set_gauge("streams_active", 0, tags={"transport": "ws"})

    return router


async def _receive_subscribe_request(
    websocket: WebSocket,
    invalid_frame_close_code: int,
) -> WebSocketSubscribeRequest:
    try:
        payload = await websocket.receive_json()
        request = WebSocketSubscribeRequest.model_validate(payload)
    except (ValidationError, ValueError, TypeError) as exc:
        await websocket.close(code=invalid_frame_close_code)
        raise WebSocketDisconnect(code=invalid_frame_close_code) from exc
    if request.action != "subscribe":
        await websocket.close(code=invalid_frame_close_code)
        raise WebSocketDisconnect(code=invalid_frame_close_code)
    return request


async def _send_events_and_check_terminal(
    websocket: WebSocket,
    events: list[TaskEvent],
) -> bool:
    terminal_seen = False
    for event in events:
        await websocket.send_json(event.model_dump(mode="json", by_alias=True))
        terminal_seen = terminal_seen or event.type in (
            TaskEventType.TASK_COMPLETED,
            TaskEventType.TASK_FAILED,
            TaskEventType.TASK_CANCELLED,
        )
    return terminal_seen
