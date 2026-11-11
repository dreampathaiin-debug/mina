"""Pydantic schemas for the durable task API."""

from __future__ import annotations

from datetime import datetime
from typing import Any
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class TaskCreateIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    title: str = Field(min_length=1, max_length=200)
    payload: dict = Field(default_factory=dict)
    kind: str = Field(default="legacy", max_length=50)
    idempotency_key: str | None = Field(default=None, max_length=200)
    scheduled_at: datetime | None = None
    requires_approval: bool = False
    max_attempts: int | None = Field(default=None, ge=1, le=20)


class TaskOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    # Back-compatible fields (legacy task surface).
    id: UUID
    title: str
    payload: dict
    status: str
    result: str | None = None
    created_at: datetime
    # Phase 9 fields.
    kind: str = "legacy"
    error: str | None = None
    attempts: int = 0
    max_attempts: int = 3
    checkpoint: dict | None = None
    progress: dict | None = None
    scheduled_at: datetime | None = None
    finished_at: datetime | None = None
    cancel_requested: bool = False


class TaskEventOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID
    task_id: UUID
    seq: int
    type: str
    data: dict
    created_at: datetime
