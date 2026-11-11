"""SQLAlchemy models for the Phase 2 multi-tenant schema.

Every user-owned table carries user_id with ON DELETE CASCADE, UTC
timestamps (created_at + updated_at) and a UUID primary key. Migrations in
migrations/ hold the authoritative Supabase SQL (with RLS policies); these
models mirror it and double as the test-schema source.
"""

import uuid
from datetime import datetime, timezone

from sqlalchemy import JSON, Boolean, DateTime, Float, ForeignKey, Index, Integer, String, Text, Uuid
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
    # Managed (Mina-provisioned) keys have no owning user; BYOK keys do.
    user_id: Mapped[uuid.UUID | None] = mapped_column(
        Uuid, ForeignKey("profiles.id", ondelete="CASCADE"), nullable=True
    )
    provider: Mapped[str] = mapped_column(String(60), nullable=False)
    label: Mapped[str | None] = mapped_column(String(120))
    # Envelope encryption: key_value is the data ciphertext (Fernet token),
    # wrapped_dek is the DEK wrapped under the KEK version in key_version.
    # Rows from Phase 2 (wrapped_dek NULL) are legacy single-key Fernet.
    key_value: Mapped[str] = mapped_column(Text, nullable=False)
    key_version: Mapped[int] = mapped_column(Integer, default=1, nullable=False)
    wrapped_dek: Mapped[str | None] = mapped_column(Text)
    source: Mapped[str] = mapped_column(String(20), default="byok", nullable=False)  # byok | managed
    status: Mapped[str] = mapped_column(String(20), default="active", nullable=False)  # active | disabled
    last_tested_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    last_test_ok: Mapped[bool | None] = mapped_column(Boolean)
    last_test_error: Mapped[str | None] = mapped_column(Text)
    last_test_latency_ms: Mapped[int | None] = mapped_column(Integer)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())

    __table_args__ = (Index("ix_provider_keys_user_provider", "user_id", "provider"),)


class UserAISettings(Base):
    __tablename__ = "user_ai_settings"

    user_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("profiles.id", ondelete="CASCADE"), primary_key=True
    )
    # byok_only | byok_first | managed_only
    policy: Mapped[str] = mapped_column(String(20), default="byok_only", nullable=False)
    # byok_first falls back to Mina-managed keys only when the user turned
    # this on themselves; it is never assumed.
    allow_managed_fallback: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    # Phase 4: user-configurable model preferences (nullable = no pin).
    preferred_model: Mapped[str | None] = mapped_column(Text)
    preferred_group: Mapped[str | None] = mapped_column(String(20))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())


class ProviderHealth(Base):
    __tablename__ = "provider_health"

    id: Mapped[uuid.UUID] = _pk()
    user_id: Mapped[uuid.UUID | None] = mapped_column(
        Uuid, ForeignKey("profiles.id", ondelete="CASCADE"), nullable=True
    )
    provider: Mapped[str] = mapped_column(String(60), nullable=False)
    ok: Mapped[bool] = mapped_column(Boolean, nullable=False)
    latency_ms: Mapped[int | None] = mapped_column(Integer)
    # Detail strings are fixed phrases; they never contain key material.
    detail: Mapped[str | None] = mapped_column(Text)
    checked_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())

    __table_args__ = (Index("ix_provider_health_provider_checked", "provider", "checked_at"),)


class ComposioConnection(Base):
    __tablename__ = "composio_connections"

    id: Mapped[uuid.UUID] = _pk()
    user_id: Mapped[uuid.UUID] = _user_fk()
    provider: Mapped[str] = mapped_column(String(60), nullable=False)
    account_id: Mapped[str | None] = mapped_column(String(160))
    status: Mapped[str] = mapped_column(String(30), default="connected", nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())


