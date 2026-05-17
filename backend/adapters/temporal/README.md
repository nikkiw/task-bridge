# Temporal Adapter (`backend/adapters/temporal`)

Temporal integration for TaskBridge backend extension points.

This package implements `TaskExecutor` without introducing Temporal dependencies
into `backend/taskbridge-fastapi`.

## Scope

- `TemporalTaskExecutor.submit_task()` -> Temporal `start_workflow`
- `TemporalTaskExecutor.request_cancellation()` -> workflow `cancel` or `signal`
- mapping helper from Temporal workflow updates to `TaskEvent` shape

## Durable vs ephemeral boundaries

Temporal should keep **durable workflow state**:

- workflow variables/checkpoints
- retries and failure history
- orchestration decisions and domain progress internals

TaskBridge transport should keep **ephemeral client stream events**:

- `TASK_PROGRESS` and `TASK_MESSAGE` for UI feedback
- `TASK_COMPLETED` / `TASK_FAILED` / `TASK_CANCELLED` terminals

Guidance:

- emit progress events only at meaningful checkpoints (avoid noisy per-step spam)
- keep payloads compact and client-facing
- keep heavy domain state in Temporal workflow storage, not in event stream payloads

## Configuration

Use `TemporalExecutorConfig`:

- `task_queue`
- `workflow`
- `workflow_id_prefix`
- `cancellation_mode` (`cancel` or `signal`)
- `start_timeout_seconds`
- `workflow_input_mapper` (host-controlled mapper `TaskRecord -> workflow input`)

## Local checks

From this directory:

```bash
uv sync --group dev
uv run ruff check
uv run ruff format --check
uv run pytest
```
