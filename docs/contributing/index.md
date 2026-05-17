# Contributing

## Repository conventions

- treat publishable packages independently
- keep wire-contract changes aligned with `protocol/`
- update documentation when public APIs or integration semantics change
- keep runtime-specific behavior in `backend/adapters/` or host apps, not backend core

## Documentation workflow

The canonical operational guide lives in [Documentation](../documentation/index.md).

When changing docs site content:

```bash
uv sync --group docs --group dev
uv run python scripts/docs_prepare.py
uv run mkdocs build --strict
```

When changing Android public API:

- update KDoc so Dokka output remains usable
- rebuild docs site assets with `scripts/docs_prepare.py`

When changing backend or adapter public APIs:

- keep reference pages rendering under `mkdocstrings`
- update curated integration pages if host behavior changes

When changing package README files:

- keep them short
- point readers to the repository docs site and documentation guide
- avoid copying the full command set into every package document

## Existing contributor docs

- root `README.md`
- `CONTRIBUTING.md`
- `docs/index.md`
- `docs/documentation/index.md`
