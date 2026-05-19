# Production-Ready Android Setup

This guide shows how to wire the current TaskBridge Android SDK into a production-style app architecture.

It focuses on four concerns:

- authenticated networking;
- custom backend routing;
- durable stream resume across process death;
- UI state driven from a local database rather than directly from the network stream.

Use this page together with:

- [Client and Config](client-config.md)
- [Events and Recovery](events-and-recovery.md)
- [Storage and Policies](storage-and-policies.md)

## 1. Build the client around `TaskBridgeConfig`

The current SDK is configured through `TaskBridgeConfig`, not through per-call URLs or a separate `OkHttpTransport` object.

A production baseline usually includes:

- `OkHttpTaskBridgeTransportFactory`
- `authHeaderProvider`
- a persistent `TaskBridgeCheckpointStore`
- a custom `TaskBridgeRouteResolver` if backend paths differ from defaults

## 2. Authentication and logging

The recommended auth boundary is `authHeaderProvider`.

Why:

- it is part of the public SDK config;
- it supports `forceRefresh` after a `401`;
- the OkHttp transport integrates it directly for HTTP, WS, and SSE requests.

You can still pass a preconfigured `OkHttpClient` for logging, DNS, certificate pinning, or other host-level networking concerns.

```kotlin
import io.github.nikkiw.taskbridge.api.TaskBridgeClient
import io.github.nikkiw.taskbridge.api.TaskBridgeConfig
import io.github.nikkiw.taskbridge.checkpoint.DataStoreTaskBridgeCheckpointStore
import io.github.nikkiw.taskbridge.okhttp.OkHttpTaskBridgeTransportConfig
import io.github.nikkiw.taskbridge.okhttp.OkHttpTaskBridgeTransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File

val loggingInterceptor =
    HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

val okHttpClient =
    OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .build()

val checkpointStore =
    DataStoreTaskBridgeCheckpointStore(
        file = File(filesDir, "taskbridge-checkpoints.preferences_pb"),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )
```

`HttpLoggingInterceptor.Level.BODY` can be useful during local debugging, but in production it is usually too noisy and risky for payload logging.

## 3. Custom routing with `TaskBridgeRouteResolver`

If your backend uses custom paths such as `/api/v1/projects/{projectId}/tasks`, the current SDK customization point is `TaskBridgeRouteResolver`.

Do not try to pass explicit `wsUrl`, `sseUrl`, or `pollingUrl` per `observeTaskEvents(...)` call. That is not how the current API works.

```kotlin
import io.github.nikkiw.taskbridge.api.TaskBridgeRouteResolver

data class ProjectTaskContext(
    val projectId: String,
    val bearerToken: String,
)

class ProjectRouteResolver : TaskBridgeRouteResolver<ProjectTaskContext> {
    override fun createTaskPath(context: ProjectTaskContext): String =
        "api/v1/projects/${context.projectId}/tasks"

    override fun pollEventsPath(
        context: ProjectTaskContext,
        taskId: String,
    ): String = "api/v1/projects/${context.projectId}/tasks/$taskId/events"

    override fun cancelTaskPath(
        context: ProjectTaskContext,
        taskId: String,
    ): String = "api/v1/projects/${context.projectId}/tasks/$taskId/cancel"

    override fun submitActionPath(
        context: ProjectTaskContext,
        taskId: String,
    ): String = "api/v1/projects/${context.projectId}/tasks/$taskId/actions"

    override fun webSocketPath(context: ProjectTaskContext): String =
        "api/v1/projects/${context.projectId}/tasks/ws"

    override fun streamEventsPath(
        context: ProjectTaskContext,
        taskId: String,
    ): String = "api/v1/projects/${context.projectId}/tasks/$taskId/events/stream"
}
```

This mirrors the backend route scheme at one config boundary instead of scattering URL knowledge across every call site.

## 4. Full production client example

```kotlin
val client =
    TaskBridgeClient.create(
        TaskBridgeConfig(
            baseUrl = "https://api.yourdomain.com",
            transportFactory =
                OkHttpTaskBridgeTransportFactory<ProjectTaskContext>(
                    OkHttpTaskBridgeTransportConfig(
                        okHttpClient = okHttpClient,
                    ),
                ),
            routeResolver = ProjectRouteResolver(),
            checkpointStore = checkpointStore,
            checkpointNamespace = "project-tasks",
            authHeaderProvider = { context, forceRefresh ->
                val token =
                    if (forceRefresh) {
                        sessionManager.refreshToken(context.projectId)
                    } else {
                        sessionManager.getToken(context.projectId)
                    }
                token?.let { "Bearer $it" }
            },
        ),
    )
```

