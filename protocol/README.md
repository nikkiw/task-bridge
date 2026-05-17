# TaskBridge Protocol

This directory contains the internal wire-level contract for `task-bridge`.

## Purpose

The protocol layer is the source of truth for:

- public HTTP and WebSocket contracts;
- event envelopes and terminal event semantics;
- replay and deduplication rules;
- compatibility fixtures used by backend and Android implementations.

Implementation details in `backend/` and `android/` must follow this directory, not redefine it.
This directory is an internal contract layer inside the monorepo and is not intended to be consumed as a separate downstream package.

## Stability Model

Wire-stable artifacts:

- [`openapi/taskbridge.openapi.yaml`](openapi/taskbridge.openapi.yaml)
- [`schemas/task_event.schema.json`](schemas/task_event.schema.json)
- JSON fixtures in [`examples/`](examples/)

Implementation details that are intentionally not wire-stable:

- Redis stream key naming
- internal task registry schema
- Android persistence implementation
- executor-specific progress generation
- server logging and metrics internals

## Versioning and Compatibility

- The repository should use semantic versioning once public releases begin.
- Any breaking change to the wire contract requires a major version bump.
- Adding optional fields or new non-terminal capabilities without changing existing semantics is a minor version change.
- Fixes that do not change the public contract are patch releases.
- Backend and Android implementations must declare which protocol version they target when compatibility guarantees become part of release metadata.
- Downstream adopters should use the publishable backend and Android packages rather than depend on `protocol/` directly.

## Event Envelope

Every server-to-client event must contain:

- `type`
- `taskId`
- `eventId`
- `createdAt`
- `payload`

Terminal events are:

- `TASK_COMPLETED`
- `TASK_FAILED`
- `TASK_CANCELLED`

`eventId` must be monotonic within a task stream and safe to use for replay checkpoints.
In the reference FastAPI backend, `eventId` is the Redis Stream ID assigned on append.

## Replay Semantics

- Polling uses `afterEventId` and returns only events strictly newer than the provided value.
- WebSocket subscribe uses `lastEventId` and replays only events strictly newer than the provided value before switching to live delivery.
- Missing or empty checkpoint values mean "start from the beginning of the retained stream".
- Duplicate delivery is allowed at transport boundaries; consumers must deduplicate by `eventId`.

## WebSocket Frame Semantics

The WebSocket transport has two frame categories:

- control frames used to negotiate subscription state
- task event frames that follow the shared `TaskEvent` envelope

Control frames are not `TaskEvent` objects. In v1, the expected control flow is:

- client sends a `subscribe` frame with `taskId` and optional `lastEventId`
- server responds with a `SUBSCRIPTION_CONFIRMED` frame
- server may emit `HEARTBEAT` frames while the subscription is live but no task events are currently available
- server then delivers replayed and live task events using the shared event envelope

See:

- [`examples/websocket_subscribe_request.json`](examples/websocket_subscribe_request.json)
- [`examples/websocket_subscribe_ack.json`](examples/websocket_subscribe_ack.json)
- [`examples/websocket_heartbeat.json`](examples/websocket_heartbeat.json)

Transport keepalive implementation details such as SSE comment pings, buffering
headers, or anti-buffering preambles are runtime concerns of the publishable
backend package, not host-app responsibilities. What remains protocol-relevant
is the stable event envelope, replay cursor semantics, and WebSocket control
frame shape.

## Idempotency Semantics

- Task creation requests use `clientRequestId`.
- Repeating the same `clientRequestId` for the same authenticated owner and same logical task must return the existing task instead of creating a new one.
- Reuse of a `clientRequestId` with conflicting payload for the same owner must be rejected with a stable error code.

## Error Model

Errors must use a stable JSON envelope with:

- `code`
- `message`
- `details`
- `timestamp`

This contract applies to both polling/start/cancel endpoints and to recoverable transport negotiation failures where an HTTP body is available.

## Notes for Contributors

- Update protocol examples whenever the public contract changes.
- Update ADRs if the transport or replay model changes.
- Do not add domain-specific task payload semantics here unless they are generic across adopters.
- Keep v1 focused on the task streaming layer; domain orchestration belongs in adopters and adapters.

## Validation commands

Run protocol contract validation from repository root:

```bash
uv run python protocol/validate_protocol.py
```

This script validates:

- task-event fixtures against `schemas/task_event.schema.json`;
- required sections and canonical paths in `openapi/taskbridge.openapi.yaml`.
