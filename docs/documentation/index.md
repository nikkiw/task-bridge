# Documentation Guide

This page is the canonical workflow for building and updating the TaskBridge documentation site.

## What the docs stack contains

- `mkdocs.yml` defines the repository site and navigation
- `docs/` contains curated hand-written guides
- `scripts/docs_prepare.py` stages generated assets before MkDocs builds
- `docs/llms.txt` is generated as a curated LLM entrypoint and published at the site root
- `docs/reference/android-ref/` is generated from Dokka multi-module output and stays untracked
- `docs/reference/backend-api/` contains generated OpenAPI documentation and stays untracked
- Python API reference pages use `mkdocstrings` against repository modules

## Local workflows

### Managed environment

Use this when you work on docs repeatedly or want the same flow as CI:

```bash
uv sync --group docs --group dev
uv run python scripts/docs_prepare.py
uv run mkdocs serve
```

### Ad-hoc preview with `uvx`

Use this when you want a fast local preview without relying on the repo-managed MkDocs environment:

```bash
python scripts/docs_prepare.py
uvx --with mkdocs-material --with mkdocstrings-python --with pymdown-extensions mkdocs serve
```

`scripts/docs_prepare.py` still runs locally because it is repository code, not a bundled `uvx` tool.

### Strict static build

Run this before finalizing docs changes:

```bash
uv sync --group docs --group dev
uv run python scripts/docs_prepare.py
uv run mkdocs build --strict
```

## What `scripts/docs_prepare.py` does

Default behavior:

1. runs `./gradlew dokkaGeneratePublicationHtml` in `android/`
2. reads Dokka HTML from `android/build/dokka/html/`
3. replaces the staged subtree under `docs/reference/android-ref/`
4. copies `protocol/openapi/taskbridge.openapi.yaml` to `docs/reference/backend-api/openapi.yaml`
5. generates a Redoc `index.html` under `docs/reference/backend-api/`
6. writes a curated `docs/llms.txt` index for LLM-oriented consumption

Useful modes:

```bash
uv run python scripts/docs_prepare.py --skip-build
uv run python scripts/docs_prepare.py --dokka-source /custom/path
uv run python scripts/docs_prepare.py --destination-android docs/reference/android-ref
```

Use `--skip-build` when generated outputs are already fresh and you only want to restage them.
The `llms.txt` output is still regenerated in `--skip-build` mode so summaries and curated links stay in sync with the docs workflow.

## When docs must be updated

### If Android public API changes

- update KDoc in `android/taskbridge-core` and `android/taskbridge-transport-okhttp` as needed
- run `scripts/docs_prepare.py`
- check `docs/android/index.md` and any guide text that describes public behavior

### If backend or adapter public API changes

- keep `mkdocstrings` pages rendering
- update `docs/backend/index.md`, `docs/adapters/index.md`, and related guides if integration behavior changed

### If protocol or transport semantics change

- update `protocol/`
- update `docs/protocol/index.md`
- update relevant ADRs or architecture pages when the semantic change is architectural

### If contributor workflow changes

- update this page first
- refresh the curated `llms.txt` content if the best entrypoints for integrators changed
- then update short entrypoints in `README.md`, `CONTRIBUTING.md`, or package README files only as pointers

## GitHub Pages and CI

The repository workflow is `.github/workflows/docs-site.yml`.

It does the following:

1. installs Java, Android SDK, Python, and `uv`
2. syncs repo docs dependencies
3. runs `pytest` for `tests/test_docs_prepare.py`
4. runs `scripts/docs_prepare.py`
5. runs `mkdocs build --strict`
6. uploads the Pages artifact
7. deploys on pushes to `main`

## Editing policy

- keep package README files short and link back to this guide
- keep operational details here instead of duplicating them in many places
- do not publish internal planning notes in site navigation
- keep generated Android HTML out of version control
