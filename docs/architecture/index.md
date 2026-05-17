# Architecture

TaskBridge is a repository-level integration library with strict boundaries between transport, protocol, and runtime-specific execution.

## Repository layers

- `android/`: SDK and sample app
- `backend/`: reusable FastAPI package
- `protocol/`: wire contracts and fixtures
- `backend/adapters/`: optional execution backend integrations
- `examples/`: runnable adopter setups

## Core architectural decisions

- task streams are keyed by `taskId`
- replay and deduplication use monotonic `eventId`
- Redis Streams or equivalent durable storage back replayable event history
- Android public API stays `Flow<TaskEvent>`
- transport fallback must not silently break the consumer observation model

## Deep-dive topics

- executor split and host responsibilities live in [Executor Integration](../executor-integration.md)
- security hooks and ownership model live in [Security Integration](../security-integration.md)
- observability and readiness hooks live in [Observability and Ops](../observability-ops.md)
- higher-level design records are summarized in [ADR](../adr/index.md)
