"""SQLAlchemy models for the Phase 2 multi-tenant schema.

Every user-owned table carries user_id with ON DELETE CASCADE, UTC
timestamps (created_at + updated_at) and a UUID primary key. Migrations in
migrations/ hold the authoritative Supabase SQL (with RLS policies); these
models mirror it and double as the test-schema source.
"""

import uuid
from datetime import datetime, timezone

from sqlalchemy import JSON, Boolean, DateTime, ForeignKey, Index, Integer, String, Text, Uuid
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class Base(DeclarativeBase):
    pass


def _pk() -> Mapped[uuid.UUID]:
    return mapped_column(Uuid, primary_key=True, default=uuid.uuid4)


def _user_fk() -> Mapped[uuid.UUID]:
    return mapped_column(
        Uuid, ForeignKey("profiles.id", ondelete="CASCADE"), nullable=False, index=True
    )


def _timestamps() -> dict:
    return {"default": utcnow, "onupdate": utcnow, "nullable": False}


class Profile(Base):
    __tablename__ = "profiles"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True)  # = auth.users.id
    email: Mapped[str | None] = mapped_column(String(255), index=True)
    display_name: Mapped[str | None] = mapped_column(String(120))
    role: Mapped[str] = mapped_column(String(20), default="user", nullable=False)
    deleted_at: Mapped[datetime | None]
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())

    @property
    def deleted(self) -> bool:
        return self.deleted_at is not None


class Conversation(Base):
    __tablename__ = "conversations"

    id: Mapped[uuid.UUID] = _pk()
    user_id: Mapped[uuid.UUID] = _user_fk()
    title: Mapped[str | None] = mapped_column(String(200))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())


class Message(Base):
    __tablename__ = "messages"

    id: Mapped[uuid.UUID] = _pk()
    conversation_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("conversations.id", ondelete="CASCADE"), nullable=False, index=True
    )
    user_id: Mapped[uuid.UUID] = _user_fk()
    role: Mapped[str] = mapped_column(String(20), nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())


class ProviderKey(Base):
    __tablename__ = "provider_keys"

    id: Mapped[uuid.UUID] = _pk()
    user_id: Mapped[uuid.UUID] = _user_fk()
    provider: Mapped[str] = mapped_column(String(60), nullable=False)
    label: Mapped[str | None] = mapped_column(String(120))
    # Fernet-encrypted when MINA_ENCRYPTION_KEY is set; plaintext otherwise.
    key_value: Mapped[str] = mapped_column(Text, nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())

    __table_args__ = (Index("ix_provider_keys_user_provider", "user_id", "provider"),)


class ComposioConnection(Base):
    __tablename__ = "composio_connections"

    id: Mapped[uuid.UUID] = _pk()
    user_id: Mapped[uuid.UUID] = _user_fk()
    provider: Mapped[str] = mapped_column(String(60), nullable=False)
    account_id: Mapped[str | None] = mapped_column(String(160))
    status: Mapped[str] = mapped_column(String(30), default="connected", nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())


class Task(Base):
    __tablename__ = "tasks"

    id: Mapped[uuid.UUID] = _pk()
    user_id: Mapped[uuid.UUID] = _user_fk()
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    payload: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)
    status: Mapped[str] = mapped_column(String(20), default="queued", nullable=False)
    result: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())

    __table_args__ = (Index("ix_tasks_user_status", "user_id", "status"),)


class Memory(Base):
    __tablename__ = "memories"

    id: Mapped[uuid.UUID] = _pk()
    user_id: Mapped[uuid.UUID] = _user_fk()
    kind: Mapped[str] = mapped_column(String(30), default="fact", nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())


class UsageLog(Base):
    __tablename__ = "usage_logs"

    id: Mapped[uuid.UUID] = _pk()
    user_id: Mapped[uuid.UUID] = _user_fk()
    model: Mapped[str | None] = mapped_column(String(80))
    prompt_tokens: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    completion_tokens: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    cost_cents: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())

    __table_args__ = (Index("ix_usage_logs_user_created", "user_id", "created_at"),)


class Approval(Base):
    __tablename__ = "approvals"

    id: Mapped[uuid.UUID] = _pk()
    user_id: Mapped[uuid.UUID] = _user_fk()
    task_id: Mapped[uuid.UUID | None] = mapped_column(
        Uuid, ForeignKey("tasks.id", ondelete="CASCADE"), index=True
    )
    status: Mapped[str] = mapped_column(String(20), default="pending", nullable=False)
    request: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())
