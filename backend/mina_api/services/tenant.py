"""Explicit tenant authorization helpers (defense in depth with RLS).

The backend always connects at service level, so every query must filter
by the verified user's id itself; RLS on the tables is the second layer,
never the only one. These helpers centralize that rule so no endpoint
forgets it.
"""

from __future__ import annotations

import uuid
from typing import Any

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

_NOT_FOUND = HTTPException(status_code=404, detail="Not found")


async def get_owned(db: AsyncSession, model: Any, object_id: uuid.UUID, user_id: uuid.UUID) -> Any:
    """Fetch a row owned by this exact user, else 404 (never 403: the
    existence of another tenant's row is itself information)."""
    row = await db.get(model, object_id)
    if row is None or row.user_id != user_id:
        raise _NOT_FOUND
    return row


async def list_owned(db: AsyncSession, model: Any, user_id: uuid.UUID, limit: int = 50) -> list[Any]:
    result = await db.execute(
        select(model).where(model.user_id == user_id).order_by(model.created_at.desc()).limit(limit)
    )
    return list(result.scalars().all())
