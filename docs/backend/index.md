# Backend

`taskbridge-fastapi` is the reusable backend package for embedding TaskBridge into host FastAPI applications.

## Host responsibilities

Host applications own:

- `FastAPI()` app construction
- authentication and middleware
- startup and shutdown lifecycle
- durable task registry
- durable event store
- executor adapter wiring

TaskBridge provides:

- typed models and error envelopes
- services and dependency hooks
- HTTP and WebSocket route builders
- replay-safe Redis Streams adapter
- observability and readiness abstractions

## Integration model

Key extension points:

- `TaskRegistry`
- `EventStore`
- `TaskExecutor`
- `AuthContextResolver`
- `OwnershipPolicy`
- `UploadPolicy`

## Operational guidance

Security model highlights:

- host-authenticated requests are normalized into `AuthContext`
- task access is ownership-based
- task reads are enumeration-safe by default

Observability highlights:

- structured logs carry `taskId`, `eventId`, and `clientRequestId`
- metrics are emitted through a host-provided `MetricsSink`
- readiness probes compose Redis and executor health

## Related docs

- generated [Python API Reference](../reference/backend.md)
- [Protocol](../protocol/index.md) for wire-level compatibility
- [Adapters](../adapters/index.md) for runtime-specific execution backends
