# Protocol

The `protocol/` layer is the wire-level source of truth for TaskBridge.

## Stable artifacts

- OpenAPI contract: `protocol/openapi/taskbridge.openapi.yaml`
- task-event schema: `protocol/schemas/task_event.schema.json`
- JSON fixtures in `protocol/examples/`

## Core semantics

Every server-to-client task event contains:

- `type`
- `taskId`
- `eventId`
- `createdAt`
- `payload`

Terminal events:

- `TASK_COMPLETED`
- `TASK_FAILED`
- `TASK_CANCELLED`

Replay rules:

- polling uses `afterEventId`
- WebSocket subscribe uses `lastEventId`
- empty checkpoint means replay from retained stream start
- duplicate transport delivery is allowed; consumers deduplicate by `eventId`

## Validation

Run from repository root:

```bash
uv run python protocol/validate_protocol.py
```

## Consumers

- Android SDK must keep public event handling aligned with protocol fixtures
- backend routers and services must keep HTTP/WebSocket contracts aligned with OpenAPI
