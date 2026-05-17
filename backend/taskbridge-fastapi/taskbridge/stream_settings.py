"""Configuration settings for streaming runtimes (SSE and WebSocket)."""

from __future__ import annotations

from dataclasses import dataclass, field

DEFAULT_REPLAY_BATCH_SIZE = 100
DEFAULT_LIVE_BATCH_SIZE = 100


@dataclass(slots=True)
class SseStreamSettings:
    """Settings for the Server-Sent Events (SSE) stream runtime."""

    replay_batch_size: int = DEFAULT_REPLAY_BATCH_SIZE
    live_batch_size: int = DEFAULT_LIVE_BATCH_SIZE
    wait_timeout_ms: int = 5_000
    emit_comment_ping: bool = True
    emit_json_heartbeat: bool = True
    comment_ping_payload: str = "heartbeat"
    anti_buffering_preamble_bytes: int = 0
    cache_control: str = "no-cache, no-transform"
    connection_header: str = "keep-alive"
    x_accel_buffering: str = "no"


@dataclass(slots=True)
class WebSocketStreamSettings:
    """Settings for the WebSocket stream runtime."""

    replay_batch_size: int = DEFAULT_REPLAY_BATCH_SIZE
    live_batch_size: int = DEFAULT_LIVE_BATCH_SIZE
    wait_timeout_ms: int = 25_000
    invalid_frame_close_code: int = 1008
    terminal_close_code: int = 1000
    emit_heartbeat_frame: bool = True


@dataclass(slots=True)
class StreamRuntimeSettings:
    """Aggregated settings for all supported stream runtimes."""

    sse: SseStreamSettings = field(default_factory=SseStreamSettings)
    websocket: WebSocketStreamSettings = field(default_factory=WebSocketStreamSettings)
