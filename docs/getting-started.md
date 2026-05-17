# Getting Started

## Prerequisites

- Python 3.11+
- `uv`
- JDK 17 for Android/Dokka generation
- Android SDK when rebuilding Android API docs

## Local documentation workflow

The canonical docs workflow is in [Documentation Guide](documentation/index.md).

Repository-managed environment:

```bash
uv sync --group docs --group dev
uv run python scripts/docs_prepare.py
uv run mkdocs serve
```

Ad-hoc `uvx` preview:

```bash
python scripts/docs_prepare.py
uvx --with mkdocs-material --with mkdocstrings-python --with pymdown-extensions mkdocs serve
```

Strict static build:

```bash
uv sync --group docs --group dev
uv run python scripts/docs_prepare.py
uv run mkdocs build --strict
```

## Package-level quick start

Backend checks:

```bash
cd backend/taskbridge-fastapi
uv sync --group dev
uv run ruff check
uv run ruff format --check
uv run pytest
```

Android checks:

```bash
cd android
./gradlew :taskbridge-core:testDebugUnitTest
./gradlew :taskbridge-transport-okhttp:testDebugUnitTest
./gradlew dokkaGeneratePublicationHtml
```

Protocol validation:

```bash
uv run python protocol/validate_protocol.py
```

## Documentation ownership

- generated Android reference comes from KDoc via Dokka
- generated Python reference comes from `mkdocstrings`
- hand-written pages in `docs/` explain integration, architecture, and contributor workflows
