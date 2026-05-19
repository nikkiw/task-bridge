# taskbridge-fastapi

`taskbridge-fastapi` is the reusable backend package for TaskBridge.

It is designed to be embedded into host FastAPI applications rather than replace
their app shell.

## Integration model

Host applications own:

- `FastAPI()` app construction
- auth and middleware
- startup and shutdown lifecycle
- infrastructure adapters for task registry, event store, and executor

TaskBridge provides:

- typed models and errors
- abstract interfaces
- service orchestration layer
- router builders and dependency hooks
- a Redis Streams event-store adapter with replay-safe `eventId` semantics
- typed backend stream runtime settings for WebSocket and SSE
- library-owned SSE runtime helpers so hosts do not implement transport loops

The important architectural rule is:

- the host owns application shell and infrastructure;
- `taskbridge-fastapi` owns transport semantics and service orchestration;
- runtime-specific execution belongs in adapter packages such as Temporal.

## Host transport integration

Host applications should not implement transport loops directly.

`taskbridge-fastapi` now provides:

- HTTP polling route builder
- WebSocket route builder
- SSE runtime helper/route builder
- typed transport runtime settings

Hosts are expected to provide only:

- auth resolution
- ownership policy
- task registry / executor / event store adapters
- route path customization
- domain-specific create-task preprocessing

That means host apps should avoid handwritten `StreamingResponse` generators for
task streaming, `Last-Event-ID` replay loops, SSE heartbeat formatting, and
transport-specific wait tuning when a TaskBridge helper already covers the case.

Minimal integration shape:

```python
from fastapi import FastAPI

from taskbridge.routes_http import build_http_router, install_http_exception_handlers
from taskbridge.routes_ws import build_ws_router

app = FastAPI()
app.include_router(build_http_router())
app.include_router(build_ws_router())
install_http_exception_handlers(app)
```

Release history for this package lives in [`backend/taskbridge-fastapi/CHANGELOG.md`](CHANGELOG.md). The committed `pyproject.toml` version stays at `0.0.0.dev0`; the published version comes from the `python-vX.Y.Z` tag in CI. Use the repository `prepare-release` workflow before pushing that tag. Release-bearing PR titles and squash titles must follow Conventional Commits such as `fix(backend): ...`.

## Observability and ops

The package includes host-overridable observability hooks:

- `MetricsSink` abstraction (`NoOpMetricsSink` by default)
- structured application logs via `log_structured(...)` helper
- readiness probe composition for dependency health (`CompositeReadinessProbe`)

Default metrics emitted by backend flow:

- `task_starts_total`
- `streams_active`
- `fallbacks_total`
- `replays_total`
- `duplicates_total`
- `cancels_total`
- `task_failures_total`

Structured logs include correlation fields when available:

- `taskId`
- `eventId`
- `clientRequestId`
- `userId`
- `transport`
- `outcome`

## Security integration

Host applications authenticate requests and map them into `AuthContext` via
`AuthContextResolver`.

TaskBridge provides:

- `OwnershipPolicy` for create/read/cancel/subscribe authorization
- enumeration-safe task reads for foreign task ids
- `UploadPolicy` for host-defined attachment quotas and rate decisions

Reference patterns for JWT and internal service auth are documented in
[`docs/security-integration.md`](../../docs/security-integration.md).

Observability wiring and readiness examples are documented in
[`docs/observability-ops.md`](../../docs/observability-ops.md).

## Retention defaults

`RedisEventStoreSettings` now includes operational retention defaults:

- `max_stream_length=1000`
- `stream_ttl_seconds=86400`
- `cleanup_interval_seconds=3600`

Host applications can override these values per environment (dev/staging/prod).

## Documentation map

Use the docs site for the concept-level guide:

- `docs/backend/index.md` for backend overview
- `docs/backend/host-integration.md` for host ownership and dependency boundaries
- `docs/backend/services-and-routes.md` for route/service split
- `docs/backend/security-readiness-observability.md` for security and ops hooks
- `docs/backend/state-and-runtime-boundaries.md` for durable state and runtime boundaries
- `docs/adapters/temporal.md` for the main runtime adapter example

## Developer workflow

Run commands from this package directory:

- `uv run pytest`
- `uv run ruff check`
- `uv run ruff format`
- `uv run ruff format --check`

For repository-level documentation and docs generation commands, see `../../docs/documentation/index.md`.

## Phase 11 test matrix

Primary test areas:

- unit/service coverage in `tests/test_models.py`, `tests/test_services.py`, `tests/test_redis_event_store.py`
- route and contract checks in `tests/test_http_api.py`, `tests/test_ws_api.py`, `tests/test_fastapi_contract.py`
- integration flow checks in `tests/test_integration_flow.py`
- protocol contract validation in `tests/test_protocol_contracts.py`

Reference matrix details live in [`tests/README.md`](tests/README.md).
