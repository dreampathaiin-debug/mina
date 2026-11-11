"""Provider API keys (BYOK): stored per tenant, never returned in full."""

from __future__ import annotations

from uuid import UUID

from fastapi import APIRouter, Depends, Request, Response
from sqlalchemy.ext.asyncio import AsyncSession

from mina_api.database.models import ProviderKey
from mina_api.database.session import get_db
from mina_api.schemas.tenant import ProviderKeyCreate, ProviderKeyOut
from mina_api.security.auth import CurrentUser
from mina_api.services.tenant import get_owned, list_owned

router = APIRouter(prefix="/provider-keys", tags=["provider-keys"])


def _mask(key: ProviderKey) -> ProviderKeyOut:
    return ProviderKeyOut(
        id=key.id,
        provider=key.provider,
        label=key.label,
        key_preview="***" + key.key_value[-4:],
        is_active=key.is_active,
        created_at=key.created_at,
    )


@router.post("", response_model=ProviderKeyOut, status_code=201)
async def create_provider_key(
    payload: ProviderKeyCreate, request: Request, user: CurrentUser, db: AsyncSession = Depends(get_db)
) -> ProviderKeyOut:
    box = request.app.state.secrets
    row = ProviderKey(
        user_id=user.id,
        provider=payload.provider,
        label=payload.label,
        key_value=box.encrypt(payload.key),
    )
    db.add(row)
    await db.commit()
    await db.refresh(row)
    return _mask(row)


@router.get("", response_model=list[ProviderKeyOut])
async def list_provider_keys(
    user: CurrentUser, db: AsyncSession = Depends(get_db)
) -> list[ProviderKeyOut]:
    return [_mask(key) for key in await list_owned(db, ProviderKey, user.id)]


@router.delete("/{key_id}", status_code=204)
async def delete_provider_key(
    key_id: UUID, user: CurrentUser, db: AsyncSession = Depends(get_db)
) -> Response:
    key = await get_owned(db, ProviderKey, key_id, user.id)
    await db.delete(key)
    await db.commit()
    return Response(status_code=204)
