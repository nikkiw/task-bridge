from __future__ import annotations

import json
from datetime import UTC, datetime

import pytest

from taskbridge.event_store import RedisEventStoreSettings, RedisStreamEventStore
from taskbridge.models import TaskEvent, TaskEventType


class FakeRedisStreamClient:
    def __init__(self) -> None:
        self.streams: dict[str, list[tuple[str, dict[str, str]]]] = {}
        self.sequence = 0
        self.last_block: int | None = None
        self.expire_calls: list[tuple[str, int]] = []

    async def xadd(
        self,
        name: str,
        fields: dict[str, str],
        id: str = "*",
        maxlen: int | None = None,
        approximate: bool = True,
    ) -> str:
        del id, approximate
        self.sequence += 1
        stream_id = f"{self.sequence}-0"
        self.streams.setdefault(name, []).append((stream_id, fields))
        if maxlen is not None:
            self.streams[name] = self.streams[name][-maxlen:]
        return stream_id

    async def xread(
        self,
        streams: dict[str, str],
        count: int | None = None,
        block: int | None = None,
    ) -> list[tuple[str, list[tuple[str, dict[str, str]]]]]:
        self.last_block = block
        results: list[tuple[str, list[tuple[str, dict[str, str]]]]] = []
        for name, last_seen_id in streams.items():
            entries = [
                (stream_id, fields)
                for stream_id, fields in self.streams.get(name, [])
                if self._is_strictly_newer(stream_id, last_seen_id)
            ]
            if count is not None:
                entries = entries[:count]
            if entries:
                results.append((name, entries))
        return results

    async def expire(self, name: str, ttl_seconds: int) -> bool:
        self.expire_calls.append((name, ttl_seconds))
        return True

    @staticmethod
    def _is_strictly_newer(candidate: str, checkpoint: str) -> bool:
        left_ms, left_seq = (int(part) for part in candidate.split("-", maxsplit=1))
        right_ms, right_seq = (int(part) for part in checkpoint.split("-", maxsplit=1))
        return (left_ms, left_seq) > (right_ms, right_seq)


def make_event(task_id: str, event_type: TaskEventType, marker: str) -> TaskEvent:
    return TaskEvent(
        type=event_type,
        taskId=task_id,
        eventId=f"client-{marker}",
        createdAt=datetime(2026, 5, 5, 12, 0, 0, tzinfo=UTC),
        payload={"marker": marker},
    )


@pytest.mark.asyncio
async def test_append_event_uses_redis_stream_id_as_public_event_id() -> None:
    store = RedisStreamEventStore(FakeRedisStreamClient())

    stored_event = await store.append_event(
        make_event("task-1", TaskEventType.TASK_STARTED, "first")
    )

    assert stored_event.event_id == "1-0"


@pytest.mark.asyncio
async def test_read_events_after_returns_only_strictly_newer_events() -> None:
    store = RedisStreamEventStore(FakeRedisStreamClient())
    await store.append_event(make_event("task-1", TaskEventType.TASK_STARTED, "one"))
    await store.append_event(make_event("task-1", TaskEventType.TASK_PROGRESS, "two"))
    await store.append_event(make_event("task-1", TaskEventType.TASK_COMPLETED, "three"))

    events = await store.read_events_after("task-1", after_event_id="1-0", limit=10)

    assert [event.event_id for event in events] == ["2-0", "3-0"]


@pytest.mark.asyncio
async def test_read_events_after_is_isolated_per_task_stream() -> None:
    store = RedisStreamEventStore(FakeRedisStreamClient())
    await store.append_event(make_event("task-1", TaskEventType.TASK_STARTED, "one"))
    await store.append_event(make_event("task-2", TaskEventType.TASK_STARTED, "other"))

    events = await store.read_events_after("task-1", after_event_id=None, limit=10)

    assert [event.task_id for event in events] == ["task-1"]


@pytest.mark.asyncio
async def test_wait_for_events_uses_same_checkpoint_semantics_with_block_timeout() -> None:
    redis = FakeRedisStreamClient()
    store = RedisStreamEventStore(redis)
    await store.append_event(make_event("task-1", TaskEventType.TASK_STARTED, "one"))
    await store.append_event(make_event("task-1", TaskEventType.TASK_PROGRESS, "two"))

    events = await store.wait_for_events(
        "task-1",
        after_event_id="1-0",
        limit=10,
        timeout_ms=3_000,
    )

    assert [event.event_id for event in events] == ["2-0"]
    assert redis.last_block == 3_000


@pytest.mark.asyncio
async def test_read_events_after_returns_empty_list_for_empty_stream() -> None:
    store = RedisStreamEventStore(FakeRedisStreamClient())

    events = await store.read_events_after("task-1", after_event_id=None, limit=10)

    assert events == []


@pytest.mark.asyncio
async def test_retention_keeps_tail_of_stream_and_replay_starts_from_retained_events() -> None:
    settings = RedisEventStoreSettings(max_stream_length=2)
    store = RedisStreamEventStore(FakeRedisStreamClient(), settings=settings)
    await store.append_event(make_event("task-1", TaskEventType.TASK_STARTED, "one"))
    await store.append_event(make_event("task-1", TaskEventType.TASK_PROGRESS, "two"))
    await store.append_event(make_event("task-1", TaskEventType.TASK_COMPLETED, "three"))

    events = await store.read_events_after("task-1", after_event_id=None, limit=10)

    assert [event.event_id for event in events] == ["2-0", "3-0"]


@pytest.mark.asyncio
async def test_append_event_persists_serialized_payload_and_round_trips() -> None:
    redis = FakeRedisStreamClient()
    store = RedisStreamEventStore(redis)

    stored_event = await store.append_event(
        make_event("task-1", TaskEventType.TASK_MESSAGE, "payload-check")
    )

    stream_payload = redis.streams[store.stream_key("task-1")][0][1]
    assert json.loads(stream_payload["payload"]) == {"marker": "payload-check"}
    assert stored_event.payload == {"marker": "payload-check"}


def test_default_settings_use_taskbridge_stream_prefix() -> None:
    settings = RedisEventStoreSettings()

    assert settings.stream_key_prefix == "taskbridge:tasks"


@pytest.mark.asyncio
async def test_append_event_applies_ttl_when_configured() -> None:
    redis = FakeRedisStreamClient()
    settings = RedisEventStoreSettings(stream_ttl_seconds=60)
    store = RedisStreamEventStore(redis, settings=settings)

    await store.append_event(make_event("task-1", TaskEventType.TASK_STARTED, "ttl"))

    assert redis.expire_calls == [(store.stream_key("task-1"), 60)]
