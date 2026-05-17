# TaskBridge

Reliable AI Task Streaming for Android clients and FastAPI hosts.

TaskBridge is a monorepo with three primary publishable surfaces:

- `android/taskbridge-core` for the transport-agnostic Android SDK
- `android/taskbridge-transport-okhttp` for the OkHttp transport adapter
- `backend/taskbridge-fastapi` for FastAPI host applications

Supporting layers stay in the same repository:

- `protocol/` for wire-level contracts
- `backend/adapters/` for backend runtime integrations
- `examples/` for runnable consumer samples
- `docs/` for architecture and contributor guidance

## What this site covers

- integration guidance for Android and backend adopters
- protocol and replay semantics
- generated Android and Python API reference
- architecture decisions and contributor workflows

## Repository map

- Start with [Getting Started](getting-started.md) for local setup and doc commands.
- Go to [Android](android/index.md) for SDK usage and generated API docs.
- Go to [Backend](backend/index.md) for embedding `taskbridge-fastapi`.
- Use [Protocol](protocol/index.md) for wire-contract and replay rules.
- Check [Examples](examples/index.md) for runnable end-to-end setups.
- Use [Documentation](documentation/index.md) for docs generation, preview, and GitHub Pages workflow.

## Out of scope

TaskBridge is intentionally not:

- a generic topic-based pub/sub framework
- a backend-vendor-specific runtime
- a domain workflow engine

Those concerns stay in host apps and adapter packages.
