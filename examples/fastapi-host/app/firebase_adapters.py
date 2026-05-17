from __future__ import annotations

import logging
from typing import Any

from fastapi import Request, HTTPException
from firebase_admin import auth, credentials, initialize_app
from taskbridge.models import AuthContext, TaskRecord
from taskbridge.security import AuthContextResolver, OwnershipPolicy

logger = logging.getLogger(__name__)

class FirebaseAuthResolver(AuthContextResolver):
    """Resolves AuthContext using Firebase ID tokens from Authorization header."""

    def __init__(self, app_name: str | None = None):
        self._app_name = app_name
        try:
            # Initialize default app if not already initialized
            initialize_app()
        except ValueError:
            pass

    async def resolve_auth_context(self, request_context: Any) -> AuthContext:
        # In FastAPI, request_context is the Request object
        if not isinstance(request_context, Request):
            logger.warning("FirebaseAuthResolver: context is not a Request")
            return AuthContext(subject="anonymous")

        auth_header = request_context.headers.get("Authorization")
        if not auth_header or not auth_header.startswith("Bearer "):
            return AuthContext(subject="anonymous")

        id_token = auth_header.split("Bearer ")[1]
        try:
            decoded_token = auth.verify_id_token(id_token)
            uid = decoded_token["uid"]
            return AuthContext(
                subject=uid,
                scopes={"tasks:all"},
                attributes=decoded_token
            )
        except Exception as e:
            logger.error(f"Firebase auth failed: {e}")
            raise HTTPException(status_code=401, detail="Invalid Firebase token")

class FirebaseOwnershipPolicy(OwnershipPolicy):
    """Enforces that users can only access their own tasks based on Firebase UID."""

    async def assert_task_create(self, auth_context: AuthContext) -> None:
        if auth_context.subject == "anonymous":
            raise PermissionError("Anonymous users cannot create tasks")

    async def assert_task_access(self, auth_context: AuthContext, task: TaskRecord) -> None:
        if auth_context.subject == "anonymous":
            raise PermissionError("Anonymous access denied")
        
        if task.owner_id != auth_context.subject:
            logger.warning(f"User {auth_context.subject} tried to access task {task.task_id} owned by {task.owner_id}")
            raise PermissionError(f"Access to task {task.task_id} denied")
