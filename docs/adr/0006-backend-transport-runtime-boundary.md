# ADR 0006: Backend transport runtime boundary for FastAPI hosts

## Status
Accepted

## Context

Host integrations currently reimplement SSE runtime concerns:

- `StreamingResponse` loop ownership
- `Last-Event-ID` replay handling
- SSE keepalive comments
- JSON heartbeat behavior
- anti-buffering headers and optional preamble behavior
- transport-specific wait timeout tuning
- transport diagnostics

This leaks protocol/runtime details out of `taskbridge-fastapi` into host apps.
The result is that hosts understand too much about backend transport behavior and
must debug buffering, heartbeats, disconnects, and replay loops themselves.

## Decision

`taskbridge-fastapi` owns backend transport runtime behavior for:

- WebSocket live/replay orchestration
- SSE replay/live orchestration
- transport keepalive strategy
- terminal event shutdown rules
- transport-specific timeout policy
- anti-buffering response headers
- transport diagnostics hooks

Host apps own:

- auth resolution
- ownership policy
- task registry
- executor wiring
- route prefix/path customization
- domain-specific create-task preprocessing

## Consequences

- Hosts stop implementing transport loops directly.
- Backend examples and documentation must show library-owned SSE integration.
- `Router+Service` remains the valid low-level API, but transport runtime becomes
  a first-class convenience layer owned by the library.
- Protocol-visible semantics remain stable, while runtime tuning details become
  typed configuration instead of handwritten host code.
