# Adapters

Adapters are publishable packages that connect `taskbridge-fastapi` to concrete execution runtimes without leaking vendor-specific behavior into backend core.

## Current adapter

`backend/adapters/temporal`:

- implements the `TaskExecutor` contract using Temporal SDK
- remains independently testable and versionable
- keeps durable workflow state in Temporal while emitting compact transport events through TaskBridge

## Adapter rules

- depend on stable backend extension points
- keep runtime specifics out of `backend/taskbridge-fastapi`
- document health/readiness implications for hosts

## Related docs

- generated [Adapter Python API Reference](../reference/adapters.md)
- [Backend](../backend/index.md) integration model
- [Architecture](../architecture/index.md) for repository layering
