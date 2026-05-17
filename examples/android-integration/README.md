# Android integration example

This folder documents how to run the **existing** Android sample (`android/sample`)
against the **FastAPI host example** (`examples/fastapi-host`). No duplicate Android
project is checked in here.

## Prerequisites

- FastAPI example running — see [`../fastapi-host/README.md`](../fastapi-host/README.md)
- Android Studio / Gradle JDK aligned with [`android/`](../../android/) toolchain

## Backend URL

| Runtime | Base URL |
|---------|-----------|
| Host machine browser / curl | `http://127.0.0.1:8000` |
| Android Emulator | `http://10.0.2.2:8000` |
| Physical device | `http://<your-LAN-ip>:8000` (same Wi‑Fi, firewall allows port 8000) |

The Compose sample uses a **Base URL** field defaulting to `http://10.0.2.2:8000` — match your setup.

See also [`backend.env.example`](backend.env.example).

## Start backend

From repo root or `examples/fastapi-host`:

```bash
cd examples/fastapi-host
uv sync
uv run uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

Confirm:

```bash
curl -s http://127.0.0.1:8000/health
```

## Start Android sample

Open the Gradle project under [`android/`](../../android/) and run the **`sample`** configuration.

In the sample screen:

1. Set **Base URL** (emulator → `http://10.0.2.2:8000`).
2. Use `taskType` values from the host demo:
   - `demo.success`
   - `demo.fail`
   - `demo.image.analyze` (or use dedicated multipart button)
3. Tap **Start task** or **Start image multipart task** and watch streamed lines
   (`TASK_STARTED`, `TASK_PROGRESS`, terminal event).

## End-to-end scenarios

### A — Happy path (WS primary)

1. Start backend + Android sample with matching base URL.
2. Tap **Start task**.
3. Expect terminal `TASK_COMPLETED`.

The SDK prefers WebSocket and falls back to HTTP polling when the socket fails.

### B — Polling fallback (manual)

1. Same as A, but block WebSockets at the network layer **or** temporarily misconfigure WS URL
   in a fork — the client should still progress via long poll (`GET …/events`).

### C — Replay watermark (Resilience)

1. After receiving events, note `eventId` values in the log.
2. Restart observation with stored checkpoint semantics supported by `TaskBridgeClient`
   (see SDK README under `android/taskbridge-core`). This demonstrates how the client resumes from the last known state.

### D — Multipart image demo

1. Ensure backend runs in temporal mode (`TB_EXECUTOR_MODE=temporal`) and worker is active.
2. In Android sample tap **Start image multipart task**.
3. Verify streamed `TASK_PROGRESS` and terminal `TASK_COMPLETED` payload with attachment summary.

## Auth note

The FastAPI host example uses `DemoAuthResolver` with a fixed `demo-user` and scopes.
The Android sample **does not** attach Bearer tokens yet — compatible with this demo.

For JWT-backed hosts:

1. Implement `AuthContextResolver` on the server.
2. Extend `TaskBridgeConfig` / OkHttp interceptors on Android to send `Authorization`.

See [`docs/security-integration.md`](../../docs/security-integration.md).
