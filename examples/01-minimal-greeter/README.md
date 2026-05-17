# 01. Minimal Greeter Example

This is the simplest possible TaskBridge implementation. It demonstrates the full lifecycle of an asynchronous task without requiring any external infrastructure like Redis or Temporal.

## What's Inside?

1.  **Standalone Backend (`app.py`)**: A FastAPI server that implements:
    *   **In-Memory Event Store**: Uses Python lists and `asyncio.Condition` for reactive event streaming.
    *   **Fake Executor**: Implements the `greeter` task that yields progress ticks.
    *   **JSON API**: Standard endpoints for starting tasks and polling events.
2.  **Android Integration**: Works out-of-the-box with the `sample` app in this repository.

## How to Run

### 1. Start the Backend
Make sure you have `uv` installed, then run:

```bash
uv run app.py
```

*Note: If you don't use `uv`, just install `fastapi`, `uvicorn`, and the local `taskbridge-fastapi` package.*

### 2. Connect the Android Emulator
To allow the Android emulator to talk to your local machine's port 8000:

```bash
adb reverse tcp:8000 tcp:8000
```

### 3. Run the Android App
1.  Open the `android` project in Android Studio.
2.  Run the `sample` app.
3.  Choose **"01. Minimal Greeter"** on the home screen.
4.  Enter your name and press **"Greet Me!"**.

## Key Concepts Demonstrated

*   **Asynchronous Lifecycle**: `ACCEPTED` -> `RUNNING` -> `COMPLETED`.
*   **Reactive Streaming**: Events appear in the Android app the moment they are generated on the server (no busy-waiting).
*   **Idempotency**: Try starting the same task again; TaskBridge handles deduplication.
