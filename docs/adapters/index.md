# Adapters

Adapters are publishable packages that connect `taskbridge-fastapi` to concrete execution runtimes without leaking vendor-specific behavior into backend core.

Use adapters when your host wants to keep TaskBridge transport semantics stable while delegating durable workflow execution to a runtime such as Temporal.

## What adapters own

Adapters are responsible for:

- implementing stable backend extension points such as `TaskExecutor`;
- mapping host task records into runtime-specific workflow input;
- translating runtime updates into TaskBridge event shapes when needed;
- documenting runtime-specific readiness and operational behavior.

Adapters are not responsible for:

- redefining the core backend contracts;
- replacing route builders or transport loops from `taskbridge-fastapi`;
- pushing vendor-specific types into the backend core package.

## Current adapter

The current concrete adapter is `backend/adapters/temporal`.

It:

- implements the `TaskExecutor` contract using Temporal SDK;
- stays independently testable and publishable;
- keeps durable orchestration state in Temporal while leaving client-facing event transport in TaskBridge.

## Adapter boundary

```mermaid
flowchart LR
    BackendCore[taskbridge-fastapi core] --> Contract[TaskExecutor contract]
    Contract --> Adapter[Temporal adapter]
    Adapter --> Runtime[Temporal runtime]
    Runtime --> HostState[Durable workflow state]
```

## What to read next

- [Temporal Adapter](temporal.md)
  The main concrete example of how an adapter plugs into backend core.
- [Backend](../backend/index.md)
  For host-owned wiring and transport/service boundaries.

## Related docs

- [Adapter Python API Reference](../reference/adapters.md)
- [Backend](../backend/index.md)
- [Architecture](../architecture/index.md)
