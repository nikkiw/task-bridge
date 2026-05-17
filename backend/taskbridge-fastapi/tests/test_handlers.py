from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock

import pytest

from taskbridge.models import PollEventsResult, TaskEvent, TaskEventType
from taskbridge.observability import MetricsSink


@pytest.mark.asyncio
async def test_ws_handler_accepts_connection():
    from taskbridge.handlers import handle_websocket_subscription

    websocket = AsyncMock()
    ws_service = MagicMock()
    auth_context = MagicMock()
    metrics_sink = MagicMock(spec=MetricsSink)

    websocket.receive_json.side_effect = Exception("disconnect")

    try:
        await handle_websocket_subscription(websocket, ws_service, auth_context, metrics_sink)
    except Exception:
        pass

    websocket.accept.assert_called_once()


@pytest.mark.asyncio
async def test_ws_handler_full_protocol():
    from taskbridge.handlers import handle_websocket_subscription

    websocket = AsyncMock()
    ws_service = MagicMock()
    ws_service.prepare_subscription = AsyncMock()
    ws_service.wait_for_live_events = AsyncMock()

    auth_context = MagicMock()
    metrics_sink = MagicMock(spec=MetricsSink)

    now = datetime.now(timezone.utc)

    # 1. Receive Subscribe
    websocket.receive_json.return_value = {
        "action": "subscribe",
        "taskId": "task_1",
        "lastEventId": "ev_0",
    }

    # 2. Mock replay
    replay_result = PollEventsResult(
        taskId="task_1",
        events=[
            TaskEvent(
                type=TaskEventType.TASK_STARTED,
                taskId="task_1",
                eventId="ev_1",
                createdAt=now,
                payload={},
            )
        ],
        nextAfterEventId="ev_1",
        hasMore=False,
    )
    ws_service.prepare_subscription.return_value = replay_result

    # 3. Mock live events: first call empty (heartbeat), second call raises to exit
    ws_service.wait_for_live_events.side_effect = [
        [],  # Trigger heartbeat
        Exception("disconnect"),  # Exit loop
    ]

    try:
        await handle_websocket_subscription(
            websocket,
            ws_service,
            auth_context,
            metrics_sink,
            wait_timeout_ms=1,  # Short timeout for test
        )
    except Exception as e:
        if str(e) != "disconnect":
            raise e

    # Verify sequence
    websocket.accept.assert_called_once()

    json_calls = [call.args[0] for call in websocket.send_json.call_args_list]
    assert any(d.get("type") == "SUBSCRIPTION_CONFIRMED" for d in json_calls)
    assert any(d.get("eventId") == "ev_1" for d in json_calls)
    assert any(d.get("type") == "HEARTBEAT" for d in json_calls)


@pytest.mark.asyncio
async def test_http_polling_handler():
    from taskbridge.handlers import handle_http_polling

    polling_service = AsyncMock()
    auth_context = MagicMock()
    auth_context.subject = "user_1"
    metrics_sink = MagicMock(spec=MetricsSink)

    # now = datetime.now(timezone.utc)
    mock_result = PollEventsResult(
        taskId="task_1",
        events=[],
        nextAfterEventId="ev_0",
        hasMore=False,
    )
    polling_service.poll_events.return_value = mock_result

    result = await handle_http_polling(
        task_id="task_1",
        polling_service=polling_service,
        auth_context=auth_context,
        metrics_sink=metrics_sink,
        after_event_id="ev_0",
        limit=50,
        wait_timeout_ms=1000,
    )

    assert result == mock_result
    polling_service.poll_events.assert_called_once_with(
        task_id="task_1",
        auth_context=auth_context,
        after_event_id="ev_0",
        limit=50,
        wait_timeout_ms=1000,
    )
    metrics_sink.increment_counter.assert_called_with("fallbacks_total")
