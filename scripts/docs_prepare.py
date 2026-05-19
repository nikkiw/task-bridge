from __future__ import annotations

import argparse
import shutil
import subprocess
from pathlib import Path
from textwrap import dedent


LLMS_TITLE = "TaskBridge"
LLMS_SUMMARY = dedent(
    """
    TaskBridge is a protocol-driven task streaming stack for Android and FastAPI.
    This file is the high-signal entrypoint for agents: it gives the exact package
    names, import paths, core types, and generation rules that prevent wrong code.
    """
).strip()

LLMS_USAGE_RULES = dedent(
    """
    When generating code for TaskBridge, prefer the exact public surfaces listed here instead of inventing wrappers or legacy names.

    Do not invent older coordinates:
    - Android Maven group is `io.github.nikkiw.taskbridge`
    - Android artifacts are `taskbridge-core` and `taskbridge-transport-okhttp`
    - Python package imports come from `taskbridge`
    - Temporal adapter imports come from `taskbridge_temporal`

    Do not invent outdated Android transport APIs:
    - configure the client through `TaskBridgeConfig<Ctx>`
    - use `OkHttpTaskBridgeTransportFactory` for the standard Android transport
    - customize routes through `TaskBridgeRouteResolver<Ctx>`
    - multipart uploads use `TaskBridgeMultipartAttachment`, not `MultipartBody.Part`

    Do not invent outdated backend ownership:
    - the host app owns `FastAPI()` construction, auth, registry, event store, and executor wiring
    - `taskbridge-fastapi` owns route builders, service orchestration, and stream semantics
    - runtime-specific execution belongs in adapters such as `taskbridge_temporal`
    """
).strip()

LLMS_ANDROID_IMPORTS = dedent(
    """
    Android imports to copy from:

    ```kotlin
    import io.github.nikkiw.taskbridge.api.TaskBridgeClient
    import io.github.nikkiw.taskbridge.api.TaskBridgeConfig
    import io.github.nikkiw.taskbridge.api.TaskBridgeRouteResolver
    import io.github.nikkiw.taskbridge.api.DefaultTaskBridgeRouteResolver
    import io.github.nikkiw.taskbridge.checkpoint.TaskBridgeCheckpointStore
    import io.github.nikkiw.taskbridge.checkpoint.InMemoryTaskBridgeCheckpointStore
    import io.github.nikkiw.taskbridge.checkpoint.DataStoreTaskBridgeCheckpointStore
    import io.github.nikkiw.taskbridge.model.TaskCreateJsonRequest
    import io.github.nikkiw.taskbridge.model.TaskBridgeMultipartAttachment
    import io.github.nikkiw.taskbridge.model.TaskActionRequest
    import io.github.nikkiw.taskbridge.model.TaskEvent
    import io.github.nikkiw.taskbridge.model.TaskProgressPayload
    import io.github.nikkiw.taskbridge.model.TaskFailedPayload
    import io.github.nikkiw.taskbridge.okhttp.OkHttpTaskBridgeTransportConfig
    import io.github.nikkiw.taskbridge.okhttp.OkHttpTaskBridgeTransportFactory
    import io.github.nikkiw.taskbridge.policy.TaskBridgeFailureClassifier
    import io.github.nikkiw.taskbridge.policy.TaskBridgeRetryPolicy
    ```
    """
).strip()

