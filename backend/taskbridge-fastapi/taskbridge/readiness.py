"""Readiness probing and health checks.

This module provides infrastructure for checking the health and readiness
of backend components like Redis and Task Executors.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from inspect import isawaitable
from typing import Any


class ReadinessProbe(ABC):
    """Abstract base class for readiness checks."""

    @abstractmethod
    async def check_readiness(self) -> tuple[bool, dict[str, Any]]:
        """Perform a readiness check.

        Returns:
            A tuple of (is_ready, details).

        """
        raise NotImplementedError


class AlwaysReadyProbe(ReadinessProbe):
    """Probe that always returns ready status."""

    async def check_readiness(self) -> tuple[bool, dict[str, Any]]:
        """Return True and empty details."""
        return True, {}


class CompositeReadinessProbe(ReadinessProbe):
    """Probe that combines multiple probes into one."""

    def __init__(self, probes: dict[str, ReadinessProbe]) -> None:
        """Initialize composite probe.

        Args:
            probes: Dictionary of named probes to check.

        """
        self._probes = probes

    async def check_readiness(self) -> tuple[bool, dict[str, Any]]:
        """Check all child probes and aggregate their status.

        Returns:
            Tuple of (aggregated_is_ready, component_details).

        """
        is_ready = True
        details: dict[str, Any] = {}
        for name, probe in self._probes.items():
            probe_ready, probe_details = await probe.check_readiness()
            is_ready = is_ready and probe_ready
            details[name] = {
                "status": "ready" if probe_ready else "not_ready",
                **probe_details,
            }
        return is_ready, details


class RedisReadinessProbe(ReadinessProbe):
    """Probe for checking Redis connectivity."""

    def __init__(self, redis_client: Any) -> None:
        """Initialize Redis probe.

        Args:
            redis_client: Redis client instance.

        """
        self._redis = redis_client

    async def check_readiness(self) -> tuple[bool, dict[str, Any]]:
        """Ping Redis and return readiness status.

        Returns:
            True if Redis responded to ping, False otherwise.

        """
        try:
            ping_result = self._redis.ping()
            if isawaitable(ping_result):
                ping_result = await ping_result
        except Exception as exc:
            return False, {"message": "Redis ping failed", "error": str(exc)}

        if ping_result:
            return True, {"message": "Redis ping ok"}
        return False, {"message": "Redis ping returned falsy result"}


class ExecutorReadinessProbe(ReadinessProbe):
    """Probe for checking task executor availability."""

    def __init__(self, executor_adapter: Any, method_name: str = "check_health") -> None:
        """Initialize executor probe.

        Args:
            executor_adapter: Adapter instance for the task executor.
            method_name: Name of the health check method to call on adapter.

        """
        self._executor_adapter = executor_adapter
        self._method_name = method_name

    async def check_readiness(self) -> tuple[bool, dict[str, Any]]:
        """Call the executor's health check method and return status.

        Returns:
            Readiness status reported by the executor.

        """
        check_method = getattr(self._executor_adapter, self._method_name, None)
        if check_method is None:
            return False, {"message": f"Executor adapter missing `{self._method_name}`"}

        try:
            result = check_method()
            if isawaitable(result):
                result = await result
        except Exception as exc:
            return False, {"message": "Executor readiness failed", "error": str(exc)}

        if isinstance(result, tuple) and len(result) == 2:
            return bool(result[0]), result[1]
        return bool(result), {"message": "Executor health check completed"}
