"""Module defining the host-provided task execution adapter."""

from __future__ import annotations

from abc import ABC, abstractmethod

from .models import TaskRecord


class TaskExecutor(ABC):
    """Host-provided execution adapter between TaskBridge services and a runtime.

    TaskBridge core stays ignorant of Temporal, Celery, queues, or process pools.
    The host implements this contract and wires it into FastAPI dependencies.

    Lifecycle:

    1. ``TaskCreationService.create_task`` persists the task via ``TaskRegistry``,
       then calls ``submit_task`` once for that ``TaskRecord``.
    2. The executor is responsible for driving work and **emitting** domain events
       by appending ``TaskEvent`` instances through the host's ``EventStore``
       (typically progress, completion, failure, cancellation outcomes).
    3. The executor should transition ``TaskRecord.status`` via ``TaskRegistry``
       in line with those events (e.g. ``RUNNING`` after start,
       ``COMPLETED`` / ``FAILED`` / ``CANCELLED`` on terminals).
    4. ``request_cancellation`` is invoked after ``TaskRegistry.request_cancellation``;
       implementations should cooperate asynchronously (signal a worker, cancel a
       workflow, set a flag checked by the task loop, etc.).

    Hooks (conceptual mapping for adapters):

    - **Submission:** ``submit_task``
    - **Progress:** emit ``TASK_PROGRESS`` (and optionally ``TASK_MESSAGE``) via ``EventStore``
    - **Completion:** registry terminal status + ``TASK_COMPLETED`` event
    - **Failure:** registry ``FAILED`` + ``TASK_FAILED`` event
    - **Cancel:** ``request_cancellation`` then terminal ``CANCELLED`` + ``TASK_CANCELLED``

    Implementations must not block the HTTP stack indefinitely; offload long work to
    background tasks, workers, or workflows owned by the host.
    """

    @abstractmethod
    async def submit_task(self, task: TaskRecord) -> None:
        """Accept a newly created task and begin asynchronous execution."""

    @abstractmethod
    async def request_cancellation(self, task: TaskRecord) -> None:
        """Request cooperative cancellation for a task already marked for cancel in the registry."""
