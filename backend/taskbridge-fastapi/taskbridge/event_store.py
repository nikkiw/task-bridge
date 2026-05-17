"""Task event storage and retrieval.

This module defines the abstract EventStore interface and its Redis Stream implementation
for persisting and streaming task events.
"""

from __future__ import annotations

import json
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Any

from .models import TaskEvent


class EventStore(ABC):
    """Abstract base class for task event storage."""

    @abstractmethod
    async def append_event(self, event: TaskEvent) -> TaskEvent:
        """Append an event to the store.

        Args:
            event: The event to append.

        Returns:
            The appended event with its assigned event ID.

        """
        raise NotImplementedError

    @abstractmethod
    async def read_events_after(
        self, task_id: str, after_event_id: str | None, limit: int
    ) -> list[TaskEvent]:
        """Read events for a task after a specific event ID.

        Args:
            task_id: The ID of the task.
            after_event_id: The event ID to start after. If None, read from the beginning.
            limit: Maximum number of events to read.

        Returns:
            List of task events.

        """
        raise NotImplementedError

    @abstractmethod
    async def wait_for_events(
        self, task_id: str, after_event_id: str | None, limit: int, timeout_ms: int
    ) -> list[TaskEvent]:
        """Wait for new events to be appended for a task.

        Args:
            task_id: The ID of the task.
            after_event_id: The event ID to start after.
            limit: Maximum number of events to return.
            timeout_ms: Maximum time to wait in milliseconds.

        Returns:
            List of task events.

        """
        raise NotImplementedError


@dataclass(slots=True)
class RedisEventStoreSettings:
    """Settings for Redis Stream event store."""

    stream_key_prefix: str = "taskbridge:tasks"
    max_stream_length: int | None = 1_000
    approximate_maxlen: bool = True
    stream_ttl_seconds: int | None = 86_400
    cleanup_interval_seconds: int = 3_600


class RedisStreamEventStore(EventStore):
    """Event store implementation using Redis Streams."""

    def __init__(
        self,
        redis_client: Any,
        settings: RedisEventStoreSettings | None = None,
    ) -> None:
        """Initialize Redis Stream event store.

        Args:
            redis_client: Redis client instance.
            settings: Optional storage settings.

        """
        self._redis = redis_client
        self._settings = settings or RedisEventStoreSettings()

    def stream_key(self, task_id: str) -> str:
        """Get the Redis key for a task's event stream.

        Args:
            task_id: The ID of the task.

        Returns:
            The Redis key string.

        """
        return f"{self._settings.stream_key_prefix}:{task_id}:events"

    async def append_event(self, event: TaskEvent) -> TaskEvent:
        """Append an event to the Redis Stream.

        Args:
            event: The event to append.

        Returns:
            The event with assigned Redis Stream ID.

        """
        stream_key = self.stream_key(event.task_id)
        stream_id = await self._redis.xadd(
            name=stream_key,
            fields=self._serialize_event_fields(event),
            maxlen=self._settings.max_stream_length,
            approximate=self._settings.approximate_maxlen,
        )
        if self._settings.stream_ttl_seconds is not None and hasattr(self._redis, "expire"):
            await self._redis.expire(stream_key, self._settings.stream_ttl_seconds)
        return event.model_copy(update={"event_id": self._decode_scalar(stream_id)})

    async def read_events_after(
        self, task_id: str, after_event_id: str | None, limit: int
    ) -> list[TaskEvent]:
        """Read events from Redis Stream after a checkpoint.

        Args:
            task_id: The ID of the task.
            after_event_id: The stream ID to start after.
            limit: Max entries to read.

        Returns:
            Deserialized task events.

        """
        if limit <= 0:
            return []
        results = await self._redis.xread(
            streams={self.stream_key(task_id): self._checkpoint(after_event_id)},
            count=limit,
        )
        return self._deserialize_results(results)

    async def wait_for_events(
        self, task_id: str, after_event_id: str | None, limit: int, timeout_ms: int
    ) -> list[TaskEvent]:
        """Perform a blocking read on Redis Stream for new events.

        Args:
            task_id: The ID of the task.
            after_event_id: The stream ID to start after.
            limit: Max entries to return.
            timeout_ms: Block timeout in milliseconds.

        Returns:
            Deserialized task events.

        """
        if limit <= 0:
            return []
        results = await self._redis.xread(
            streams={self.stream_key(task_id): self._checkpoint(after_event_id)},
            count=limit,
            block=timeout_ms,
        )
        return self._deserialize_results(results)

    @staticmethod
    def _checkpoint(after_event_id: str | None) -> str:
        return after_event_id or "0-0"

    @staticmethod
    def _serialize_event_fields(event: TaskEvent) -> dict[str, str]:
        return {
            "type": event.type.value,
            "taskId": event.task_id,
            "createdAt": event.created_at.isoformat(),
            "payload": json.dumps(event.payload, separators=(",", ":"), sort_keys=True),
        }

    def _deserialize_results(self, results: Any) -> list[TaskEvent]:
        events: list[TaskEvent] = []
        for _, entries in results:
            for stream_id, fields in entries:
                decoded_fields = {
                    self._decode_scalar(key): self._decode_scalar(value)
                    for key, value in fields.items()
                }
                events.append(
                    TaskEvent(
                        type=decoded_fields["type"],
                        taskId=decoded_fields["taskId"],
                        eventId=self._decode_scalar(stream_id),
                        createdAt=decoded_fields["createdAt"],
                        payload=json.loads(decoded_fields["payload"]),
                    )
                )
        return events

    @staticmethod
    def _decode_scalar(value: Any) -> str:
        if isinstance(value, bytes):
            return value.decode("utf-8")
        return str(value)
