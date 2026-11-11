"""Own-account endpoints: profile, tenant view, account deletion."""

from __future__ import annotations

import logging

import httpx
from fastapi import APIRouter, Depends, Request
from sqlalchemy import delete, update

from mina_api.database.models import (
    Approval,
    ComposioConnection,
    Conversation,
    Memory,
    Message,
    Profile,
    ProviderKey,
    Task,
    DurableTask,
    TaskEvent,
    UsageLog,
)
from mina_api.schemas.tenant import MeResponse
from datetime import datetime, timezone

from mina_api.security.auth import CurrentUser, get_db

logger = logging.getLogger("mina.account")
router = APIRouter(tags=["account"])

_OWNED_TABLES = (Message, Conversation, ProviderKey, ComposioConnection, Approval, Task, DurableTask, TaskEvent, Memory, UsageLog)


@router.get("/me", response_model=MeResponse, summary="Current user")
async def me(user: CurrentUser) -> Profile:
    return user


@router.delete("/account", status_code=204, summary="Delete account and all owned data")
async def delete_account(request: Request, user: CurrentUser, db=Depends(get_db)) -> None:
    """Hard-deletes every row the user owns, then soft-deletes the profile
    (its deleted_at makes every existing token worthless at the next
    request). When the Supabase service key is configured the auth user is
    deleted too, via the admin API with the server-only key."""
    settings = request.app.state.settings
    for model in _OWNED_TABLES:
        await db.execute(delete(model).where(model.user_id == user.id))
    await db.execute(update(Profile).where(Profile.id == user.id).values(deleted_at=datetime.now(timezone.utc)))
    await db.commit()

    if settings.supabase_url and settings.supabase_service_key:
        try:
            async with httpx.AsyncClient(timeout=10) as client:
                await client.delete(
                    f"{settings.supabase_url}/auth/v1/admin/users/{user.id}",
                    headers={"apikey": settings.supabase_service_key, "Authorization": f"Bearer {settings.supabase_service_key}"},
                )
        except Exception:
            logger.warning("supabase_admin_delete_failed", extra={"event": "supabase_admin_delete_failed", "method": "DELETE", "path": "/v1/account"})
