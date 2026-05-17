# Contributing

## Repository Model

`task-bridge` is an open-source GitHub monorepo with multiple independently versioned publishable packages.

Publishable packages:

- `backend/taskbridge-fastapi`
- `android/taskbridge-core`
- `android/taskbridge-transport-okhttp`
- future adapter packages under `backend/adapters/`

Repository support layers:

- `protocol/` for internal contract definitions and fixtures
- `docs/` for ADRs, integration docs, and contributor guidance
- `examples/` for runnable demos and integration references

## Versioning

The repository uses independent versioning.

- Backend package versions are owned by `backend/taskbridge-fastapi`
- Android package versions are owned by `android/taskbridge-core` and `android/taskbridge-transport-okhttp`
- Future adapter versions are owned by their adapter directories
- `protocol/` is not a standalone package dependency; it defines the shared contract used by publishable packages

## Contribution Scope

Good contributions usually do one of these:

- improve the backend package
- improve the Android SDK
- improve contract docs, schemas, or examples
- add tests, examples, and release tooling
- add adapters without leaking vendor-specific behavior into core packages

Avoid mixing unrelated concerns in one change.

## Before Opening a Change

1. Read [README.md](README.md)
2. Read [docs/index.md](docs/index.md)
3. Read [docs/documentation/index.md](docs/documentation/index.md)
4. If your change affects transport semantics, also read:
   - [protocol/README.md](protocol/README.md)
   - [docs/adr/0001-backend-stack.md](docs/adr/0001-backend-stack.md)
   - [docs/adr/0002-android-networking.md](docs/adr/0002-android-networking.md)
   - [docs/adr/0003-streaming-protocol.md](docs/adr/0003-streaming-protocol.md)

## Local Workspace Workflow

For parallel work or isolated feature development, prefer git worktrees instead of switching the main working tree back and forth.

Repository convention:

- use `.worktrees/` at the repository root
- create one branch per focused task
- keep the main checkout clean for review, release, or emergency fixes

Example:

```bash
git worktree add .worktrees/backend-core -b feat/backend-core
git worktree add .worktrees/android-sdk -b feat/android-sdk
```

Because `.worktrees/` is ignored at the repository root, these worktrees stay out of normal git status output.

## Engineering Conventions

Backend conventions:

- use `uv` for environment and dependency management
- use `ruff` for linting and formatting
- use `pytest` for verification
- prefer explicit models and deterministic behavior

Android conventions:

- keep public APIs small and stable
- prefer `Flow<TaskEvent>` as the public streaming surface
- do not close the public flow on temporary network loss

Cross-cutting rules:

- update tests with behavior changes
- update `protocol/` when public transport contracts change
- update ADRs when architecture changes
- keep examples aligned with actual package behavior

## Documentation Workflow

Repository documentation is built as a MkDocs site with generated Android and Python reference.

Canonical workflow:

```bash
uv sync --group docs --group dev
uv run python scripts/docs_prepare.py
uv run mkdocs build --strict
```

For local preview:

```bash
uv run mkdocs serve
```

For ad-hoc preview without the repo-managed MkDocs environment:

```bash
python scripts/docs_prepare.py
uvx --with mkdocs-material --with mkdocstrings-python --with pymdown-extensions mkdocs serve
```

Documentation responsibilities:

- `docs/` holds curated hand-written pages and MkDocs content
- `scripts/docs_prepare.py` regenerates and stages Android Dokka output
- Python API reference is rendered through `mkdocstrings`
- package README files should stay short and point to the repo docs site instead of duplicating the full docs workflow

When public APIs or integration semantics change, update the docs in the same change.
Use `docs/documentation/index.md` as the source of truth for docs maintenance details.

## Pull Request Expectations

A good pull request should:

- explain which package or layer it changes
- mention whether it changes the public contract
- include verification notes
- mention any follow-up work left intentionally out of scope

## Licensing

The repository should include an explicit OSS license before public publication.
Until that is finalized, avoid assuming inbound contribution terms beyond standard repository review.
