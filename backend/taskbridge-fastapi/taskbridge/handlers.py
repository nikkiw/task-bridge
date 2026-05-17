"""Unified handlers for HTTP and WebSocket transports.

This module provides high-level logic for processing task polling requests
and maintaining WebSocket streaming sessions.
"""

import logging

from fastapi import WebSocket
from pydantic import ValidationError
from starlette.websockets import WebSocketDisconnect

from .errors import AuthenticationError, TaskBridgeError
from .models import (
    AuthContext,
    PollEventsResult,
    TaskEvent,
    TaskEventType,
    WebSocketHeartbeat,
    WebSocketSubscribeRequest,
    WebSocketSubscriptionConfirmed,
)
from .observability import (
    MetricsSink,
    NoOpTransportDiagnosticsSink,
    TransportDiagnosticsSink,
    log_structured,
)
from .services import TaskPollingService, WebSocketSubscriptionService

logger = logging.getLogger(__name__)


async def handle_http_polling(
    task_id: str,
    polling_service: TaskPollingService,
    auth_context: AuthContext,
    metrics_sink: MetricsSink,
    after_event_id: str | None = None,
    limit: int = 100,
    wait_timeout_ms: int = 0,
) -> PollEventsResult:
    """Handle long-polling request for task events.

    Args:
        task_id: The ID of the task to poll.
        polling_service: Service to fetch events.
        auth_context: Authenticated user context.
        metrics_sink: Sink for operational metrics.
        after_event_id: Fetch events occurring after this ID.
        limit: Max events to return.
        wait_timeout_ms: How long to block if no events are available.

    Returns:
        PollEventsResult containing the events and next checkpoint.

    """
    result = await polling_service.poll_events(
        task_id=task_id,
        auth_context=auth_context,
        after_event_id=after_event_id,
        limit=limit,
        wait_timeout_ms=wait_timeout_ms,
    )
    metrics_sink.increment_counter("fallbacks_total")
    log_structured(
        logger,
        level=logging.INFO,
        message="http.task.poll",
        task_id=task_id,
        event_id=result.next_after_event_id,
        user_id=auth_context.subject,
        transport="http",
        outcome="ok",
        details={"batchSize": len(result.events), "afterEventId": after_event_id},
    )
    return result


async def handle_websocket_subscription(
    websocket: WebSocket,
    ws_service: WebSocketSubscriptionService,
    auth_context: AuthContext,
    metrics_sink: MetricsSink,
    replay_batch_size: int = 100,
    live_batch_size: int = 100,
    wait_timeout_ms: int = 25_000,
    invalid_frame_close_code: int = 1008,
    terminal_close_code: int = 1000,
    diagnostics_sink: TransportDiagnosticsSink | None = None,
) -> None:
    """Handle a WebSocket connection for task event streaming.

    Args:
        websocket: The WebSocket connection.
        ws_service: Service to manage subscriptions.
        auth_context: Authenticated user context.
        metrics_sink: Sink for operational metrics.
        replay_batch_size: Max events to send during initial replay.
        live_batch_size: Max events to send in each live update.
        wait_timeout_ms: Heartbeat interval when idle.
        invalid_frame_close_code: WS close code for invalid protocol frames.
        terminal_close_code: WS close code when task reaches terminal state.
        diagnostics_sink: Optional sink for transport diagnostics.

    """
    actual_diagnostics = diagnostics_sink or NoOpTransportDiagnosticsSink()
    await websocket.accept()
    try:
        # 1. Receive and validate subscribe request
        try:
            payload = await websocket.receive_json()
            subscribe_request = WebSocketSubscribeRequest.model_validate(payload)
        except (ValidationError, ValueError, TypeError):
            await websocket.close(code=invalid_frame_close_code)
            return

        if subscribe_request.action != "subscribe":
            await websocket.close(code=invalid_frame_close_code)
            return

        # 2. Prepare subscription (replay)
        # Note: Ownership is validated inside prepare_subscription via assert_task_access
        replay = await ws_service.prepare_subscription(
            task_id=subscribe_request.task_id,
            auth_context=auth_context,
            last_event_id=subscribe_request.last_event_id,
            limit=replay_batch_size,
        )
        actual_diagnostics.on_replay_start(
            "ws",
            subscribe_request.task_id,
            subscribe_request.last_event_id,
        )

        # 4. Confirm subscription
        await websocket.send_json(
            WebSocketSubscriptionConfirmed(
                type="SUBSCRIPTION_CONFIRMED",
                taskId=subscribe_request.task_id,
                replayStartedAfterEventId=subscribe_request.last_event_id,
                live=True,
            ).model_dump(mode="json", by_alias=True)
        )

        metrics_sink.set_gauge("streams_active", 1, tags={"transport": "ws"})

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

        # 5. Send replay events
        after_event_id = replay.next_after_event_id
        should_close = await _send_events_and_check_terminal(websocket, replay.events)
        if should_close:
            await websocket.close(code=terminal_close_code)
            return

        # 6. Live events loop
        while True:
            live_events = await ws_service.wait_for_live_events(
                task_id=subscribe_request.task_id,
                after_event_id=after_event_id,
                limit=live_batch_size,
                timeout_ms=wait_timeout_ms,
            )

            if not live_events:
                await websocket.send_json(
                    WebSocketHeartbeat(
                        type="HEARTBEAT",
                        taskId=subscribe_request.task_id,
                        live=True,
                    ).model_dump(mode="json", by_alias=True)
                )
                actual_diagnostics.on_heartbeat("ws", subscribe_request.task_id)
                continue

            after_event_id = live_events[-1].event_id
            should_close = await _send_events_and_check_terminal(websocket, live_events)
            for event in live_events:
                actual_diagnostics.on_delivery("ws", subscribe_request.task_id, event.event_id)
            if should_close:
                await websocket.close(code=terminal_close_code)
                return

    except WebSocketDisconnect:
        actual_diagnostics.on_disconnect("ws", "<unknown>")
        return
    except (AuthenticationError, TaskBridgeError) as exc:
        logger.warning("WS closed due to %s: %s", type(exc).__name__, exc)
        await websocket.close(code=invalid_frame_close_code)
        return
    finally:
        metrics_sink.set_gauge("streams_active", 0, tags={"transport": "ws"})


async def _send_events_and_check_terminal(
    websocket: WebSocket,
    events: list[TaskEvent],
) -> bool:
    """Send events via WebSocket and check if any are terminal."""
    terminal_seen = False
    for event in events:
        await websocket.send_json(event.model_dump(mode="json", by_alias=True))
        terminal_seen = terminal_seen or event.type in (
            TaskEventType.TASK_COMPLETED,
            TaskEventType.TASK_FAILED,
            TaskEventType.TASK_CANCELLED,
        )
    return terminal_seen
