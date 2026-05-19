# Adapters

This directory is reserved for publishable adapter packages that integrate `task-bridge` with external execution or infrastructure systems.

Adapters exist so backend core can stay reusable while execution runtime specifics remain isolated.

Rules:

- adapters must depend on stable backend extension points
- adapters must not force vendor-specific behavior into core packages
- adapter versioning should remain independent from backend and Android package versions

## Available adapters

- [`temporal/`](temporal/README.md): `TaskExecutor` implementation backed by Temporal SDK.

## Boundary

Adapters should own:

- runtime client integration;
- executor bridging;
- runtime-specific config and mapping;
- any helper that translates runtime updates into TaskBridge event shapes.

Adapters should not own:

- FastAPI route handling;
- generic backend auth or ownership policy;
- replay or transport semantics already defined by backend core.

Repository-level docs site and generation workflow are documented in `../../docs/documentation/index.md`.

For the concept-level docs, use:

- `../../docs/adapters/index.md`
- `../../docs/adapters/temporal.md`
