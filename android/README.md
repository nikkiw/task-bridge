# TaskBridge — Android (`android/`)

A Gradle project containing two publishable Android libraries:

- `taskbridge-core`
- `taskbridge-transport-okhttp`

And a `sample` module.

Versions for AGP, Kotlin, Compose BOM, and dependencies are defined in `gradle/libs.versions.toml`.

General build and quality policy: [ADR 0005: Android toolchain](../docs/adr/0005-android-toolchain-build-quality.md).

## Installation

Add the following to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.github.nikkiw.taskbridge:taskbridge-core:VERSION")
    // If you want to use the OkHttp-based transport
    implementation("io.github.nikkiw.taskbridge:taskbridge-transport-okhttp:VERSION")
}
```

Check the latest version on [Maven Central](https://central.sonatype.com/search?q=io.github.nikkiw.taskbridge).

Release notes for both Android artifacts live in [`android/CHANGELOG.md`](CHANGELOG.md). Android releases are prepared through the repository `prepare-release` workflow and published when an `android-vX.Y.Z` tag is pushed.

## Requirements

- `JDK 17`
- Android SDK version at least `compileSdk` from the version catalog (Min SDK `24`)
- `local.properties` in `android/` with `sdk.dir=...`

## Quick Start

From the `android/` directory:

```bash
./gradlew check
```

Verified with the current modular structure: `taskbridge-core`, `taskbridge-transport-okhttp`, `sample`.

## Maven Artifacts

The public publication is now modular:

- `io.github.nikkiw.taskbridge:taskbridge-core`
- `io.github.nikkiw.taskbridge:taskbridge-transport-okhttp`

Release-bearing PR titles and squash titles must follow Conventional Commits such as `feat(android): ...` or `fix(android): ...`.

`taskbridge-core` does not depend on `okhttp`, `okhttp-sse`, or Retrofit in main code.
`taskbridge-transport-okhttp` is the only transport adapter using OkHttp/WebSocket/SSE/Retrofit wiring.

## Main Commands

All commands are executed from the `android/` directory.

| Command | Purpose |
|---|---|
| `./gradlew check` | Full run of checks across all Android modules. |
| `./gradlew test` | Unit tests for all modules. |
| `./gradlew :taskbridge-core:testDebugUnitTest` | Tests for the core module only. |
| `./gradlew :taskbridge-transport-okhttp:testDebugUnitTest` | Tests for the OkHttp transport adapter. |
| `./gradlew :sample:assembleDebug` | Build the debug APK for the sample application. |
| `./gradlew spotlessCheck` | Check Kotlin formatting. |
| `./gradlew spotlessApply` | Auto-format Kotlin code. |
| `./gradlew detekt` | Static analysis for Kotlin. |
| `./gradlew lint` / `./gradlew lintDebug` | Android Lint. |
| `./gradlew :taskbridge-core:generateLicenseReport` | License report for core. |
| `./gradlew :taskbridge-transport-okhttp:generateLicenseReport` | License report for the OkHttp adapter. |
| `./gradlew :taskbridge-core:dokkaGeneratePublicationHtml` | HTML documentation for core. |
| `./gradlew :taskbridge-transport-okhttp:dokkaGeneratePublicationHtml` | HTML documentation for the OkHttp adapter. |

## Directory Structure

| Path | Role |
|---|---|
| `build-logic/` | Convention plugins for Android/library/quality/publish. |
| `gradle/libs.versions.toml` | Version catalog. |
| `taskbridge-core/` | Transport-agnostic SDK core: API, models, checkpoint, retry, state machine. |
| `taskbridge-transport-okhttp/` | OkHttp transport adapter: HTTP, WebSocket, SSE, Retrofit wiring. |
| `sample/` | Minimal Compose application for manual verification. |
| `config/detekt/detekt.yml` | Detekt configuration. |
| `config/license/allowed-licenses.json` | Allowed dependency licenses. |
| `config/spotless/copyright.kt` | License header for Spotless. |

## Public SDK API

The main entry point is `TaskBridgeClient`, with a transport-agnostic configuration.

Core surface:

- `startTaskJson(request)`
- `startTaskMultipart(clientRequestId, taskType, inputJson, metadataJson, attachments)`
- `observeTaskEvents(taskId, lastEventId)`
- `cancelTask(taskId, reason)`
- `submitAction(taskId, action)`

`startTaskMultipart(...)` no longer requires `MultipartBody.Part`; use `TaskBridgeMultipartAttachment`.

`observeTaskEvents(...)` returns `Flow<TaskEvent>`.

Known events:

- `TaskStartedEvent`
- `TaskProgressEvent`
- `TaskMessageEvent`
- `TaskSuspendedEvent`
- `TaskActionAcceptedEvent`
- `TaskCompletedEvent`
- `TaskFailedEvent`
- `TaskCancelledEvent`
- `UnknownTaskEvent`

Example with OkHttp transport adapter:

```kotlin
val client =
    TaskBridgeClient.create(
        TaskBridgeConfig(
            baseUrl = "http://10.0.2.2:8000",
            transportFactory =
                OkHttpTaskBridgeTransportFactory(
                    OkHttpTaskBridgeTransportConfig(
                        okHttpClient = OkHttpClient(),
                    ),
                ),
        ),
    )