LLMS_ANDROID_REFERENCE = dedent(
    """
    Android quick reference:

    - `TaskBridgeConfig<Ctx>` fields: `baseUrl`, `transportFactory`, `routeResolver`, `authHeaderProvider`, `checkpointStore`, `checkpointNamespace`, `failureClassifier`, `retryPolicy`, `streamTransport`, `transportEventListener`, `json`, `dispatcher`, `commandMaxAttempts`
    - `TaskBridgeClient<Ctx>` methods: `startTaskJson`, `startTaskMultipart`, `observeTaskEvents`, `cancelTask`, `submitAction`
    - `TaskBridgeRouteResolver<Ctx>` methods: `createTaskPath`, `createTaskMultipartPath`, `pollEventsPath`, `cancelTaskPath`, `submitActionPath`, `webSocketPath`, `streamEventsPath`
    - `TaskBridgeCheckpointStore` methods: `load`, `save`, `clear`
    - `TaskCreateJsonRequest` fields: `clientRequestId`, `taskType`, `input`, `metadata`
    - `TaskBridgeMultipartAttachment` fields: `fieldName`, `fileName`, `contentType`, `content`
    - `TaskActionRequest` fields: `clientActionId`, `suspendId`, `actionType`, `payload`, `metadata`
    - `TaskEvent` base fields: `taskId`, `eventId`, `createdAt`, `payload`, `wireType`
    - `TaskProgressPayload` fields: `progress`, `stage`, `message`
    - `TaskFailedPayload` fields: `code`, `message`, `retryable`
    - `TaskSuspendedEvent.suspension` fields: `suspendId`, `kind`, `reasonCode`, `allowedActions`, `schemaVersion`, `expiresAt`, `uiHints`, `interaction`
    - terminal events are `TaskCompletedEvent`, `TaskFailedEvent`, `TaskCancelledEvent`
    """
).strip()

LLMS_ANDROID_TRANSPORT = dedent(
    """
    Android transport facts:

    - `OkHttpTaskBridgeTransportConfig` fields: `okHttpClient`, `longPollReadTimeoutBufferMs`
    - `OkHttpTaskBridgeTransportFactory<Ctx>` is the standard transport implementation
    - `authHeaderProvider(context, true)` is the refresh hook after a rejected `401`
    - replay-safe observation is based on checkpoints and stable event IDs, not on WebSocket session continuity
    """
).strip()

LLMS_BACKEND_IMPORTS = dedent(
    """
    Backend imports to copy from:

    ```python
    from taskbridge import (
        build_http_router,
        build_ws_router,
        install_http_exception_handlers,
        TaskRegistry,
        TaskExecutor,
        EventStore,
        AuthContextResolver,
        OwnershipPolicy,
        UploadPolicy,
        MetricsSink,
        ReadinessProbe,
        RedisEventStoreSettings,
        RedisStreamEventStore,
        StreamRuntimeSettings,
        SseStreamSettings,
        WebSocketStreamSettings,
        HttpRouteSettings,
        WebSocketRouteSettings,
        TaskEventType,
        TaskStatus,
        CancelTaskStatus,
        SubmitActionResultStatus,
        TaskSuspensionStatus,
        TaskSuspensionKind,
        ResumeHandoffStatus,
        TaskActionReceiptStatus,
        ErrorResponse,
        HealthResponse,
        ReadinessResponse,
        TaskAttachment,
        TaskCreationService,
        TaskPollingService,
        TaskActionService,
        TaskCancellationService,
        TaskResumeService,
        TaskResumeReconciliationService,
        TaskRetentionService,
        WebSocketSubscriptionService,
        TaskRecord,
        TaskCreateCommand,
        HttpTaskCreateRequest,
        TaskCreatedResult,
        PollEventsResult,
        CancelTaskCommand,
        CancelTaskResult,
        HttpSubmitActionRequest,
        SubmitActionCommand,
        SubmitActionResult,
        TaskEvent,
        TaskSuspensionRecord,
        TaskActionReceipt,
        AuthContext,
    )
    ```
    """
).strip()

