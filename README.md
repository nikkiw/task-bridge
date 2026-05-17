# TaskBridge

Reliable AI Task Streaming library for Android and FastAPI.

## Overview

TaskBridge provides a reliable infrastructure layer for long-running AI tasks. It handles task initiation, file uploads, and event streaming with built-in recovery and fallback mechanisms.

## Project Structure

- `android/`: Android Kotlin library and samples.
- `backend/`: FastAPI backend implementation and worker stubs.
- `protocol/`: Shared API contracts (OpenAPI, JSON Schemas).
- `adapters/`: Future publishable integration packages for execution backends.
- `examples/`: Runnable **consumer** examples (see [`examples/README.md`](examples/README.md)).
  - [`examples/fastapi-host/`](examples/fastapi-host/README.md) — minimal FastAPI host wiring `taskbridge-fastapi`.
  - [`examples/android-integration/`](examples/android-integration/README.md) — run `android/sample` against that host.
- `docs/`: Roadmap, ADRs, plans, and contributor-facing process notes.
- `.ai/`: Early architectural notes kept for internal drafting.

## Core Features

- **Reliable Streaming**: WebSocket-first event streaming with replay and deduplication by `eventId`.
- **Durable Recovery**: Android recovery and failover resume from the last persisted checkpoint, not transient in-memory cursors.
- **Fallback Support**: Transparently switches to long polling if WebSockets are unavailable; network replay may repeat already-seen events after failure, but the public `Flow` remains deduplicated.
- **Idempotency**: Prevents duplicate tasks via `clientRequestId`.
- **Flow-based API**: Native Kotlin Coroutines `Flow` for easy Android integration.

## Backend Engineering Baseline

The Python backend should follow the same practical conventions used across the portfolio's modern Python services:

- `uv` for environment and dependency management
- `ruff` for linting and formatting
- `pytest` for unit and integration tests
- typed Pydantic models and explicit config boundaries
- structured logs, health/readiness checks, and CI-friendly commands
- Docker-friendly local setup and reproducible developer workflows

## Out of Scope for v1

- Domain-specific workflow logic
- Generic topic-based pub/sub
- Backend-vendor lock-in inside the core package
- A Kotlin Multiplatform API guarantee

## Monorepo Model

`task-bridge` is a GitHub monorepo with independently versioned publishable packages.

Publishable packages:

- `backend/taskbridge-fastapi`
- `android/taskbridge-core`
- `android/taskbridge-transport-okhttp`
- future adapter packages under `adapters/`

Repository support layers:

- `protocol/`
- `docs/`
- `examples/`

## Documentation

Repository documentation now has a MkDocs site at `docs/site/` with generated API reference.

Primary commands:

- `uv sync --group docs --group dev`
- `uv run python scripts/docs_prepare.py`
- `uv run mkdocs serve`

For `uvx`, strict build, Dokka staging behavior, and GitHub Pages workflow, use the canonical guide at `docs/site/documentation/index.md`.

Source material still lives in the repository:

- [.ai/ARCHITECTURE.md](.ai/ARCHITECTURE.md) for earlier deep-dive notes
- [docs/ROADMAP.md](docs/ROADMAP.md) for development phases and backlog
- [docs/plans/README.md](docs/plans/README.md) for internal execution tracking
- [protocol/README.md](protocol/README.md) for wire-level compatibility
- [CONTRIBUTING.md](CONTRIBUTING.md) for contributor guidance
- [docs/site/documentation/index.md](docs/site/documentation/index.md) for docs generation and maintenance workflow

For isolated parallel work, the repository convention is to use git worktrees under `.worktrees/`.

## Phase 11 CI/Test Matrix Commands

From repository root:

- Backend checks:
  - `cd backend/taskbridge-fastapi && uv sync --group dev`
  - `uv run ruff check && uv run ruff format --check && uv run pytest`
- Android unit checks:
  - `cd android && ./gradlew test`
- Protocol contract checks:
  - `python protocol/validate_protocol.py`
