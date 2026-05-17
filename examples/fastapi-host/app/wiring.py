from __future__ import annotations

import os
from dataclasses import dataclass

from redis.asyncio import from_url as redis_from_url
from taskbridge.event_store import RedisEventStoreSettings, RedisStreamEventStore
from taskbridge.fakes import DeterministicFakeExecutor
from taskbridge.readiness import (
    AlwaysReadyProbe,
    CompositeReadinessProbe,
    ExecutorReadinessProbe,
    RedisReadinessProbe,
)
from taskbridge.services import (
    TaskActionService,
    TaskCancellationService,
    TaskCreationService,
    TaskPollingService,
    WebSocketSubscriptionService,
)

from .demo_adapters import (
    DemoAuthResolver,
    DemoEventStore,
    DemoOwnershipPolicy,
    DemoTaskRegistry,
)
from .enterprise_stores import InMemoryActionReceiptStore, InMemorySuspensionStore
from .firebase_adapters import FirebaseAuthResolver, FirebaseOwnershipPolicy
from .temporal_resume_service import TemporalTaskResumeService
from .temporal_streaming_executor import StreamingTemporalExecutor


@dataclass(slots=True)
class DemoServices:
    registry: DemoTaskRegistry
    store: object
    executor: object
    auth_resolver: DemoAuthResolver
    creation: TaskCreationService
    polling: TaskPollingService
    cancellation: TaskCancellationService
    websocket_subscription: WebSocketSubscriptionService
    action: TaskActionService
    readiness_probe: object


def build_demo_services(
    *,
    progress_ticks: int = 2,
    step_delay_seconds: float = 0.05,
) -> DemoServices:
    mode = os.getenv("TB_EXECUTOR_MODE", "temporal").strip().lower()
    registry = DemoTaskRegistry()
    suspension_store = InMemorySuspensionStore()
    receipt_store = InMemoryActionReceiptStore()

    if mode == "fake":
        store = DemoEventStore()
        executor = DeterministicFakeExecutor(
            registry=registry,
            event_store=store,
            progress_ticks=progress_ticks,
            step_delay_seconds=step_delay_seconds,
        )
        readiness_probe = AlwaysReadyProbe()
    else:
        redis_url = os.getenv("REDIS_URL", "redis://127.0.0.1:6379/0")
        redis_client = redis_from_url(redis_url, encoding="utf-8", decode_responses=False)
        store = RedisStreamEventStore(
            redis_client=redis_client,
            settings=RedisEventStoreSettings(
                stream_key_prefix=os.getenv("TB_STREAM_PREFIX", "taskbridge:examples"),
                stream_ttl_seconds=int(os.getenv("TB_STREAM_TTL_SECONDS", "86400")),
                max_stream_length=int(os.getenv("TB_STREAM_MAXLEN", "1000")),
            ),
        )
        temporal_target = os.getenv("TEMPORAL_ADDRESS", "127.0.0.1:7233")
        temporal_namespace = os.getenv("TEMPORAL_NAMESPACE", "default")
        temporal_queue = os.getenv("TEMPORAL_TASK_QUEUE", "taskbridge-demo")
        executor = StreamingTemporalExecutor(
            task_registry=registry,
            event_store=store,
            suspension_store=suspension_store,
            temporal_target=temporal_target,
            temporal_namespace=temporal_namespace,
            task_queue=temporal_queue,
        )
        readiness_probe = CompositeReadinessProbe(
            {
                "redis": RedisReadinessProbe(redis_client),
                "executor": ExecutorReadinessProbe(executor),
            }
        )
    if os.getenv("TB_AUTH_MODE") == "firebase":
        auth_resolver = FirebaseAuthResolver()
        ownership = FirebaseOwnershipPolicy()
    else:
        auth_resolver = DemoAuthResolver()
        ownership = DemoOwnershipPolicy()

    if isinstance(executor, StreamingTemporalExecutor):
        resume_service = TemporalTaskResumeService(
            client_factory=executor._get_client,
            workflow_id_fn=executor._workflow_id_for
        )
    else:
        resume_service = None

    action_service = TaskActionService(
        registry=registry,
        suspension_store=suspension_store,
        receipt_store=receipt_store,
        event_store=store,
        ownership_policy=ownership,
        resume_service=resume_service,
    )

    return DemoServices(
        registry=registry,
        store=store,
        executor=executor,
        auth_resolver=auth_resolver,
        creation=TaskCreationService(registry, executor, ownership),
        polling=TaskPollingService(registry, store, ownership),
        cancellation=TaskCancellationService(registry, executor, ownership),
        websocket_subscription=WebSocketSubscriptionService(registry, store, ownership),
        action=action_service,
        readiness_probe=readiness_probe,
    )
