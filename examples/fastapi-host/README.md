# TaskBridge FastAPI Host Example

This example demonstrates how to embed the TaskBridge backend into a FastAPI application.

## Infrastructure Modes

### 1. Minimal (Fake Mode)
Zero infrastructure required. Uses in-memory stores and a fake executor.
Set `TB_EXECUTOR_MODE=fake`.

### 2. Enterprise (Real Mode)
Uses **Redis** for event streaming and **Temporal** for task execution. Supports **Firebase Auth** and **Human-in-the-loop** (suspensions).

## Running with Docker (Recommended)

The easiest way to run the full Enterprise stack is using Docker Compose:

```bash
docker-compose up --build
```

This will start:
-   **API**: The FastAPI server on port 8000.
-   **Worker**: A Temporal worker for processing tasks.
-   **Temporal Cluster**: Workflow engine + UI on port 8233.
-   **Redis**: High-performance event store.

## Configuration

Environment variables (see `docker-compose.yml`):
-   `TB_EXECUTOR_MODE`: `temporal` (default) or `fake`.
-   `TB_AUTH_MODE`: `demo` (default) or `firebase`.
-   `REDIS_URL`: Connection string for Redis.
-   `TEMPORAL_ADDRESS`: Address of the Temporal cluster.

### Firebase Auth Setup
If you want to test real Firebase authentication:
1.  Set `TB_AUTH_MODE=firebase`.
2.  Set `GOOGLE_APPLICATION_CREDENTIALS` to your service account JSON path.
3.  The backend will expect a `Authorization: Bearer <ID_TOKEN>` header.

## Enterprise Features Demonstrated

### 1. Human-in-the-loop (Suspensions)
Start a task with type `enterprise.document.review`. 
1.  The workflow will start.
2.  It will emit a `TASK_SUSPENDED` event.
3.  The Android app will show an "Action Required" UI.
4.  Submitting an action (approve/reject) will send a Signal to Temporal and resume the workflow.

### 2. Firebase Ownership
Tasks are linked to the Firebase UID. One user cannot see or cancel another user's tasks.