LLMS_BACKEND_REFERENCE = dedent(
    """
    Backend quick reference:

    - `TaskRegistry` methods: `create_task`, `get_task`, `get_task_by_client_request_id`, `update_task_status`, `request_cancellation`, `same_idempotency_payload`
    - `TaskExecutor` methods: `submit_task`, `request_cancellation`
    - `EventStore` methods: `append_event`, `read_events_after`, `wait_for_events`
    - `AuthContextResolver` method: `resolve_auth_context`
    - `OwnershipPolicy` methods: `assert_task_create`, `assert_task_access`
    - `UploadPolicy` method: `assert_upload_allowed`
    - `MetricsSink` methods: `increment_counter`, `set_gauge`
    - `ReadinessProbe` method: `check_readiness`
    - `TaskRecord` fields: `task_id`, `client_request_id`, `task_type`, `input_payload`, `metadata`, `attachments`, `owner_id`, `status`, `cancellation_requested`, `created_at`, `updated_at`
    - `TaskCreateCommand` fields: `client_request_id`, `task_type`, `input_payload`, `metadata`, `attachments`, `auth_context`
    - `TaskEvent` fields: `type`, `task_id`, `event_id`, `created_at`, `payload`
    - `TaskSuspensionRecord` fields: `task_id`, `suspend_id`, `owner_id`, `status`, `kind`, `reason_code`, `allowed_actions`, `schema_version`, `interaction_payload`, `ui_hints`, `resume_token`, `successful_action_id`, `resume_handoff_status`, `created_at`, `expires_at`, `resolved_at`
    - `TaskActionReceipt` fields: `task_id`, `suspend_id`, `client_action_id`, `action_type`, `payload`, `actor_id`, `status`, `created_at`, `processed_at`, `expires_at`
    - HTTP request models: `HttpTaskCreateRequest`, `HttpCancelTaskRequest`, `HttpSubmitActionRequest`
    - HTTP/WebSocket response models: `TaskCreatedResult`, `PollEventsResult`, `CancelTaskResult`, `SubmitActionResult`, `WebSocketSubscribeRequest`, `WebSocketSubscriptionConfirmed`, `WebSocketHeartbeat`
    """
).strip()

LLMS_BACKEND_SETTINGS = dedent(
    """
    Backend settings and wiring facts:

    - `HttpRouteSettings` fields: `max_upload_bytes`, `allowed_upload_content_types`, `create_task_path`, `poll_events_path`, `stream_events_path`, `cancel_task_path`, `submit_action_path`, `stream_runtime`
    - `WebSocketRouteSettings` fields: `replay_batch_size`, `live_batch_size`, `wait_timeout_ms`, `invalid_frame_close_code`, `terminal_close_code`, `stream_runtime`
    - `StreamRuntimeSettings` fields: `sse`, `websocket`
    - `SseStreamSettings` fields: `replay_batch_size`, `live_batch_size`, `wait_timeout_ms`, `emit_comment_ping`, `emit_json_heartbeat`, `comment_ping_payload`, `anti_buffering_preamble_bytes`, `cache_control`, `connection_header`, `x_accel_buffering`
    - `WebSocketStreamSettings` fields: `replay_batch_size`, `live_batch_size`, `wait_timeout_ms`, `invalid_frame_close_code`, `terminal_close_code`, `emit_heartbeat_frame`
    - `RedisEventStoreSettings` fields: `stream_key_prefix`, `max_stream_length`, `approximate_maxlen`, `stream_ttl_seconds`, `cleanup_interval_seconds`
    - default route builders to prefer are `build_http_router()`, `build_ws_router()`, and `install_http_exception_handlers(app)`
    """
).strip()

LLMS_TEMPORAL_IMPORTS = dedent(
    """
    ```python
    from taskbridge_temporal import (
        TemporalTaskExecutor,
        TemporalExecutorConfig,
        WorkflowInputMapper,
        DefaultWorkflowInputMapper,
        TemporalWorkflowUpdate,
        map_temporal_update_to_task_event,
    )
    ```
    """
).strip()

LLMS_TEMPORAL_CONFIG = dedent(
    """
    Temporal adapter quick reference:

    - `TemporalExecutorConfig` fields: `task_queue`, `workflow`, `workflow_id_prefix`, `namespace`, `cancellation_mode`, `start_timeout_seconds`, `workflow_input_mapper`
    - `TemporalExecutorConfig.workflow_id_for(task)` returns `<workflow_id_prefix>:<task.task_id>`
    - `DefaultWorkflowInputMapper.to_workflow_input(task)` returns a dict with `task_id`, `task_type`, `input_payload`, `metadata`, `owner_id`
    - `TemporalTaskExecutor.submit_task(task)` starts the workflow
    - `TemporalTaskExecutor.request_cancellation(task)` cancels or signals depending on `cancellation_mode`
    - the Temporal adapter does not replace `TaskEvent` emission or `TaskRegistry` updates; host workflows still own those
    """
).strip()

