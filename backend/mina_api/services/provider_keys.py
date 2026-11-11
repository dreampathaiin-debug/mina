"""Provider key service: create, rotate, disable, delete, test, and the
policy-aware key selection that model routing will consume in a later phase."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Optional
from uuid import UUID

import httpx
from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from mina_api.database.models import ProviderHealth, ProviderKey, UserAISettings
from mina_api.providers.registry import ProviderRegistry
from mina_api.providers.validation import check_connectivity
from mina_api.security.crypto import EnvelopeCrypto


def mask_preview(raw_key: str) -> str:
    return "***" + raw_key[-4:]


def mask_stored(row: ProviderKey, crypto: EnvelopeCrypto) -> str:
    plaintext = decrypt_stored(row, crypto)
    if plaintext is None:  # undecryptable: still never echo stored ciphertext shape
        return "***"
    return mask_preview(plaintext)


def decrypt_stored(row: ProviderKey, crypto: EnvelopeCrypto) -> Optional[str]:
    if row.wrapped_dek:
        return crypto.decrypt(row.key_version, row.wrapped_dek, row.key_value)
    return crypto.decrypt_legacy(row.key_value)


async def create_key(
    db: AsyncSession,
    crypto: EnvelopeCrypto,
    registry: ProviderRegistry,
    user_id: UUID,
    provider: str,
    raw_key: str,
    label: str | None = None,
) -> ProviderKey:
    if registry.get(provider) is None:
        raise HTTPException(status_code=422, detail=f"Unknown provider: {provider}")
    format_ok = registry.format_ok(provider, raw_key)
    if format_ok is False:
        raise HTTPException(status_code=422, detail=f"Key does not match {provider}'s format")
    key_version, wrapped_dek, data_token = crypto.encrypt(raw_key)
    row = ProviderKey(
        user_id=user_id,
        provider=provider.lower(),
        label=label,
        key_value=data_token,
        key_version=key_version,
        wrapped_dek=wrapped_dek,
        source="byok",
        status="active",
    )
    db.add(row)
    await db.commit()
    await db.refresh(row)
    return row


async def rotate_key(db: AsyncSession, crypto: EnvelopeCrypto, row: ProviderKey, new_raw: str) -> ProviderKey:
    key_version, wrapped_dek, data_token = crypto.encrypt(new_raw)
    row.key_value = data_token
    row.key_version = key_version
    row.wrapped_dek = wrapped_dek
    # A rotated key's old test result no longer describes it.
    row.last_tested_at = None
    row.last_test_ok = None
    row.last_test_error = None
    row.last_test_latency_ms = None
    await db.commit()
    await db.refresh(row)
    return row


async def set_status(db: AsyncSession, row: ProviderKey, status: str) -> ProviderKey:
    row.status = status
    await db.commit()
    await db.refresh(row)
    return row


async def run_test(
    db: AsyncSession,
    registry: ProviderRegistry,
    row: ProviderKey,
    crypto: EnvelopeCrypto,
    http: httpx.AsyncClient,
) -> dict[str, object]:
    spec = registry.get(row.provider)
    if spec is None:
        raise HTTPException(status_code=422, detail=f"Unknown provider: {row.provider}")
    raw = decrypt_stored(row, crypto)
    if raw is None:
        # Encryption material mismatch: never echo the stored value.
        result = {"ok": False, "latency_ms": None, "detail": "key could not be decrypted on this server"}
    else:
        result = await check_connectivity(spec, raw, http)
    row.last_tested_at = datetime.now(timezone.utc)
    row.last_test_ok = bool(result["ok"])
    row.last_test_error = None if result["ok"] else str(result["detail"])
    row.last_test_latency_ms = result["latency_ms"]  # type: ignore[assignment]
    db.add(
        ProviderHealth(
            user_id=row.user_id,
            provider=row.provider,
            ok=bool(result["ok"]),
            latency_ms=result["latency_ms"],  # type: ignore[arg-type]
            detail=str(result["detail"]),
            checked_at=row.last_tested_at,
        )
    )
    await db.commit()
    await db.refresh(row)
    return {**result, "tested_at": row.last_tested_at}


async def provision_managed_keys(
    db: AsyncSession, crypto: EnvelopeCrypto, managed: dict[str, str]
) -> int:
    """Idempotent: seed Mina-managed keys from server configuration. Managed
    rows have no owning user and are reachable only through the service."""
    created = 0
    for provider, raw in managed.items():
        existing = await db.execute(
            select(ProviderKey).where(
                ProviderKey.provider == provider.lower(), ProviderKey.source == "managed"
            )
        )
        if existing.scalars().first() is not None:
            continue
        key_version, wrapped_dek, data_token = crypto.encrypt(raw)
        db.add(
            ProviderKey(
                user_id=None,
                provider=provider.lower(),
                label="managed",
                key_value=data_token,
                key_version=key_version,
                wrapped_dek=wrapped_dek,
                source="managed",
                status="active",
            )
        )
        created += 1
    if created:
        await db.commit()
    return created


async def get_or_default_settings(db: AsyncSession, user_id: UUID) -> UserAISettings:
    result = await db.execute(select(UserAISettings).where(UserAISettings.user_id == user_id))
    settings_row = result.scalars().first()
    if settings_row is None:
        settings_row = UserAISettings(user_id=user_id)
        db.add(settings_row)
        await db.commit()
        await db.refresh(settings_row)
    return settings_row


async def select_keys_for_request(
    db: AsyncSession, user_id: UUID, provider: str
) -> list[ProviderKey]:
    """The single place key priority is decided. Default policy is byok_only,
    so Mina-managed credits are never charged unless the user said so."""
    settings_row = await get_or_default_settings(db, user_id)
    byok = (
        await db.execute(
            select(ProviderKey).where(
                ProviderKey.user_id == user_id,
                ProviderKey.provider == provider.lower(),
                ProviderKey.source == "byok",
                ProviderKey.status == "active",
            )
        )
    ).scalars().all()
    if settings_row.policy == "byok_only":
        return list(byok)
    managed = (
        await db.execute(
            select(ProviderKey).where(
                ProviderKey.provider == provider.lower(),
                ProviderKey.source == "managed",
                ProviderKey.status == "active",
            )
        )
    ).scalars().all()
    if settings_row.policy == "managed_only":
        return list(managed)
    # byok_first: managed keys only when the user explicitly opted in.
    if settings_row.allow_managed_fallback:
        return list(byok) + list(managed)
    return list(byok)
