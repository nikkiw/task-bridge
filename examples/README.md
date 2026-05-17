# TaskBridge examples

Runnable integration examples for adopters. These live outside publishable packages
(`backend/taskbridge-fastapi`, `android/taskbridge-core`, `android/taskbridge-transport-okhttp`).

## Contents

| Path | Purpose |
|------|---------|
| [`01-minimal-greeter/`](01-minimal-greeter/README.md) | The simplest possible TaskBridge implementation (standalone Python script). Great starting point to understand the lifecycle and reactive streaming without complex infrastructure. |
| [`fastapi-host/`](fastapi-host/README.md) | Enterprise-grade FastAPI app demonstrating Redis event streaming, Temporal task execution, Firebase Auth, and Human-in-the-loop (suspensions). |
| [`android-integration/`](android-integration/README.md) | How to point the existing [`android/sample`](../android/sample) module at the `fastapi-host` and run end-to-end checks (WebSocket, Polling fallback, Multipart uploads). |

## Relationship to `android/`

The **Android SDK** and its **in-repo sample app** stay under [`android/`](../android/):
library code in `taskbridge-core` and `taskbridge-transport-okhttp`, demo UI in `android/sample`.

`examples/android-integration/` only documents **consumer wiring** against a real backend (this repo’s FastAPI example or your own host), without duplicating the sample project.

For the unified repository docs site and generation workflow, see `../docs/documentation/index.md`.
