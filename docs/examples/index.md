# Examples

TaskBridge keeps runnable examples outside publishable packages.

## Included examples

`examples/fastapi-host/`

- minimal FastAPI host wiring
- fake registry and event-store components
- `DeterministicFakeExecutor` for local demos and tests

`examples/android-integration/`

- shows how to point `android/sample` at a running backend
- demonstrates the end-to-end integration path rather than a separate SDK sample app

## When to use examples

- validate wiring before integrating into a real host app
- reproduce protocol or transport behavior locally
- smoke-test Android and backend changes together