class ComposioExecution(Base):
    """One validated Composio tool execution, logged without secrets.

    Only metadata is stored: the SHA-256 fingerprint of the arguments and
    the argument key names. Argument values, provider tokens and anything
    else secret never reach this table; the result is a length-capped
    excerpt, because tool output is untrusted content.
    """

    __tablename__ = "composio_executions"

    id: Mapped[uuid.UUID] = _pk()
    user_id: Mapped[uuid.UUID] = _user_fk()
    tool_slug: Mapped[str] = mapped_column(String(160), nullable=False)
    connected_account_id: Mapped[str | None] = mapped_column(String(160))
    args_fingerprint: Mapped[str] = mapped_column(String(64), nullable=False)
    argument_keys: Mapped[list] = mapped_column(JSON, default=list, nullable=False)
    status: Mapped[str] = mapped_column(String(20), default="running", nullable=False)
    successful: Mapped[bool | None] = mapped_column(Boolean)
    result_excerpt: Mapped[str | None] = mapped_column(Text)
    result_truncated: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    error: Mapped[str | None] = mapped_column(Text)
    latency_ms: Mapped[int | None] = mapped_column(Integer)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())

    __table_args__ = (
        Index("ix_composio_executions_user_created", "user_id", "created_at"),
    )


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


class DurableTask(Base):
    """Phase 9 durable background task. Survives process restarts;
    workers claim it with a lease, checkpoint progress, retry with
    backoff and dead-letter on repeated failure."""

    __tablename__ = "durable_tasks"

    id: Mapped[uuid.UUID] = _pk()
    user_id: Mapped[uuid.UUID] = _user_fk()
    kind: Mapped[str] = mapped_column(String(50), default="legacy", nullable=False)
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    payload: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)
    status: Mapped[str] = mapped_column(String(30), default="QUEUED", nullable=False)
    idempotency_key: Mapped[str | None] = mapped_column(String(200), nullable=True)
    attempts: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    max_attempts: Mapped[int] = mapped_column(Integer, default=3, nullable=False)
    scheduled_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    run_after: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    lease_owner: Mapped[str | None] = mapped_column(String(120), nullable=True)
    lease_expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    checkpoint: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    progress: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    result: Mapped[str | None] = mapped_column(Text)
    error: Mapped[str | None] = mapped_column(Text)
    cancel_requested: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    pause_requested: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    requires_approval: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())

    __table_args__ = (
        Index("ix_durable_tasks_status_run_after", "status", "run_after"),
        Index("ix_durable_tasks_user_status", "user_id", "status"),
        Index("uq_durable_tasks_idempotency", "user_id", "idempotency_key", unique=True),
    )


class TaskEvent(Base):
    """Append-only progress events for one durable task. The (task_id,
    seq) pair is the SSE event id clients reconnect from."""

    __tablename__ = "task_events"

    id: Mapped[uuid.UUID] = _pk()
    task_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("durable_tasks.id", ondelete="CASCADE"), nullable=False)
    user_id: Mapped[uuid.UUID] = _user_fk()
    seq: Mapped[int] = mapped_column(Integer, nullable=False)
    type: Mapped[str] = mapped_column(String(50), nullable=False)
    data: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())

    __table_args__ = (
        Index("uq_task_events_task_seq", "task_id", "seq", unique=True),
        Index("ix_task_events_user", "user_id"),
    )


