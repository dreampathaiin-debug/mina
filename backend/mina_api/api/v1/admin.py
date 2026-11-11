"""Admin-only endpoints (role-based authorization)."""

from __future__ import annotations

from pydantic import BaseModel
from datetime import datetime
from uuid import UUID
from typing import Optional

from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from mina_api.database.models import Profile
from mina_api.database.session import get_db
from mina_api.security.auth import AdminUser

router = APIRouter(prefix="/admin", tags=["admin"])


class AdminUserOut(BaseModel):
    id: UUID
    email: Optional[str]
    role: str
    deleted: bool
    created_at: datetime

    model_config = {"from_attributes": True}


@router.get("/users", response_model=list[AdminUserOut])
async def list_users(admin: AdminUser, db: AsyncSession = Depends(get_db)) -> list[Profile]:
    result = await db.execute(select(Profile).order_by(Profile.created_at.desc()).limit(200))
    return list(result.scalars().all())
