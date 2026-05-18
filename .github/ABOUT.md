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

* **Android:** `android/taskbridge-core`, `android/taskbridge-transport-okhttp` (triggered by `android-v*` tags and published together)
* **Backend / Python:** `backend/taskbridge-fastapi` (triggered by `python-v*` tags)
* **Backend adapter / Python:** `backend/adapters/<adapter>` (triggered by `python-<adapter>-v*` tags, for example `python-temporal-v*`)

Android publication follows the official Sonatype Central Portal flow for Gradle `maven-publish`:
- upload both Android artifacts to the OSSRH compatibility API at `ossrh-staging-api.central.sonatype.com`
- then transfer the deployment to the Central Portal with the documented manual endpoint

The GitHub secret names remain `OSSRH_USERNAME` and `OSSRH_PASSWORD`, but they must contain the **Central Portal token username and password**, not legacy OSSRH credentials.

## Current Workflows
- `backend-ci.yml` — tests FastAPI backend and workers
- `android-ci.yml` — runs Android JVM unit tests
- `protocol-ci.yml` — validates shared protocol contracts
- `docs-site.yml` — builds and deploys MkDocs documentation to GitHub Pages
- `publish-release.yml` — publishes tagged Android and Python releases
- `release-smoke.yml` — validates release packaging and publication guardrails before tags are cut
