# Executor integration (TaskBridge backend core)

TaskBridge **does not** embed Temporal, Celery, RQ, or other worker frameworks in its core package. Execution is always supplied by the host application through the `TaskExecutor` abstract contract (`taskbridge.worker.TaskExecutor`).

## Responsibilities split

| Layer | Owns |
|--------|------|
| **TaskBridge services** | HTTP/WebSocket transport, idempotent task creation, polling replay semantics, ownership hooks |
| **Host `TaskRegistry`** | Durable task rows / metadata, lifecycle fields such as `cancellation_requested` |
| **Host `EventStore`** | Ordered append-only stream per task (`eventId` monotonic per stream) |
| **Host `TaskExecutor`** | Submission to a runtime, progress/timing, emitting events + updating registry status |

## Lifecycle expectations

1. `TaskCreationService` persists an `ACCEPTED` task, then calls `TaskExecutor.submit_task` exactly once per new task id.
2. The executor transitions work to `RUNNING` when execution actually begins (or your domain equivalent), appends `TASK_STARTED`, then progress/message events as appropriate.
3. Terminal outcomes must align registry status and events:
   - `COMPLETED` + `TASK_COMPLETED`
   - `FAILED` + `TASK_FAILED`
   - `CANCELLED` + `TASK_CANCELLED`
4. `TaskCancellationService` updates the registry (`cancellation_requested`) then calls `TaskExecutor.request_cancellation`. The executor must cooperate asynchronously (signals, workflow cancel, flags).

## Adapter placement

Heavy integrations (Temporal workflows, Celery chains, Kubernetes jobs) belong in **`backend/adapters/`** packages or host-specific modules that implement `TaskExecutor` — never as imports inside `taskbridge` core.

Reference Temporal adapter package:

- `backend/adapters/temporal` (separate dependency and CI gate, no Temporal dependency in backend core)

## Reference fake

`DeterministicFakeExecutor` (`taskbridge.fakes`) is a tiny asyncio-backed executor for **tests and demos**: fixed progress ticks, optional injected failure, cooperative cancellation. It is not a production worker.

## Temporal state boundary guidance

When using Temporal:

- keep **durable workflow state** (retry state, long-lived variables, domain checkpoints) inside workflow execution history/state;
- emit **ephemeral TaskBridge events** (`TASK_PROGRESS`, `TASK_MESSAGE`, terminal events) only for client-facing transport updates;
- avoid pushing full domain state snapshots into TaskBridge event payloads to keep replay streams compact.
