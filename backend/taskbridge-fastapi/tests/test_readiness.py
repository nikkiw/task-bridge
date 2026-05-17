from __future__ import annotations

from taskbridge.readiness import (
    AlwaysReadyProbe,
    CompositeReadinessProbe,
    ExecutorReadinessProbe,
    RedisReadinessProbe,
)


class HealthyRedis:
    async def ping(self) -> bool:
        return True


class BrokenRedis:
    async def ping(self) -> bool:
        raise RuntimeError("connection refused")


class HealthyExecutor:
    async def check_health(self) -> bool:
        return True


class BrokenExecutor:
    async def check_health(self) -> bool:
        return False


async def test_composite_readiness_reports_ready_when_all_probes_pass() -> None:
    probe = CompositeReadinessProbe(
        {
            "redis": RedisReadinessProbe(HealthyRedis()),
            "executor": ExecutorReadinessProbe(HealthyExecutor()),
        }
    )

    is_ready, details = await probe.check_readiness()

    assert is_ready is True
    assert details["redis"]["status"] == "ready"
    assert details["executor"]["status"] == "ready"


async def test_composite_readiness_reports_not_ready_on_dependency_failure() -> None:
    probe = CompositeReadinessProbe(
        {
            "redis": RedisReadinessProbe(BrokenRedis()),
            "executor": ExecutorReadinessProbe(BrokenExecutor()),
        }
    )

    is_ready, details = await probe.check_readiness()

    assert is_ready is False
    assert details["redis"]["status"] == "not_ready"
    assert details["executor"]["status"] == "not_ready"


async def test_composite_readiness_supports_mixed_probe_types() -> None:
    probe = CompositeReadinessProbe(
        {
            "always": AlwaysReadyProbe(),
            "executor": ExecutorReadinessProbe(HealthyExecutor()),
        }
    )

    is_ready, details = await probe.check_readiness()

    assert is_ready is True
    assert details["always"]["status"] == "ready"
