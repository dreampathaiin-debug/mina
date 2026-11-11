"""Schemas for the provider key manager. Every response schema excludes key
material by construction; a client-sent user_id or source is never accepted."""

from __future__ import annotations

from datetime import datetime
from typing import Literal, Optional
from uuid import UUID

from pydantic import BaseModel, Field, field_validator

AIPolicy = Literal["byok_only", "byok_first", "managed_only"]


class ProviderKeyCreate(BaseModel):
    provider: str = Field(min_length=1, max_length=60)
    label: Optional[str] = Field(default=None, max_length=120)
    key: str = Field(min_length=8, max_length=512)
    # Only BYOK keys can be created through the API; managed keys are
    # provisioned server-side from configuration.
    is_byok: Literal[True] = True


class ProviderKeyRotate(BaseModel):
    key: str = Field(min_length=8, max_length=512)


class ProviderKeyStatus(BaseModel):
    status: Literal["active", "disabled"]


class ProviderKeyOut(BaseModel):
    id: UUID
    provider: str
    label: Optional[str]
    source: str
    status: str
    # Masked preview only: never the raw key, never a decryptable form.
    key_preview: str
    last_tested_at: Optional[datetime] = None
    last_test_ok: Optional[bool] = None
    last_test_error: Optional[str] = None
    last_test_latency_ms: Optional[int] = None
    created_at: datetime
    updated_at: datetime


class KeyTestOut(BaseModel):
    ok: bool
    latency_ms: Optional[int] = None
    detail: str
    tested_at: datetime


class ProviderOut(BaseModel):
    name: str
    display_name: str
    format_known: bool
    # Aggregated, most recent health check for this provider (any scope).
    last_test_ok: Optional[bool] = None
    last_tested_at: Optional[datetime] = None


class AIPolicyIn(BaseModel):
    policy: AIPolicy
    # Explicit opt-in for the byok_first fallback; absent means "leave as is".
    allow_managed_fallback: Optional[bool] = None

    @field_validator("allow_managed_fallback")
    @classmethod
    def fallback_needs_byok_first(cls, value: Optional[bool], info) -> Optional[bool]:
        policy = info.data.get("policy")
        if value is True and policy not in (None, "byok_first"):
            raise ValueError("allow_managed_fallback only applies to byok_first")
        return value


class AIPolicyOut(BaseModel):
    policy: AIPolicy
    allow_managed_fallback: bool
    updated_at: datetime