This gives you:

- one place to customize auth;
- one place to customize backend route layout;
- persistent stream resume after app restart;
- the standard OkHttp transport stack with WS -> SSE -> polling fallback.

## 5. Starting and observing a task

With a custom context type, the context travels through every request:

```kotlin
val context =
    ProjectTaskContext(
        projectId = "proj_123",
        bearerToken = "unused-here-if-session-manager-is-source-of-truth",
    )

val created =
    client.startTaskJson(
        context = context,
        request =
            TaskCreateJsonRequest(
                clientRequestId = "req-123",
                taskType = "document.analysis",
                input =
                    buildJsonObject {
                        put("documentId", "doc_456")
                    },
            ),
    )

client.observeTaskEvents(
    context = context,
    taskId = created.taskId,
).collect { event ->
    when (event) {
        is TaskProgressEvent -> println("Progress: ${event.payload}")
        is TaskSuspendedEvent -> println("Needs action: ${event.suspension.kind}")
        is TaskCompletedEvent -> println("Completed")
        is TaskFailedEvent -> println("Failed: ${event.payload}")
        else -> Unit
    }
}
```

## 6. Local database as UI source of truth

For production apps, the UI should usually observe local state rather than collect the raw TaskBridge `Flow` directly.

That means:

- a repository or worker collects TaskBridge events;
- the repository persists or projects them into Room;
- the UI observes Room-backed state.

This is still the right architecture for UX and app structure.

### Background sync shape

```kotlin
suspend fun syncTaskIntoRoom(
    context: ProjectTaskContext,
    taskId: String,
) {
    client.observeTaskEvents(
        context = context,
        taskId = taskId,
    ).collect { event ->
        taskDao.insertEvent(event.toEntity())

        when (event) {
            is TaskProgressEvent -> taskDao.updateProgress(taskId, event.payload.toString())
            is TaskCompletedEvent -> taskDao.markCompleted(taskId)
            is TaskFailedEvent -> taskDao.markFailed(taskId, event.payload.toString())
            is TaskCancelledEvent -> taskDao.markCancelled(taskId)
            else -> Unit
        }
    }
}
```

### ViewModel shape

```kotlin
class TaskViewModel(
    taskDao: TaskDao,
    taskId: String,
) : ViewModel() {
    val taskState: StateFlow<TaskUiState> =
        taskDao.observeTask(taskId)
            .map { entity -> entity.toUiState() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Lazily,
                initialValue = TaskUiState.Loading,
            )
}
```

## 7. Important durability caveat

Using Room as your UI source of truth is recommended, but you should not describe it as a transactional guarantee with TaskBridge checkpoints.

The current SDK updates its checkpoint after emitting an event to the collector. That means there is a narrow failure window:

- the SDK emits event `E`;
- the SDK persists checkpoint `E`;
- the app process dies before Room commit completes.

In that case, Room may miss an event that the checkpoint has already advanced past.

Practical guidance:

- make Room event inserts idempotent by `eventId`;
- use the Room projection as your UI state source, not as an irreversible audit ledger;
- if your product needs stronger local durability guarantees, document that requirement explicitly and design an app-specific reconciliation strategy.

TaskBridge still gives you durable stream resume semantics. The caveat is specifically about coordinating SDK checkpoint persistence with your own database write.

## 8. Recommended production baseline

For most Android consumers, this is the right default posture:

- `OkHttpTaskBridgeTransportFactory`
- `authHeaderProvider` for auth and token refresh
- `TaskBridgeRouteResolver` for custom backend paths
- `DataStoreTaskBridgeCheckpointStore` for process-death recovery
- Room-backed projection for UI state
- idempotent inserts keyed by `eventId`

## Summary

The current production-ready setup is built around `TaskBridgeConfig`, not per-call URLs or a separate transport object. If you combine:

- SDK-native auth via `authHeaderProvider`;
- route customization via `TaskBridgeRouteResolver`;
- persistent checkpoints via `DataStoreTaskBridgeCheckpointStore`;
- local Room projection for UI state;

you get a resilient Android integration that matches the current TaskBridge codebase and backend routing model without overstating the durability guarantees of your local projection layer.
