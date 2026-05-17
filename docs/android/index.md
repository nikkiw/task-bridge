# Android SDK

The TaskBridge Android SDK provides a reliable, `Flow`-based interface for interacting with the TaskBridge backend. It handles task lifecycle, binary uploads, and event streaming with automatic recovery.

## Modular Architecture

The SDK is divided into two main artifacts to keep the core dependency-light:

- `taskbridge-core`: API definitions, models, state management, and the base client. Depends on `kotlinx-serialization` and `androidx-datastore`.
- `taskbridge-transport-okhttp`: The default transport implementation using OkHttp, WebSockets, and SSE.

## Installation

Add the dependencies to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.github.nikkiw:taskbridge-core:VERSION")
    implementation("io.github.nikkiw:taskbridge-transport-okhttp:VERSION")
}
```

## Core Concepts

### TaskBridgeClient

The main entry point. Created via `TaskBridgeClient.create(config)`.

### Event Streaming

Use `observeTaskEvents(taskId)` to get a `Flow<TaskEvent>`. The SDK handles:
- **Resilience**: Automatic reconnection on network failure.
- **Checkpointing**: Resuming from the last seen event ID using a persistent store.
- **Deduplication**: Ensuring events are processed exactly once.

### Task Lifecycle

1. **Start**: `startTaskJson` or `startTaskMultipart`.
2. **Observe**: `observeTaskEvents`.
3. **Interact**: Respond to `TaskSuspendedEvent` using `submitAction`.
4. **Complete**: Handle terminal events like `TaskCompletedEvent` or `TaskFailedEvent`.

## Build and Validation

Run from `android/`:

```bash
./gradlew test
./gradlew dokkaGenerateMultiModuleHtml
```

## Related Docs

- generated [Android API Reference](../reference/android.md)
- repo-level [Protocol](../protocol/index.md) contract
- [Examples](../examples/index.md) for end-to-end validation against a backend
- ADR 0005 summary in [ADR](../adr/index.md)