LLMS_PROTOCOL = dedent(
    """
    Protocol vocabulary that tends to matter when generating examples:

    - Android event types: `TASK_STARTED`, `TASK_PROGRESS`, `TASK_MESSAGE`, `TASK_SUSPENDED`, `TASK_ACTION_ACCEPTED`, `TASK_COMPLETED`, `TASK_FAILED`, `TASK_CANCELLED`.
    - Android suspension kinds: `USER_ACTION_REQUIRED`, `EXTERNAL_CONFIRMATION_PENDING`, `SYSTEM_PAUSED`.
    - Backend task statuses: `ACCEPTED`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`.
    - Backend action result statuses: `ACCEPTED`, `DEDUPLICATED`, `REJECTED_ALREADY_RESOLVED`, `REJECTED_EXPIRED`.

    The important semantic boundary is replay safety: event delivery can duplicate, but the client and backend both key progress on stable event IDs and task IDs.
    """
).strip()

LLMS_EXAMPLES = dedent(
    """
    Example entrypoints worth reading after this file:

    - `examples/fastapi-host/` shows a minimal host wiring `taskbridge-fastapi`.
    - `examples/android-integration/` runs the Android sample against that host.
    - `examples/01-minimal-greeter/` is the simplest consumer setup.
    """
).strip()

LLMS_ANDROID_LINKS = [
    ("Android SDK Overview", "./android/", "Conceptual entrypoint for Android integration."),
    ("Android Production Setup", "./android/production-setup/", "Production client wiring with auth, route resolver, and checkpoint persistence."),
    ("Client and Config", "./android/client-config/", "Detailed explanation of `TaskBridgeConfig`, `Ctx`, and route customization."),
    ("Events and Recovery", "./android/events-and-recovery/", "Replay semantics, suspension handling, and terminal events."),
    ("Transport and Extension Points", "./android/transport-and-extension-points/", "Transport fallback, custom transports, and extension boundaries."),
    ("Storage and Policies", "./android/storage-and-policies/", "Checkpoint persistence, retry policy, and failure classification."),
]

LLMS_BACKEND_LINKS = [
    ("Backend Overview", "./backend/", "Conceptual entrypoint for FastAPI integration."),
    ("Host Integration", "./backend/host-integration/", "What the host owns versus what TaskBridge owns."),
    ("Services and Routes", "./backend/services-and-routes/", "HTTP routes, WS routes, and service boundaries."),
    ("Security, Readiness, and Observability", "./backend/security-readiness-observability/", "Auth, ownership, metrics, and readiness hooks."),
    ("State and Runtime Boundaries", "./backend/state-and-runtime-boundaries/", "Registry, event store, retention, and runtime separation."),
]

LLMS_PROTOCOL_LINKS = [
    ("Getting Started", "./getting-started/", "Quick install and local development flow."),
    ("Protocol Specification", "./protocol/", "Wire contract, event semantics, and interoperability surface."),
    ("Architecture Overview", "./architecture/", "Repository-wide boundaries between SDK, backend, and adapters."),
]

LLMS_EXAMPLE_LINKS = [
    ("Examples Overview", "./examples/", "Index of runnable consumer setups."),
    ("FastAPI Host Example", "./examples/#fastapi-host", "Minimal host wiring for `taskbridge-fastapi`."),
    ("Android Integration Example", "./examples/#android-integration", "Android sample wired to the FastAPI host."),
]

LLMS_OPTIONAL_LINKS = [
    ("Android API Reference", "./reference/android/", "Generated Android API reference."),
    ("Backend Python API Reference", "./reference/backend/", "Python symbol reference."),
    ("Adapter Python API Reference", "./reference/adapters/", "Temporal adapter symbol reference."),
    ("OpenAPI Reference", "./reference/backend-api/", "Rendered OpenAPI and schema pages."),
    ("Documentation Guide", "./documentation/", "Docs build and publication workflow."),
    ("Publication Guide", "./contributing/publication/", "Release and publishing flow."),
    ("ADR Index", "./adr/", "Architecture decisions and rationale."),
]

