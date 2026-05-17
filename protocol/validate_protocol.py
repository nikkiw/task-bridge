from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import yaml
from jsonschema import Draft202012Validator

PROTOCOL_DIR = Path(__file__).resolve().parent
EXAMPLES_DIR = PROTOCOL_DIR / "examples"
EVENT_SCHEMA_PATH = PROTOCOL_DIR / "schemas" / "task_event.schema.json"
CLIENT_ACTION_SCHEMA_PATH = PROTOCOL_DIR / "schemas" / "task_client_action.schema.json"
CLIENT_ACTION_RESPONSE_SCHEMA_PATH = (
    PROTOCOL_DIR / "schemas" / "task_client_action_response.schema.json"
)
OPENAPI_PATH = PROTOCOL_DIR / "openapi" / "taskbridge.openapi.yaml"


def _is_task_event_shape(payload: dict[str, Any]) -> bool:
    required = {"type", "taskId", "eventId", "createdAt", "payload"}
    return required.issubset(payload.keys())


def _iter_event_candidates(document: Any) -> list[dict[str, Any]]:
    if isinstance(document, dict):
        if _is_task_event_shape(document):
            return [document]
        if isinstance(document.get("events"), list):
            return [event for event in document["events"] if isinstance(event, dict)]
    return []


def validate_event_examples() -> None:
    schema = json.loads(EVENT_SCHEMA_PATH.read_text(encoding="utf-8"))
    validator = Draft202012Validator(schema)
    for example_file in sorted(EXAMPLES_DIR.glob("*.json")):
        payload = json.loads(example_file.read_text(encoding="utf-8"))
        for candidate in _iter_event_candidates(payload):
            errors = sorted(validator.iter_errors(candidate), key=lambda err: str(err.path))
            if errors:
                raise AssertionError(f"{example_file.name} violates task_event schema: {errors!r}")


def validate_openapi_shape() -> None:
    spec = yaml.safe_load(OPENAPI_PATH.read_text(encoding="utf-8"))
    for section in ("openapi", "info", "paths", "components"):
        if section not in spec:
            raise AssertionError(f"Missing OpenAPI section: {section}")

    for path in (
        "/api/v1/tasks",
        "/api/v1/tasks/{taskId}/events",
        "/api/v1/tasks/{taskId}/actions",
        "/api/v1/tasks/{taskId}/cancel",
        "/api/v1/tasks/ws",
    ):
        if path not in spec["paths"]:
            raise AssertionError(f"Missing required path: {path}")

    for schema_name in (
        "TaskClientActionRequest",
        "TaskClientActionResponse",
        "TaskActionAcceptedPayload",
    ):
        if schema_name not in spec["components"]["schemas"]:
            raise AssertionError(f"Missing OpenAPI schema: {schema_name}")


def validate_action_schemas_exist() -> None:
    for schema_path in (CLIENT_ACTION_SCHEMA_PATH, CLIENT_ACTION_RESPONSE_SCHEMA_PATH):
        if not schema_path.exists():
            raise AssertionError(f"Missing protocol schema: {schema_path.name}")
        Draft202012Validator.check_schema(json.loads(schema_path.read_text(encoding="utf-8")))


if __name__ == "__main__":
    validate_event_examples()
    validate_action_schemas_exist()
    validate_openapi_shape()
    print("Protocol validation passed")
