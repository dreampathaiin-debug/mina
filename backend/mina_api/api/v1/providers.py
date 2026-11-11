"""Provider key management API. Keys are created, rotated, disabled, deleted
and tested here; every response carries a masked preview only. Identity comes
from the verified token; a key owned by another tenant is simply not found."""

from __future__ import annotations

from datetime import datetime, timezone
from uuid import UUID

import httpx
from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from mina_api.database.models import ProviderHealth, ProviderKey, UserAISettings
from mina_api.database.session import get_db
from mina_api.schemas.providers import (
    AIPolicyIn,
    AIPolicyOut,
    KeyTestOut,
    ProviderKeyCreate,
    ProviderKeyOut,
    ProviderKeyRotate,
    ProviderKeyStatus,
    ProviderOut,
)
from mina_api.security.auth import CurrentUser
from mina_api.services import provider_keys as key_service
from mina_api.services.provider_keys import (
    create_key,
    get_or_default_settings,
    mask_stored,
    rotate_key,
    run_test,
    set_status,
)
from mina_api.services.tenant import get_owned, list_owned

router = APIRouter(tags=["providers"])


def _out(row: ProviderKey, request: Request) -> ProviderKeyOut:
    crypto = _crypto(request)
    return ProviderKeyOut(
        id=row.id,
        provider=row.provider,
        label=row.label,
        source=row.source,
        status=row.status,
        key_preview=mask_stored(row, crypto),
        last_tested_at=row.last_tested_at,
        last_test_ok=row.last_test_ok,
        last_test_error=row.last_test_error,
        last_test_latency_ms=row.last_test_latency_ms,
        created_at=row.created_at,
        updated_at=row.updated_at,
    )


def _crypto(request: Request):
    crypto = request.app.state.crypto
    if crypto is None:
        raise HTTPException(status_code=503, detail="Key encryption is not configured")
    return crypto


def _http(request: Request) -> httpx.AsyncClient:
    """Tests may pre-set a client on app state; otherwise one per request."""
    client = getattr(request.app.state, "http", None)
    return client or httpx.AsyncClient(timeout=10)


@router.post("/providers/keys", response_model=ProviderKeyOut, status_code=201)
async def create_provider_key(
    payload: ProviderKeyCreate, request: Request, user: CurrentUser, db: AsyncSession = Depends(get_db)
) -> ProviderKeyOut:
    row = await create_key(
        db,
        _crypto(request),
        request.app.state.registry,
        user.id,
        payload.provider,
        payload.key,
        payload.label,
    )
    return _out(row, request)


@router.get("/providers/keys", response_model=list[ProviderKeyOut])
async def list_provider_keys(
    request: Request,
    provider: str | None = None,
    user: CurrentUser = None,  # type: ignore[assignment]
    db: AsyncSession = Depends(get_db),
) -> list[ProviderKeyOut]:
    rows = await list_owned(db, ProviderKey, user.id, provider=provider)
    return [_out(row, request) for row in rows]


@router.patch("/providers/keys/{key_id}", response_model=ProviderKeyOut)
async def update_provider_key(
    key_id: UUID, payload: ProviderKeyStatus, request: Request, user: CurrentUser, db: AsyncSession = Depends(get_db)
) -> ProviderKeyOut:
    row = await get_owned(db, ProviderKey, key_id, user.id)
    await set_status(db, row, payload.status)
    return _out(row, request)


@router.delete("/providers/keys/{key_id}", status_code=204)
async def delete_provider_key(
    key_id: UUID, user: CurrentUser, db: AsyncSession = Depends(get_db)
) -> None:
    row = await get_owned(db, ProviderKey, key_id, user.id)
    await db.delete(row)
    await db.commit()


@router.post("/providers/keys/{key_id}/test", response_model=KeyTestOut)
async def test_provider_key(
    key_id: UUID, request: Request, user: CurrentUser, db: AsyncSession = Depends(get_db)
) -> KeyTestOut:
    row = await get_owned(db, ProviderKey, key_id, user.id)
    result = await run_test(db, request.app.state.registry, row, _crypto(request), _http(request))
    return KeyTestOut(
        ok=bool(result["ok"]),
        latency_ms=result.get("latency_ms"),  # type: ignore[arg-type]
        detail=str(result["detail"]),
        tested_at=result["tested_at"],  # type: ignore[arg-type]
    )


@router.post("/providers/keys/{key_id}/rotate", response_model=ProviderKeyOut)
async def rotate_provider_key(
    key_id: UUID, payload: ProviderKeyRotate, request: Request, user: CurrentUser, db: AsyncSession = Depends(get_db)
) -> ProviderKeyOut:
    row = await get_owned(db, ProviderKey, key_id, user.id)
    await rotate_key(db, _crypto(request), row, payload.key)
    return _out(row, request)


@router.get("/providers", response_model=list[ProviderOut])
async def list_providers(request: Request, user: CurrentUser, db: AsyncSession = Depends(get_db)) -> list[ProviderOut]:
    registry = request.app.state.registry
    health_rows = (
        await db.execute(
            select(ProviderHealth).order_by(ProviderHealth.checked_at.desc()).limit(300)
        )
    ).scalars().all()
    latest: dict[str, ProviderHealth] = {}
    for row in health_rows:
        latest.setdefault(row.provider, row)
    return [
        ProviderOut(
            name=spec.name,
            display_name=spec.display_name,
            format_known=spec.key_format is not None,
            last_test_ok=(latest[spec.name].ok if spec.name in latest else None),
            last_tested_at=(latest[spec.name].checked_at if spec.name in latest else None),
        )
        for spec in registry.all()
    ]


@router.get("/settings/ai-policy", response_model=AIPolicyOut)
async def get_ai_policy(user: CurrentUser, db: AsyncSession = Depends(get_db)) -> AIPolicyOut:
    settings_row = await get_or_default_settings(db, user.id)
    return AIPolicyOut(
        policy=settings_row.policy,  # type: ignore[arg-type]
        allow_managed_fallback=settings_row.allow_managed_fallback,
        updated_at=settings_row.updated_at,
    )


@router.patch("/settings/ai-policy", response_model=AIPolicyOut)
async def patch_ai_policy(
    payload: AIPolicyIn, user: CurrentUser, db: AsyncSession = Depends(get_db)
) -> AIPolicyOut:
    settings_row = await get_or_default_settings(db, user.id)
    settings_row.policy = payload.policy
    if payload.allow_managed_fallback is not None:
        settings_row.allow_managed_fallback = payload.allow_managed_fallback
    await db.commit()
    await db.refresh(settings_row)
    return AIPolicyOut(
        policy=settings_row.policy,  # type: ignore[arg-type]
        allow_managed_fallback=settings_row.allow_managed_fallback,
        updated_at=settings_row.updated_at,
    )
