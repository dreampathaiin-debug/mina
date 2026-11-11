"""Memory API: list, create, update, delete, search.

Every endpoint is tenant-scoped through the service layer; a memory id
that belongs to another user is indistinguishable from one that does
not exist.
"""

from __future__ import annotations

from uuid import UUID

import fastapi
from fastapi import APIRouter, Depends, HTTPException, Query, Request
from sqlalchemy.ext.asyncio import AsyncSession

from mina_api.database.session import get_db
from mina_api.memory import safety
from mina_api.memory.schemas import MemoryCreate, MemoryOut, MemorySearchIn, MemorySearchOut, MemoryUpdate
from mina_api.memory.service import MemoryError, MemoryNotFound, MemoryService
from mina_api.security.auth import CurrentUser

router = APIRouter(prefix="/memory", tags=["memory"])


def _service(request) -> MemoryService:
    return request.app.state.memory_service


@router.get("", response_model=list[MemoryOut])
async def list_memories(
    user: CurrentUser,
    request: Request,
    kind: str | None = None,
    limit: int = Query(default=100, ge=1, le=500),
    db: AsyncSession = Depends(get_db),
) -> list:
    return await _service(request).list_memories(db, user.id, kind=kind, limit=limit)


@router.post("", response_model=MemoryOut, status_code=201)
async def create_memory(
    payload: MemoryCreate,
    user: CurrentUser,
    request: Request,
    db: AsyncSession = Depends(get_db),
) -> dict:
    try:
        row, _created = await _service(request).create(
            db, user.id, payload.content,
            kind=payload.kind,
            source=payload.source,
            confidence=payload.confidence,
            source_ref=payload.source_ref,
            user_approved=payload.user_approved,
            reason=payload.reason,
            expires_at=payload.expires_at,
        )
    except safety.SecretDetectedError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except safety.SensitiveWithoutPermissionError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except MemoryError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    return row


@router.patch("/{memory_id}", response_model=MemoryOut)
async def update_memory(
    memory_id: UUID,
    payload: MemoryUpdate,
    user: CurrentUser,
    request: Request,
    db: AsyncSession = Depends(get_db),
) -> dict:
    try:
        row = await _service(request).update(
            db, user.id, memory_id,
            content=payload.content,
            kind=payload.kind,
            confidence=payload.confidence,
            status=payload.status,
            expires_at=payload.expires_at,
        )
    except safety.SecretDetectedError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except safety.SensitiveWithoutPermissionError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except MemoryNotFound:
        raise HTTPException(status_code=404, detail="memory not found") from None
    return row


@router.delete("/{memory_id}")
async def delete_memory(
    memory_id: UUID,
    user: CurrentUser,
    request: Request,
    purge: bool = Query(default=False),
    db: AsyncSession = Depends(get_db),
) -> dict:
    try:
        return await _service(request).delete(db, user.id, memory_id, purge=purge)
    except MemoryNotFound:
        raise HTTPException(status_code=404, detail="memory not found") from None


@router.post("/search", response_model=list[MemorySearchOut])
async def search_memories(
    payload: MemorySearchIn,
    user: CurrentUser,
    request: Request,
    db: AsyncSession = Depends(get_db),
) -> list:
    return await _service(request).search(
        db, user.id, payload.query, k=payload.k, kinds=payload.kinds,
        min_confidence=payload.min_confidence,
    )
