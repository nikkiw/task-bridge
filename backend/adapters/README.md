# Adapters

This directory is reserved for publishable adapter packages that integrate `task-bridge` with external execution or infrastructure systems.

Examples of backend adapter packages:

- `backend/adapters/temporal`
- `backend/adapters/celery`

Rules:

- adapters must depend on stable backend extension points
- adapters must not force vendor-specific behavior into core packages
- adapter versioning should remain independent from backend and Android package versions

## Available adapters

- [`temporal/`](temporal/README.md): `TaskExecutor` implementation backed by Temporal SDK.

Repository-level docs site and generation workflow are documented in `../../docs/documentation/index.md`.