LLMS_REFERENCE_NOTE = dedent(
    """
    For exhaustive symbol lists, use the generated reference pages listed under `Optional`. For agent-first generation, prefer the rules and exact names above before falling back to prose docs.
    """
).strip()


def repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def build_android_reference(android_dir: Path) -> None:
    subprocess.run(
        ["./gradlew", "dokkaGeneratePublicationHtml"],
        cwd=android_dir,
        check=True,
    )


def build_backend_reference(root: Path) -> None:
    # Future-proofing: could generate OpenAPI static docs here
    # For now, we ensure the protocol directory is accessible if needed
    pass


def remove_legacy_android_reference(legacy_destination: Path) -> None:
    if legacy_destination.is_dir():
        shutil.rmtree(legacy_destination)


def prepare_android_reference(source: Path, destination: Path) -> Path:
    if not source.is_dir():
        # Fallback to check if it's in a different location or if we should aggregate manually
        raise FileNotFoundError(f"Dokka output directory not found: {source}")

    if destination.exists():
        shutil.rmtree(destination)

    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(source, destination)

    # Inject "Back to Manual" link via main.js to appear on every page
    main_js = destination / "scripts" / "main.js"
    if main_js.exists():
        back_link_js = """
window.addEventListener('DOMContentLoaded', () => {
    const nav = document.getElementById('navigation-wrapper');
    if (nav) {
        const link = document.createElement('a');
        link.href = '../../'; 
        link.innerText = '← Back to Manual';
        link.className = 'library-name--link';
        link.style.marginRight = '20px';
        link.style.display = 'inline-block';
        nav.prepend(link);
    }
});
"""
        with open(main_js, "a", encoding="utf-8") as f:
            f.write(back_link_js)
        print(f"Injected back-link into {main_js}")

    return destination


def prepare_backend_reference(root: Path, destination_dir: Path) -> None:
    openapi_source = root / "protocol" / "openapi" / "taskbridge.openapi.yaml"
    schemas_source = root / "protocol" / "schemas"
    
    if openapi_source.exists():
        destination_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy2(openapi_source, destination_dir / "openapi.yaml")
        print(f"Copied OpenAPI spec to {destination_dir / 'openapi.yaml'}")

        # Also copy schemas because they are referenced relatively (../schemas/...)
        schemas_destination = destination_dir.parent / "schemas"
        if schemas_source.is_dir():
            if schemas_destination.exists():
                shutil.rmtree(schemas_destination)
            shutil.copytree(schemas_source, schemas_destination)
            print(f"Copied protocol schemas to {schemas_destination}")

        # Generate a simple Redoc index.html with a back link
        redoc_html = f"""<!DOCTYPE html>
<html>
  <head>
    <title>TaskBridge Backend API Reference</title>
    <meta charset="utf-8"/>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <link href="https://fonts.googleapis.com/css?family=Montserrat:300,400,700|Roboto:300,400,700" rel="stylesheet">
    <style>
      body {{
        margin: 0;
        padding: 0;
      }}
      .back-nav {{
        padding: 12px 24px;
        background: #fafafa;
        border-bottom: 1px solid #e5e5e5;
      }}
      .back-nav a {{
        text-decoration: none;
        color: #1DA2BD;
        font-family: Montserrat, sans-serif;
        font-weight: 700;
        font-size: 14px;
      }}
    </style>
  </head>
  <body>
    <div class="back-nav">
      <a href="../../">← Back to Manual</a>
    </div>
    <redoc spec-url='openapi.yaml'></redoc>
    <script src="https://cdn.redoc.ly/redoc/latest/bundles/redoc.standalone.js"> </script>
  </body>
</html>
"""
        (destination_dir / "index.html").write_text(redoc_html, encoding="utf-8")
        print(f"Generated Redoc HTML at {destination_dir / 'index.html'}")


