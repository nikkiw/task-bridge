from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import yaml
from jsonschema import Draft202012Validator

REPO_ROOT = Path(__file__).resolve().parents[3]
PROTOCOL_DIR = REPO_ROOT / "protocol"
EXAMPLES_DIR = PROTOCOL_DIR / "examples"
EVENT_SCHEMA_PATH = PROTOCOL_DIR / "schemas" / "task_event.schema.json"
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


def test_protocol_examples_validate_against_task_event_schema() -> None:
    schema = json.loads(EVENT_SCHEMA_PATH.read_text(encoding="utf-8"))
    validator = Draft202012Validator(schema)

    for example_file in sorted(EXAMPLES_DIR.glob("*.json")):
        payload = json.loads(example_file.read_text(encoding="utf-8"))
        for candidate in _iter_event_candidates(payload):
            errors = sorted(validator.iter_errors(candidate), key=lambda err: str(err.path))
            assert not errors, f"{example_file.name} violates task_event schema: {errors!r}"


def test_openapi_has_required_top_level_sections() -> None:
    spec = yaml.safe_load(OPENAPI_PATH.read_text(encoding="utf-8"))
    required_sections = ("openapi", "info", "paths", "components")
    for section in required_sections:
        assert section in spec, f"Missing OpenAPI section: {section}"

    assert "/api/v1/tasks" in spec["paths"]
    assert "/api/v1/tasks/{taskId}/events" in spec["paths"]
    assert "/api/v1/tasks/{taskId}/cancel" in spec["paths"]
    assert "/api/v1/tasks/ws" in spec["paths"]
