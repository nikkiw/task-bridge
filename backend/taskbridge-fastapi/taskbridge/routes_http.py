"""HTTP routes and infrastructure for TaskBridge.

This module provides the APIRouter for TaskBridge HTTP endpoints, including
task creation, polling, and action submission.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Annotated, Any

from fastapi import APIRouter, Depends, FastAPI, Header, Query, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse

from .dependencies import (
    get_metrics_sink,
    get_readiness_probe,
    get_task_action_service,
    get_task_cancellation_service,
    get_task_creation_service,
    get_task_polling_service,
    get_transport_diagnostics_sink,
    get_upload_policy,
    get_websocket_subscription_service,
    resolve_http_auth_context,
)
from .errors import (
    AuthenticationError,
    InvalidRequestError,
    TaskBridgeError,
    TaskConflictError,
    TaskNotFoundError,
    TaskOwnershipError,
    TaskSubmissionError,
    UploadValidationError,
)
from .handlers import handle_http_polling
from .models import (
    AuthContext,
    CancelTaskCommand,
    CancelTaskResult,
    ErrorResponse,
    HealthResponse,
    HttpCancelTaskRequest,
    HttpSubmitActionRequest,
    HttpTaskCreateRequest,
    PollEventsResult,
    ReadinessResponse,
    SubmitActionCommand,
    SubmitActionResult,
    SubmitActionResultStatus,
    TaskAttachment,
    TaskCreateCommand,
    TaskCreatedResult,
)
from .observability import MetricsSink, log_structured
from .readiness import ReadinessProbe
from .security import UploadPolicy
from .stream_runtime import build_sse_stream_response, sse_event_generator
from .stream_settings import StreamRuntimeSettings

logger = logging.getLogger(__name__)

TASK_CREATION_SERVICE = Depends(get_task_creation_service)
TASK_POLLING_SERVICE = Depends(get_task_polling_service)
TASK_CANCELLATION_SERVICE = Depends(get_task_cancellation_service)
TASK_ACTION_SERVICE = Depends(get_task_action_service)
TASK_SUBSCRIPTION_SERVICE = Depends(get_websocket_subscription_service)
READINESS_PROBE = Depends(get_readiness_probe)
UPLOAD_POLICY = Depends(get_upload_policy)
METRICS_SINK = Depends(get_metrics_sink)
TRANSPORT_DIAGNOSTICS_SINK = Depends(get_transport_diagnostics_sink)


@dataclass(slots=True)
class HttpRouteSettings:
    """Settings for HTTP routes."""

    max_upload_bytes: int = 10 * 1024 * 1024
    allowed_upload_content_types: frozenset[str] = field(default_factory=frozenset)
    create_task_path: str = "/api/v1/tasks"
    poll_events_path: str = "/api/v1/tasks/{taskId}/events"
    stream_events_path: str = "/api/v1/tasks/{taskId}/events/stream"
    cancel_task_path: str = "/api/v1/tasks/{taskId}/cancel"
    submit_action_path: str = "/api/v1/tasks/{taskId}/actions"
    stream_runtime: StreamRuntimeSettings = field(default_factory=StreamRuntimeSettings)


def build_http_router(settings: HttpRouteSettings | None = None) -> APIRouter:
    """Build and return an APIRouter with TaskBridge HTTP routes.

    Args:
        settings: Optional HTTP route settings.

    Returns:
        APIRouter configured with TaskBridge routes.

    """
    actual_settings = settings or HttpRouteSettings()
    router = APIRouter()

    @router.post(actual_settings.create_task_path, response_model=TaskCreatedResult)
    async def create_task(
        request: Request,
        response: Response,
        auth_context: Annotated[AuthContext, Depends(resolve_http_auth_context)],
        service=TASK_CREATION_SERVICE,
        upload_policy: UploadPolicy = UPLOAD_POLICY,
    ) -> TaskCreatedResult:
        command = await _parse_create_task_command(
            request=request,
            auth_context=auth_context,
            settings=actual_settings,
            upload_policy=upload_policy,
        )
        result = await service.create_task(command)
        response.status_code = 200 if result.deduplicated else 201
        log_structured(
            logger,
            level=logging.INFO,
            message="http.task.create",
            task_id=result.task_id,
            client_request_id=result.client_request_id,
            user_id=auth_context.subject,
            transport="http",
            outcome="deduplicated" if result.deduplicated else "created",
        )
        return result

    @router.get(actual_settings.poll_events_path, response_model=PollEventsResult)
    async def poll_events(
        taskId: str,
        afterEventId: str | None = Query(default=None),
        waitTimeoutMs: int = Query(default=0, ge=0, le=60_000),
        maxEvents: int = Query(default=100, ge=1, le=500),
        auth_context: Annotated[AuthContext, Depends(resolve_http_auth_context)] = None,
        service=TASK_POLLING_SERVICE,
        metrics_sink: MetricsSink = METRICS_SINK,
    ) -> PollEventsResult:
        return await handle_http_polling(
            task_id=taskId,
            polling_service=service,
            auth_context=auth_context,
            metrics_sink=metrics_sink,
            after_event_id=afterEventId,
            limit=maxEvents,
            wait_timeout_ms=waitTimeoutMs,
        )

    @router.get(actual_settings.stream_events_path)
    async def stream_events(
        taskId: str,
        request: Request,
        lastEventId: str | None = Header(None, alias="Last-Event-ID"),
        auth_context: Annotated[AuthContext, Depends(resolve_http_auth_context)] = None,
        service=TASK_SUBSCRIPTION_SERVICE,
        diagnostics_sink=TRANSPORT_DIAGNOSTICS_SINK,
    ) -> StreamingResponse:
        event_iterator = sse_event_generator(
            request=request,
            task_id=taskId,
            last_event_id=lastEventId,
            auth_context=auth_context,
            service=service,
            settings=actual_settings.stream_runtime.sse,
            diagnostics=diagnostics_sink,
        )
        return build_sse_stream_response(
            event_iterator,
            actual_settings.stream_runtime.sse,
        )

    @router.post(
        actual_settings.cancel_task_path,
        response_model=CancelTaskResult,
        status_code=202,
    )
    async def cancel_task(
        taskId: str,
        request_body: HttpCancelTaskRequest | None = None,
        auth_context: Annotated[AuthContext, Depends(resolve_http_auth_context)] = None,
        service=TASK_CANCELLATION_SERVICE,
        metrics_sink: MetricsSink = METRICS_SINK,
    ) -> CancelTaskResult:
        actual_command = CancelTaskCommand(
            task_id=taskId,
            auth_context=auth_context,
            reason=request_body.reason if request_body is not None else None,
        )
        result = await service.cancel_task(actual_command)
        metrics_sink.increment_counter("cancels_total")
        log_structured(
            logger,
            level=logging.INFO,
            message="http.task.cancel",
            task_id=taskId,
            user_id=auth_context.subject,
            transport="http",
            outcome=result.status.value,
        )
        return result

    @router.post(
        actual_settings.submit_action_path,
        response_model=SubmitActionResult,
        status_code=202,
    )
    async def submit_action(
        taskId: str,
        request_body: HttpSubmitActionRequest,
        response: Response,
        auth_context: Annotated[AuthContext, Depends(resolve_http_auth_context)] = None,
        service=TASK_ACTION_SERVICE,
        metrics_sink: MetricsSink = METRICS_SINK,
    ) -> SubmitActionResult:
        result = await service.submit_action(
            SubmitActionCommand(
                task_id=taskId,
                client_action_id=request_body.client_action_id,
                suspend_id=request_body.suspend_id,
                action_type=request_body.action_type,
                payload=request_body.payload,
                metadata=request_body.metadata,
                auth_context=auth_context,
            )
        )
        response.status_code = (
            200 if result.status == SubmitActionResultStatus.DEDUPLICATED else 202
        )
        metrics_sink.increment_counter("actions_total", tags={"status": result.status.value})
        log_structured(
            logger,
            level=logging.INFO,
            message="http.task.action",
            task_id=taskId,
            client_request_id=result.client_action_id,
            user_id=auth_context.subject,
            transport="http",
            outcome=result.status.value,
        )
        return result

    @router.get("/health", response_model=HealthResponse)
    async def health() -> HealthResponse:
        return HealthResponse(status="ok")

    @router.get("/ready", response_model=ReadinessResponse)
    async def ready(probe: ReadinessProbe = READINESS_PROBE) -> JSONResponse:
        is_ready, details = await probe.check_readiness()
        payload = ReadinessResponse(
            status="ready" if is_ready else "not_ready",
            details=details,
        )
        return JSONResponse(
            status_code=200 if is_ready else 503,
            content=payload.model_dump(mode="json"),
        )

    return router


def install_http_exception_handlers(app: FastAPI) -> None:
    """Register exception handlers for TaskBridge errors on a FastAPI app.

    Args:
        app: The FastAPI application instance.

    """

    @app.exception_handler(TaskBridgeError)
    async def handle_taskbridge_error(_: Request, exc: TaskBridgeError) -> JSONResponse:
        return JSONResponse(
            status_code=_status_code_for_exception(exc),
            content=_error_response(exc.code, str(exc)).model_dump(mode="json"),
        )

    @app.exception_handler(RequestValidationError)
    async def handle_request_validation(_: Request, exc: RequestValidationError) -> JSONResponse:
        return JSONResponse(
            status_code=422,
            content=_error_response(
                code="REQUEST_VALIDATION_ERROR",
                message="Request validation failed",
                details={"errors": exc.errors()},
            ).model_dump(mode="json"),
        )


async def _parse_create_task_command(
    request: Request,
    auth_context: AuthContext,
    settings: HttpRouteSettings,
    upload_policy: UploadPolicy,
) -> TaskCreateCommand:
    content_type = request.headers.get("content-type", "")
    if content_type.startswith("application/json"):
        payload = HttpTaskCreateRequest.model_validate(await request.json())
        command = TaskCreateCommand(
            client_request_id=payload.client_request_id,
            task_type=payload.task_type,
            input_payload=payload.input_payload,
            metadata=payload.metadata,
            attachments=[],
            auth_context=auth_context,
        )
        await upload_policy.assert_upload_allowed(auth_context, command.attachments)
        return command
    if content_type.startswith("multipart/form-data"):
        form = await request.form()
        client_request_id = _form_required_text(form, "clientRequestId")
        task_type = _form_required_text(form, "taskType")
        input_payload = _parse_json_text(form.get("input"), field_name="input", default={})
        metadata = _parse_json_text(form.get("metadata"), field_name="metadata", default={})
        upload_items = form.getlist("attachment")
        upload_items.extend(form.getlist("attachments"))
        attachments = await _parse_attachments(upload_items, settings)
        command = TaskCreateCommand(
            client_request_id=client_request_id,
            task_type=task_type,
            input_payload=input_payload,
            metadata=metadata,
            attachments=attachments,
            auth_context=auth_context,
        )
        await upload_policy.assert_upload_allowed(auth_context, command.attachments)
        return command
    raise InvalidRequestError("Unsupported content type")


def _form_required_text(form: Any, field_name: str) -> str:
    value = form.get(field_name)
    if not isinstance(value, str) or not value.strip():
        raise InvalidRequestError(f"Missing required field: {field_name}")
    return value


def _parse_json_text(raw_value: Any, *, field_name: str, default: dict[str, Any]) -> dict[str, Any]:
    if raw_value is None or raw_value == "":
        return default
    if not isinstance(raw_value, str):
        raise InvalidRequestError(f"Expected string field: {field_name}")
    try:
        value = json.loads(raw_value)
    except json.JSONDecodeError as exc:
        raise InvalidRequestError(f"Invalid JSON in field: {field_name}") from exc
    if not isinstance(value, dict):
        raise InvalidRequestError(f"Expected JSON object in field: {field_name}")
    return value


async def _parse_attachments(files: list[Any], settings: HttpRouteSettings) -> list[TaskAttachment]:
    attachments: list[TaskAttachment] = []
    for item in files:
        if not _is_upload_file_like(item):
            continue
        content_type = item.content_type or "application/octet-stream"
        if (
            settings.allowed_upload_content_types
            and content_type not in settings.allowed_upload_content_types
        ):
            raise UploadValidationError(f"Unsupported upload content type: {content_type}")
        content = await item.read()
        if len(content) > settings.max_upload_bytes:
            raise UploadValidationError(
                f"Upload exceeds maximum size of {settings.max_upload_bytes} bytes"
            )
        attachments.append(
            TaskAttachment(
                filename=_normalize_filename(item.filename),
                content_type=content_type,
                content=content,
                size_bytes=len(content),
            )
        )
    return attachments


def _normalize_filename(filename: str | None) -> str:
    candidate = (filename or "upload.bin").replace("\\", "/").split("/")[-1]
    return candidate or "upload.bin"


def _is_upload_file_like(value: Any) -> bool:
    return hasattr(value, "read") and hasattr(value, "filename")


def _status_code_for_exception(exc: TaskBridgeError) -> int:
    if isinstance(exc, AuthenticationError):
        return 401
    if isinstance(exc, (InvalidRequestError, UploadValidationError)):
        return 400
    if isinstance(exc, TaskOwnershipError):
        return 403
    if isinstance(exc, TaskNotFoundError):
        return 404
    if isinstance(exc, TaskConflictError):
        return 409
    if isinstance(exc, TaskSubmissionError):
        return 503
    return 500


def _error_response(
    code: str,
    message: str,
    details: dict[str, Any] | None = None,
) -> ErrorResponse:
    return ErrorResponse(
        code=code,
        message=message,
        details=details or {},
        timestamp=datetime.now(UTC),
    )
