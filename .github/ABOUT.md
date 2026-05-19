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

Package-level changelog ownership:

- `android/CHANGELOG.md`
- `backend/taskbridge-fastapi/CHANGELOG.md`
- `backend/adapters/temporal/CHANGELOG.md`

The root `CHANGELOG.md` is only an index. Package changelog sections are the source of truth for GitHub Release notes.

## Release Contract

Release-bearing changes must use Conventional Commits and squash merge so the final commit on `main` stays machine-readable.

Accepted title shapes include:

- `feat(android): ...`
- `fix(backend): ...`
- `docs(android): ...`
- `feat(temporal)!: ...`

Breaking changes are recognized only by `!` after the type or scope, or by a `BREAKING CHANGE:` footer.

Release flow:

1. Run `prepare-release` with `component` and `version`.
2. Merge the generated `chore(release): prepare <component> vX.Y.Z` PR.
3. Push the matching tag: `android-vX.Y.Z`, `python-vX.Y.Z`, or `python-temporal-vX.Y.Z`.
4. `publish-release.yml` publishes artifacts and creates or updates the matching GitHub Release from the package changelog section.

Android publication follows the official Sonatype Central Portal flow for Gradle `maven-publish`:
- upload both Android artifacts to the OSSRH compatibility API at `ossrh-staging-api.central.sonatype.com`
- then transfer the deployment to the Central Portal with the documented manual endpoint

The GitHub secret names remain `OSSRH_USERNAME` and `OSSRH_PASSWORD`, but they must contain the **Central Portal token username and password**, not legacy OSSRH credentials.

## Current Workflows
- `backend-ci.yml` — tests FastAPI backend and workers
- `android-ci.yml` — runs Android JVM unit tests
- `protocol-ci.yml` — validates shared protocol contracts
- `docs-site.yml` — builds and deploys MkDocs documentation to GitHub Pages
- `release-contract.yml` — validates the Conventional Commits release contract on PRs and `main`
- `prepare-release.yml` — generates package-scoped changelog entries and opens release prep PRs
- `publish-release.yml` — publishes tagged Android and Python releases
- `release-smoke.yml` — validates release packaging and publication guardrails before tags are cut
