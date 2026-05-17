"""Server-Sent Events (SSE) streaming runtime implementation."""

from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator
from datetime import datetime, timezone
from typing import TYPE_CHECKING
from uuid import uuid4

from fastapi import Request
from fastapi.responses import StreamingResponse

from .observability import NoOpTransportDiagnosticsSink

if TYPE_CHECKING:
    from .models import AuthContext
    from .observability import TransportDiagnosticsSink
    from .schemas import PollEventsResult
    from .services import WebSocketSubscriptionService
    from .stream_settings import SseStreamSettings


async def sse_event_generator(
    *,
    request: Request,
    task_id: str,
    last_event_id: str | None,
    auth_context: AuthContext,
    service: WebSocketSubscriptionService,
    settings: SseStreamSettings,
    prepared_replay: PollEventsResult | None = None,
    diagnostics: TransportDiagnosticsSink | None = None,
) -> AsyncIterator[str]:
    """Generate a sequence of formatted SSE messages.

    Handles initial replay of missed events followed by a live subscription loop.

    Args:
        request: The incoming FastAPI request (used to detect disconnection).
        task_id: Unique task identifier.
        last_event_id: Resume marker provided by the client.
        auth_context: Authenticated user context.
        service: Service instance providing event retrieval logic.
        settings: Configuration for the SSE stream.
        prepared_replay: Pre-fetched events to avoid redundant DB reads.
        diagnostics: Sink for transport-level metrics and tracing.

    Yields:
        Formatted SSE messages as strings.

    """
    actual_diagnostics = diagnostics or NoOpTransportDiagnosticsSink()
    replay = prepared_replay
    if replay is None:
        replay = await service.prepare_subscription(
            task_id=task_id,
            auth_context=auth_context,
            last_event_id=last_event_id,
            limit=settings.replay_batch_size,
        )
    actual_diagnostics.on_replay_start("sse", task_id, last_event_id)
    after_event_id = last_event_id

    if settings.anti_buffering_preamble_bytes > 0:
        padding = " " * settings.anti_buffering_preamble_bytes
        yield f": preamble {padding}\n\n"

    for event in replay.events:
        yield f"id: {event.event_id}\ndata: {event.model_dump_json(by_alias=True)}\n\n"
        after_event_id = event.event_id

    while not await request.is_disconnected():
        if settings.emit_comment_ping:
            yield f": {settings.comment_ping_payload}\n\n"

        try:
            events = await service.wait_for_live_events(
                task_id=task_id,
                after_event_id=after_event_id,
                limit=settings.live_batch_size,
                timeout_ms=settings.wait_timeout_ms,
            )
        except asyncio.CancelledError:
            actual_diagnostics.on_disconnect("sse", task_id)
            break

        if await request.is_disconnected():
            actual_diagnostics.on_disconnect("sse", task_id)
            break

        if not events:
            if settings.emit_json_heartbeat:
                actual_diagnostics.on_heartbeat("sse", task_id)
                heartbeat = {
                    "type": "HEARTBEAT",
                    "taskId": task_id,
                    "eventId": f"hb-{uuid4().hex}",
                    "createdAt": datetime.now(timezone.utc).isoformat(),
                    "payload": {"live": True},
                }
                yield f"data: {json.dumps(heartbeat, separators=(',', ':'))}\n\n"
            await asyncio.sleep(0.1)
            continue

        for event in events:
            if await request.is_disconnected():
                actual_diagnostics.on_disconnect("sse", task_id)
                break
            yield f"id: {event.event_id}\ndata: {event.model_dump_json(by_alias=True)}\n\n"
            actual_diagnostics.on_delivery("sse", task_id, event.event_id)
            after_event_id = event.event_id


def build_sse_stream_response(
    event_iterator: AsyncIterator[str],
    settings: SseStreamSettings,
) -> StreamingResponse:
    """Wrap an event generator in a FastAPI StreamingResponse.

    Args:
        event_iterator: The generator yielding SSE messages.
        settings: Configuration providing necessary HTTP headers.

    Returns:
        A StreamingResponse configured for text/event-stream.

    """
    return StreamingResponse(
        event_iterator,
        media_type="text/event-stream",
        headers={
            "Cache-Control": settings.cache_control,
            "Connection": settings.connection_header,
            "X-Accel-Buffering": settings.x_accel_buffering,
        },
    )