def render_llms_txt() -> str:
    parts = [
        f"# {LLMS_TITLE}",
        "",
        f"> {LLMS_SUMMARY}",
        "",
        LLMS_USAGE_RULES,
        "",
        LLMS_ANDROID_IMPORTS,
        "",
        LLMS_ANDROID_REFERENCE,
        "",
        LLMS_ANDROID_TRANSPORT,
        "",
        LLMS_BACKEND_IMPORTS,
        "",
        LLMS_BACKEND_REFERENCE,
        "",
        LLMS_BACKEND_SETTINGS,
        "",
        LLMS_TEMPORAL_IMPORTS,
        "",
        LLMS_TEMPORAL_CONFIG,
        "",
        LLMS_PROTOCOL,
        "",
        LLMS_EXAMPLES,
        "",
        LLMS_REFERENCE_NOTE,
        "",
        "## Android SDK",
        "",
    ]
    for label, url, description in LLMS_ANDROID_LINKS:
        parts.append(f"- [{label}]({url}): {description}")
    parts.extend(
        [
            "",
            "## Backend (FastAPI)",
            "",
        ]
    )
    for label, url, description in LLMS_BACKEND_LINKS:
        parts.append(f"- [{label}]({url}): {description}")
    parts.extend(
        [
            "",
            "## Protocol",
            "",
        ]
    )
    for label, url, description in LLMS_PROTOCOL_LINKS:
        parts.append(f"- [{label}]({url}): {description}")
    parts.extend(
        [
            "",
            "## Examples",
            "",
        ]
    )
    for label, url, description in LLMS_EXAMPLE_LINKS:
        parts.append(f"- [{label}]({url}): {description}")
    parts.extend(
        [
            "",
            "## Optional",
            "",
        ]
    )
    for label, url, description in LLMS_OPTIONAL_LINKS:
        parts.append(f"- [{label}]({url}): {description}")
    parts.extend(
        [
            "",
            "## Temporal Adapter",
            "",
            "- [Temporal Adapter Guide](./adapters/temporal/): Runtime-specific integration guidance.",
            "- [Adapters Overview](./adapters/): Adapter boundary and publishable package overview.",
            "",
        ]
    )
    return "\n".join(parts).rstrip() + "\n"


def prepare_llms_txt(destination: Path) -> Path:
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(render_llms_txt(), encoding="utf-8")
    print(f"Prepared LLM index at {destination}")
    return destination


def parse_args() -> argparse.Namespace:
    root = repo_root()
    parser = argparse.ArgumentParser(
        description="Build and stage generated documentation assets for the TaskBridge MkDocs site."
    )
    parser.add_argument(
        "--skip-build",
        action="store_true",
        help="Reuse existing build outputs instead of invoking build tools.",
    )
    parser.add_argument(
        "--dokka-source",
        type=Path,
        default=root / "android" / "build" / "dokka" / "html",
        help="Path to the generated Dokka HTML directory.",
    )
    parser.add_argument(
        "--destination-android",
        type=Path,
        default=root / "docs" / "reference" / "android-ref",
        help="Destination inside the MkDocs source tree for Android API reference.",
    )
    parser.add_argument(
        "--destination-backend",
        type=Path,
        default=root / "docs" / "reference" / "backend-api",
        help="Destination inside the MkDocs source tree for Backend API reference assets.",
    )
    parser.add_argument(
        "--destination-llms",
        type=Path,
        default=root / "docs" / "llms.txt",
        help="Destination inside the MkDocs source tree for the curated llms.txt index.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = repo_root()

    if not args.skip_build:
        print("Building Android API reference...")
        build_android_reference(root / "android")
        print("Building Backend API reference assets...")
        build_backend_reference(root)

    remove_legacy_android_reference(root / "docs" / "site" / "reference" / "android")
    remove_legacy_android_reference(root / "docs" / "site" / "reference" / "android-ref")
    remove_legacy_android_reference(root / "docs" / "site" / "reference" / "backend-api")
    remove_legacy_android_reference(root / "docs" / "site" / "reference" / "schemas")
    remove_legacy_android_reference(root / "docs" / "reference" / "android")

    prepared_android = prepare_android_reference(
        source=args.dokka_source.resolve(),
        destination=args.destination_android.resolve(),
    )
    print(f"Prepared Android API reference at {prepared_android}")

    prepare_backend_reference(
        root=root,
        destination_dir=args.destination_backend.resolve(),
    )
    prepare_llms_txt(args.destination_llms.resolve())

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