class Memory(Base):
    """Phase 8 long-term memory. The Phase 7 columns (kind, content,
    timestamps) are unchanged; every later column is nullable or has a
    default so legacy rows keep working."""

    __tablename__ = "memories"

    id: Mapped[uuid.UUID] = _pk()
    user_id: Mapped[uuid.UUID] = _user_fk()
    kind: Mapped[str] = mapped_column(String(30), default="fact", nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    # Phase 8 columns.
    confidence: Mapped[float] = mapped_column(Float, default=0.5, nullable=False)
    source: Mapped[str] = mapped_column(String(20), default="user_stated", nullable=False)
    source_ref: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    status: Mapped[str] = mapped_column(String(20), default="active", nullable=False)
    superseded_by: Mapped[uuid.UUID | None] = mapped_column(Uuid, nullable=True)
    user_approved: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    last_accessed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    # Stored as a plain float list so the same model serves SQLite tests
    # and Postgres; the migration adds the native pgvector column.
    embedding: Mapped[list | None] = mapped_column(JSON, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())

    __table_args__ = (Index("ix_memories_user_status", "user_id", "status"),)


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


class OptimizerEvent(Base):
    """Phase 5: one measured optimizer event per optimized model request."""

    __tablename__ = "optimizer_events"

    id = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    user_id = _user_fk()
    model_id = mapped_column(String(120), nullable=False)
    task_type = mapped_column(String(60), nullable=False)
    tokens_before = mapped_column(Integer, nullable=False)
    tokens_after = mapped_column(Integer, nullable=False)
    output_tokens = mapped_column(Integer, nullable=False, default=0)
    cache_hit = mapped_column(String(16), nullable=True)  # exact | semantic
    cache_saved_tokens = mapped_column(Integer, nullable=False, default=0)
    est_savings_usd = mapped_column(Integer, nullable=False, default=0)  # micro-USD
    latency_ms = mapped_column(Integer, nullable=False, default=0)
    quality_failure = mapped_column(Boolean, nullable=False, default=False)
    failure_reason = mapped_column(Text, nullable=True)
    created_at = mapped_column(DateTime(timezone=True), default=utcnow, nullable=False)
    updated_at = mapped_column(DateTime(timezone=True), default=utcnow, onupdate=utcnow, nullable=False)


class SemanticCacheEntry(Base):
    """Phase 5: persistent semantic response cache (PostgreSQL/pgvector in
    production; embeddings stored as JSON vectors, KNN scoped by
    scope_hash = user|model|policy)."""

    __tablename__ = "semantic_cache_entries"

    id = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    scope_hash = mapped_column(String(200), nullable=False, index=True)
    embedding = mapped_column(JSON, nullable=False)
    response = mapped_column(JSON, nullable=False)
    prompt_preview = mapped_column(Text, nullable=False)
    input_tokens = mapped_column(Integer, nullable=False)
    created_at = mapped_column(DateTime(timezone=True), default=utcnow, nullable=False)
    expires_at = mapped_column(DateTime(timezone=True), nullable=True)


class AgentRun(Base):
    """One orchestration run of the Phase 7 agent.

    `payload` holds the full resumable run state: the structured plan,
    per-step status, counters and limits. A run that reaches a waiting
    checkpoint (approval, device result, pause) persists here and is
    continued later without losing state.
    """

    __tablename__ = "agent_runs"

    id: Mapped[uuid.UUID] = _pk()
    user_id: Mapped[uuid.UUID] = _user_fk()
    conversation_id: Mapped[uuid.UUID | None] = mapped_column(
        Uuid, ForeignKey("conversations.id", ondelete="SET NULL")
    )
    mode: Mapped[str] = mapped_column(String(20), default="ask", nullable=False)
    status: Mapped[str] = mapped_column(String(30), default="running", nullable=False)
    input_text: Mapped[str] = mapped_column(Text, nullable=False)
    goal: Mapped[str | None] = mapped_column(Text)
    payload: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)
    final_response: Mapped[str | None] = mapped_column(Text)
    error: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())

    __table_args__ = (Index("ix_agent_runs_user_created", "user_id", "created_at"),)


class AgentEvent(Base):
    """One stable trace event of an agent run."""

    __tablename__ = "agent_events"

    id: Mapped[uuid.UUID] = _pk()
    run_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("agent_runs.id", ondelete="CASCADE"), nullable=False, index=True
    )
    user_id: Mapped[uuid.UUID] = _user_fk()
    seq: Mapped[int] = mapped_column(Integer, nullable=False)
    type: Mapped[str] = mapped_column(String(40), nullable=False)
    payload: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())

    __table_args__ = (
        Index("ix_agent_events_run_seq", "run_id", "seq", unique=True),
    )
