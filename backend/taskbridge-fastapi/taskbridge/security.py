"""Security and access control abstractions."""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from .models import AuthContext, TaskAttachment, TaskRecord


class AuthContextResolver(ABC):
    """Interface for resolving authentication context from requests."""

    @abstractmethod
    async def resolve_auth_context(self, request_context: object) -> AuthContext:
        """Resolve the security context from an incoming request.

        Args:
            request_context: The raw request object (e.g. FastAPI Request or WebSocket).

        Returns:
            The resolved authentication context.

        """
        raise NotImplementedError


class OwnershipPolicy(ABC):
    """Interface for enforcing task ownership and access rules."""

    @abstractmethod
    async def assert_task_create(self, auth_context: AuthContext) -> None:
        """Verify that the user is allowed to create a new task.

        Args:
            auth_context: Authenticated user context.

        Raises:
            PermissionError: If creation is denied.

        """
        raise NotImplementedError

    @abstractmethod
    async def assert_task_access(self, auth_context: AuthContext, task: TaskRecord) -> None:
        """Verify that the user is allowed to access an existing task.

        Args:
            auth_context: Authenticated user context.
            task: The task record to access.

        Raises:
            PermissionError: If access is denied.

        """
        raise NotImplementedError


class DenyAllAccessPolicy(OwnershipPolicy):
    """Policy that unconditionally denies all task creation and access."""

    async def assert_task_create(self, auth_context: AuthContext) -> None:
        """Unconditionally deny task creation.

        Args:
            auth_context: Authenticated user context.

        Raises:
            PermissionError: Always raised.

        """
        raise PermissionError(f"Task creation denied for {auth_context.subject}")

    async def assert_task_access(self, auth_context: AuthContext, task: TaskRecord) -> None:
        """Unconditionally deny task access.

        Args:
            auth_context: Authenticated user context.
            task: The task record to access.

        Raises:
            PermissionError: Always raised.

        """
        del task
        raise PermissionError(f"Task access denied for {auth_context.subject}")


class UploadPolicy(ABC):
    """Interface for validating file uploads for tasks."""

    @abstractmethod
    async def assert_upload_allowed(
        self,
        auth_context: AuthContext,
        attachments: list[TaskAttachment],
    ) -> None:
        """Verify that the requested file uploads are allowed.

        Args:
            auth_context: Authenticated user context.
            attachments: List of attachments to validate.

        Raises:
            PermissionError: If upload is denied.

        """
        raise NotImplementedError


class AllowAllUploadPolicy(UploadPolicy):
    """Policy that unconditionally allows all file uploads."""

    async def assert_upload_allowed(
        self,
        auth_context: AuthContext,
        attachments: list[TaskAttachment],
    ) -> None:
        """Unconditionally allow file uploads.

        Args:
            auth_context: Authenticated user context.
            attachments: List of attachments to validate.

        """
        del auth_context, attachments
