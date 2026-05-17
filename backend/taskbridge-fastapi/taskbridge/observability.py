"""Monitoring and observability for TaskBridge.

This module defines interfaces for metrics collection, transport diagnostics,
and structured logging.
"""

from __future__ import annotations

import logging
from abc import ABC, abstractmethod
from typing import Any


class MetricsSink(ABC):
    """Abstract interface for application metrics."""

    @abstractmethod
    def increment_counter(
        self,
        metric_name: str,
        value: int = 1,
        *,
        tags: dict[str, str] | None = None,
    ) -> None:
        """Increment a monotonic counter.

        Args:
            metric_name: Name of the metric.
            value: Amount to increment by.
            tags: Optional dimensional tags.

        """
        raise NotImplementedError

    @abstractmethod
    def set_gauge(
        self,
        metric_name: str,
        value: float,
        *,
        tags: dict[str, str] | None = None,
    ) -> None:
        """Set a gauge value.

        Args:
            metric_name: Name of the metric.
            value: New value for the gauge.
            tags: Optional dimensional tags.

        """
        raise NotImplementedError


class NoOpMetricsSink(MetricsSink):
    """Metrics sink that drops all metrics."""

    def increment_counter(
        self,
        metric_name: str,
        value: int = 1,
        *,
        tags: dict[str, str] | None = None,
    ) -> None:
        """Drop counter increment."""
        del metric_name, value, tags

    def set_gauge(
        self,
        metric_name: str,
        value: float,
        *,
        tags: dict[str, str] | None = None,
    ) -> None:
        """Drop gauge update."""
        del metric_name, value, tags


class TransportDiagnosticsSink:
    """Sink for low-level transport events (heartbeats, delivery, etc.)."""

    def on_replay_start(self, transport: str, task_id: str, after_event_id: str | None) -> None:
        """Record start of an event replay."""
        del transport, task_id, after_event_id

    def on_heartbeat(self, transport: str, task_id: str) -> None:
        """Record a heartbeat sent to client."""
        del transport, task_id

    def on_delivery(self, transport: str, task_id: str, event_id: str) -> None:
        """Record successful event delivery."""
        del transport, task_id, event_id

    def on_disconnect(self, transport: str, task_id: str) -> None:
        """Record client disconnection."""
        del transport, task_id


class NoOpTransportDiagnosticsSink(TransportDiagnosticsSink):
    """Transport diagnostics sink that drops all events."""

    pass


def log_structured(
    logger: logging.Logger,
    *,
    level: int,
    message: str,
    task_id: str | None = None,
    event_id: str | None = None,
    client_request_id: str | None = None,
    user_id: str | None = None,
    transport: str | None = None,
    outcome: str | None = None,
    details: dict[str, Any] | None = None,
) -> None:
    """Log a message with structured metadata in the 'taskbridge' extra field.

    Args:
        logger: Logger instance.
        level: Logging level (e.g. logging.INFO).
        message: Log message.
        task_id: Optional task ID.
        event_id: Optional event ID.
        client_request_id: Optional idempotency key.
        user_id: Optional subject ID.
        transport: Transport name (http/ws).
        outcome: Outcome tag (ok/error/confirmed).
        details: Additional context as a dictionary.

    """
    payload: dict[str, Any] = {}
    if task_id is not None:
        payload["taskId"] = task_id
    if event_id is not None:
        payload["eventId"] = event_id
    if client_request_id is not None:
        payload["clientRequestId"] = client_request_id
    if user_id is not None:
        payload["userId"] = user_id
    if transport is not None:
        payload["transport"] = transport
    if outcome is not None:
        payload["outcome"] = outcome
    if details:
        payload["details"] = details

    logger.log(level, message, extra={"taskbridge": payload})
