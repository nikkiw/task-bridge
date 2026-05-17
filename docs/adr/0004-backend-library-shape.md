# ADR 0004: Backend Library Shape for FastAPI Hosts

## Status

Accepted

## Context

`taskbridge-fastapi` is designed as an infrastructure library, not a standalone backend service.
It should be embeddable into host FastAPI applications.

Primary goals for the backend package:

- provide reusable task/event contracts;
- provide transport endpoints for start/poll/cancel/ws;
- avoid forcing host apps into a specific auth scheme, lifecycle model, or DI architecture.

For v1, we needed to choose the public API shape for host FastAPI apps.

Considered options:

1. `Router+Service`
2. `App Plugin`
3. `Two Layers v1`

Related documents:

- [ADR 0001: backend stack](0001-backend-stack.md)
- [ADR 0006: backend transport runtime boundary](0006-backend-transport-runtime-boundary.md)

## Options

### 1. Router+Service

The library exports:

- typed models;
- service layer;
- router builders or router modules;
- dependency hook interfaces;
- settings/config objects.

The host application remains responsible for:

- creating the FastAPI app;
- mounting routers;
- configuring auth;
- passing registry/event store/executor implementations;
- owning lifecycle, logging, metrics, and middleware.

Pros:

- fits mature FastAPI codebases well;
- keeps `task-bridge` a library, not a mini-framework;
- improves OSS adoption because host architecture does not need to change;
- isolates core contracts from FastAPI wiring.

Cons:

- integration is slightly more verbose;
- examples and quick start become slightly longer.

### 2. App Plugin

The library exports one main helper such as `setup_taskbridge(app, ...)` that mounts routes and part of lifecycle hooks internally.

Pros:

- faster onboarding for simple demos and PoCs;
- less boilerplate in host apps.

Cons:

- tighter coupling with host FastAPI internals;
- weaker fit for custom auth/DI/lifecycle rules;
- higher risk that the library starts dictating backend architecture;
- harder adoption in existing production codebases.

### 3. Two Layers v1

Support both `Router+Service` and `setup_taskbridge(...)` from day one.

Pros:

- appears flexible;
- covers low-level and convenience use cases.

Cons:

- too early for v1 to double public API surface;
- helper API can lock decisions before low-level API stabilizes;
- heavier docs, tests, and release support.

## Decision

`taskbridge-fastapi` adopts `Router+Service` as the primary public backend API.

This means:

- backend package remains a library for host FastAPI apps;
- host app owns final app composition;
- host app owns auth wiring and lifecycle;
- core services and abstractions stay framework-light;
- route layer is built on top of service layer, not the opposite.

## Implementation Meaning

The backend implementation must include:

- typed core models;
- backend-local error model;
- abstract interfaces:
  - `EventStore`
  - `TaskRegistry`
  - `TaskExecutor`
  - `AuthContextResolver`
  - `OwnershipPolicy`
- service-layer entrypoints for task creation, polling, cancellation, and websocket subscription orchestration;
- FastAPI integration layer that uses the service layer but does not own host app architecture.

A host FastAPI project must be able to:

- mount taskbridge routes under its own prefix;
- use its own auth scheme;
- attach custom observability hooks;
- choose its own adapter implementations for registry, event store, and executor.

## Rejected

The approach where the backend package requires a standalone app shell or enforces a single wiring path through `setup_taskbridge(app, ...)` is rejected.

Such a helper may be added later only as a thin convenience wrapper over a stable `Router+Service` API.

## Consequences

Positive:

- stronger fit for OSS reuse;
- easier integration into existing FastAPI services;
- cleaner boundaries between transport layer and business/executor integration.

Negative:

- slightly higher integration verbosity;
- stronger discipline required for service-layer and dependency-contract design.

## Follow-up Note (2026-05-13)

The original `Router+Service` decision remains intact, but transport runtime concerns such as SSE replay/live loops, heartbeat policy, buffering headers, and transport diagnostics are library responsibilities rather than host-app responsibilities.

Host apps may still compose routers manually, but should do so through transport helpers/builders instead of handwritten stream generators.
