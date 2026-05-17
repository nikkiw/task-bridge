# GitHub Automation Plan

This directory is reserved for repository automation and contributor-facing GitHub configuration.

Expected future contents:
- workflow files for backend, Android, and contract validation
- issue templates
- pull request templates
- release workflows for independently versioned packages

## Monorepo CI/CD Strategy
Validation workflows (`-ci.yml`) are required to pass before merging into `main`. To save resources, workflows utilize path filtering (e.g., `dorny/paths-filter`) to only run tests for the specific components that were modified in the Pull Request.

`protocol/`, `docs/`, and `examples/` participate in validation workflows, but they are not publishable packages by default.

## Independent Releases
Release automation treats packages independently. Deployments are triggered by pushing specific git tags:

* **Backend / Python:** `backend/taskbridge-fastapi` (triggered by `pypi-v*` tags)
* **Android:** `android/taskbridge-core`, `android/taskbridge-transport-okhttp` (triggered by `maven-v*` tags)
* *(Future)* adapter packages under `backend/adapters/`

## Current Workflows
- `backend-ci.yml` — tests FastAPI backend and workers
- `android-ci.yml` — runs Android JVM unit tests
- `protocol-ci.yml` — validates shared protocol contracts
- `docs-site.yml` — builds and deploys MkDocs documentation to GitHub Pages
