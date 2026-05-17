# GitHub Automation Plan

This directory is reserved for repository automation and contributor-facing GitHub configuration.

Expected future contents:

- workflow files for backend, Android, and contract validation
- issue templates
- pull request templates
- release workflows for independently versioned packages

Release automation should treat packages independently:

- `backend/taskbridge-fastapi`
- `android/taskbridge-core`
- `android/taskbridge-transport-okhttp`
- future adapter packages under `backend/adapters/`

`protocol/`, `docs/`, and `examples/` should participate in validation workflows, but they are not publishable packages by default.

## Current workflows

- `workflows/backend-ci.yml`
- `workflows/android-ci.yml`
- `workflows/protocol-ci.yml`
