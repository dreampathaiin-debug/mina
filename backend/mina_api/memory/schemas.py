"""Pydantic schemas for the memory API and model-generated JSON."""

from __future__ import annotations

from datetime import datetime
from uuid import UUID
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

MemoryKindIn = Literal["working", "episodic", "semantic", "procedural"]
MemoryStatusIn = Literal["active", "superseded", "expired", "deleted"]


class MemoryCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    content: str = Field(min_length=1, max_length=4000)
    kind: MemoryKindIn = "semantic"
    source: Literal["user_stated", "extracted", "system"] = "user_stated"
    confidence: float = Field(default=0.95, ge=0.0, le=1.0)
    source_ref: dict | None = None
    user_approved: bool = False
    reason: str | None = Field(default=None, max_length=500)
    expires_at: datetime | None = None


class MemoryUpdate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    content: str | None = Field(default=None, min_length=1, max_length=4000)
    kind: MemoryKindIn | None = None
    confidence: float | None = Field(default=None, ge=0.0, le=1.0)
    status: MemoryStatusIn | None = None
    expires_at: datetime | None = None


class MemorySearchIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    query: str = Field(min_length=1, max_length=2000)
    k: int = Field(default=8, ge=1, le=50)
    kinds: list[MemoryKindIn] | None = None
    min_confidence: float | None = Field(default=None, ge=0.0, le=1.0)


class MemoryCandidate(BaseModel):
    """One memory the extractor found in conversation text.

    `confirmed` is false for anything the model inferred rather than
    heard the user state directly; guesses never become facts here.
    """

    model_config = ConfigDict(extra="forbid")

    content: str = Field(min_length=1, max_length=2000)
    kind: MemoryKindIn = "semantic"
    confirmed: bool = False
    source_ref: dict | None = None


class MemoryOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID
    kind: str
    content: str
    confidence: float
    source: str
    source_ref: dict | None
    status: str
    user_approved: bool
    created_at: datetime
    updated_at: datetime
    expires_at: datetime | None
    last_accessed_at: datetime | None


class MemorySearchOut(BaseModel):
    id: str
    kind: str
    content: str
    confidence: float
    score: float
    source: str
    user_approved: bool
    updated_at: datetime