val created =
    client.startTaskJson(
        TaskCreateJsonRequest(
            clientRequestId = "req-1",
            taskType = "demo.echo",
        ),
    )

client.observeTaskEvents(created.taskId).collect { event ->
    when (event) {
        is TaskProgressEvent -> println(event.payload)
        is TaskSuspendedEvent -> {
            // Task is waiting for user action. Show UI for the required interaction.
            val suspendId = event.suspension.suspendId
            println("Task suspended: ${event.suspension.kind}")
        }
        is TaskActionAcceptedEvent -> {
            println("Action accepted: ${event.accepted.clientActionId}")
        }
        is TaskCompletedEvent -> println(event.payload)
        is UnknownTaskEvent -> println(event.wireType)
        else -> Unit
    }
}
```

### Submitting Actions

When a task is `TASK_SUSPENDED`, use `submitAction` to resume:

```kotlin
val actionResponse = client.submitAction(
    taskId = taskId,
    action = TaskActionRequest(
        clientActionId = UUID.randomUUID().toString(),
        suspendId = suspendId,
        actionType = "confirm",
        payload = JsonObject(mapOf("confirmed" to JsonPrimitive(true)))
    )
)

if (actionResponse.status == SubmitActionStatus.ACCEPTED) {
    println("Action submitted successfully")
}
```

Multipart example:

```kotlin
val attachmentBytes = byteArrayOf(1, 2, 3)

val response =
    client.startTaskMultipart(
        clientRequestId = "req-2",
        taskType = "demo.image",
        inputJson = """{"source":"android"}""",
        metadataJson = null,
        attachments =
            listOf(
                TaskBridgeMultipartAttachment(
                    fileName = "sample.bin",
                    contentType = "application/octet-stream",
                    content = attachmentBytes,
                ),
            ),
    )
```

Important boundary:

- `TaskBridgeMultipartAttachment` is the public SDK boundary, not `MultipartBody.Part`;
- the current API accepts attachment content as `ByteArray`;
- this is appropriate when the app already has the bytes in memory;
- this is not a streaming upload API for arbitrarily large files.

## Resilience Model

- Precedence for resume:
  - Explicit `lastEventId`
  - Persisted checkpoint
  - `null`
- Checkpoint is updated after every emitted task event.
- Terminal events clear the checkpoint.
- Transport heartbeats do not advance the checkpoint.
- Retryable transport failures keep the `Flow` alive and restart the observation loop with backoff.
- Terminal auth/protocol failures terminate the `Flow` with an error.

If the host app does not provide its own store in `TaskBridgeConfig`, `InMemoryTaskBridgeCheckpointStore` is used.
Persistent adapter: `DataStoreTaskBridgeCheckpointStore`.

## Before Committing

Recommended run:

```bash
./gradlew spotlessApply detekt spotlessCheck check
```

## Related Documents

- [ADR 0005 — toolchain, Spotless, Detekt, Dokka, sample](../docs/adr/0005-android-toolchain-build-quality.md)
- [docs/android/index.md](../docs/android/index.md) for the human-readable Android guide
