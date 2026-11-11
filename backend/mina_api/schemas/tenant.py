"""Request/response schemas for Phase 2 tenant endpoints.

Note what is absent: user_id. Identity comes only from the verified
token; no schema accepts or trusts a client-supplied user_id.
"""

from datetime import datetime
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, Field


class MeResponse(BaseModel):
    id: UUID
    email: str | None = None
    display_name: str | None = None
    role: str
    created_at: datetime


class ConversationCreate(BaseModel):
    title: str | None = Field(default=None, max_length=200)


class ConversationOut(BaseModel):
    id: UUID
    title: str | None
    created_at: datetime

    model_config = {"from_attributes": True}


MessageRole = Literal["user", "assistant", "system"]


class MessageCreate(BaseModel):
    role: MessageRole = "user"
    content: str = Field(min_length=1, max_length=32000)


class MessageOut(BaseModel):
    id: UUID
    role: MessageRole
    content: str
    created_at: datetime

    model_config = {"from_attributes": True}




class TaskCreate(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    payload: dict = Field(default_factory=dict)


class TaskOut(BaseModel):
    id: UUID
    title: str
    payload: dict
    status: str
    result: str | None
    created_at: datetime

    model_config = {"from_attributes": True}