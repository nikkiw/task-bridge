# TaskBridge Observability and Ops

This guide describes how host applications wire observability for
`backend/taskbridge-fastapi`.

## Logging model

Use `taskbridge.observability.log_structured(...)` for consistent log payloads.

The helper emits structured fields under `extra["taskbridge"]` and supports:

- `taskId`
- `eventId`
- `clientRequestId`
- `userId`
- `transport` (`http`, `ws`, `internal`)
- `outcome`

## Metrics model

TaskBridge exposes `MetricsSink` with a no-op default implementation.

Host app integration pattern:

1. Implement `MetricsSink` using Prometheus, OpenTelemetry, StatsD, etc.
2. Override `get_metrics_sink` dependency in your FastAPI app.
3. Keep metric names stable for dashboards and alerting.

Core metrics emitted by TaskBridge:

- `task_starts_total`
- `streams_active`
- `fallbacks_total`
- `replays_total`
- `duplicates_total`
- `cancels_total`
- `task_failures_total`

## Readiness model

TaskBridge supports composable readiness probes:

- `RedisReadinessProbe` for Redis connectivity checks (`ping`)
- `ExecutorReadinessProbe` for executor adapter health (`check_health`)
- `CompositeReadinessProbe` for consolidated `/ready` status and details

The `/ready` route keeps the same response contract:

- `status`: `ready` or `not_ready`
- `details`: per dependency diagnostics

## Retention defaults

`RedisEventStoreSettings` operational defaults:

- `max_stream_length=1000`
- `stream_ttl_seconds=86400`
- `cleanup_interval_seconds=3600`

Recommended overrides:

- dev: smaller max length and shorter TTL for local debugging
- staging: production-like values with lower resource ceilings
- prod: values sized from replay SLO and expected task/event volume

## Reference dependency wiring

Minimal host app wiring shape:

- `get_readiness_probe` -> `CompositeReadinessProbe({"redis": ..., "executor": ...})`
- `get_metrics_sink` -> host metrics implementation
- standard TaskBridge service dependencies stay unchanged
