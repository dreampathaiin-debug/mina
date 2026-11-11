"""Phase 10 observability models: usage events, budgets, budget alerts.

Everything here is metadata only. Raw prompts, provider keys and OAuth
tokens never enter this layer.
"""

from __future__ import annotations

import uuid
from datetime import date, datetime

from sqlalchemy import (
    Boolean,
    Date,
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    UniqueConstraint,
    Uuid,
)
from sqlalchemy.orm import Mapped, mapped_column

from mina_api.database.models import (
    Base,
    _pk,
    _timestamps,
    _user_fk,
)


class UsageEvent(Base):
    """One recorded unit of usage: a model attempt, a tool execution or
    a task run. Costs are ESTIMATES (cost_basis='estimate'); actual
    provider billing is reconciled separately."""

    __tablename__ = "usage_events"

    id: Mapped[uuid.UUID] = _pk()
    kind: Mapped[str] = mapped_column(String(20), nullable=False)  # model_call | tool_execution
    user_id: Mapped[uuid.UUID | None] = mapped_column(
        Uuid, ForeignKey("profiles.id", ondelete="CASCADE"), nullable=True, index=True
    )  # nullable: system calls
    request_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    conversation_id: Mapped[uuid.UUID | None] = mapped_column(Uuid, nullable=True)
    task_id: Mapped[uuid.UUID | None] = mapped_column(Uuid, nullable=True)
    provider: Mapped[str | None] = mapped_column(String(50), nullable=True)
    model: Mapped[str | None] = mapped_column(String(80), nullable=True)
    selection_reason: Mapped[str | None] = mapped_column(String(80), nullable=True)
    input_tokens: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    output_tokens: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    estimated_cost_usd: Mapped[float | None] = mapped_column(Float, nullable=True)
    cost_basis: Mapped[str] = mapped_column(String(20), default="estimate", nullable=False)
    billing_source: Mapped[str | None] = mapped_column(String(20), nullable=True)  # byok | managed
    cache_hit: Mapped[str | None] = mapped_column(String(20), nullable=True)  # exact | semantic | none
    latency_ms: Mapped[int | None] = mapped_column(Integer, nullable=True)
    retry_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    fallback_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    tool_slug: Mapped[str | None] = mapped_column(String(120), nullable=True)
    tool_duration_ms: Mapped[int | None] = mapped_column(Integer, nullable=True)
    success: Mapped[bool | None] = mapped_column(Boolean, nullable=True)
    error_category: Mapped[str | None] = mapped_column(String(50), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())

    __table_args__ = (
        Index("ix_usage_events_user_created", "user_id", "created_at"),
        Index("ix_usage_events_kind_created", "kind", "created_at"),
        Index("ix_usage_events_provider_created", "provider", "created_at"),
    )


class UsageBudget(Base):
    """Per-user budget limits. NULL means 'no limit of this kind'."""

    __tablename__ = "usage_budgets"

    id: Mapped[uuid.UUID] = _pk()
    user_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("profiles.id", ondelete="CASCADE"), unique=True, nullable=False)
    daily_budget_usd: Mapped[float | None] = mapped_column(Float, nullable=True)
    monthly_budget_usd: Mapped[float | None] = mapped_column(Float, nullable=True)
    daily_token_limit: Mapped[int | None] = mapped_column(Integer, nullable=True)
    monthly_token_limit: Mapped[int | None] = mapped_column(Integer, nullable=True)
    concurrent_request_limit: Mapped[int | None] = mapped_column(Integer, nullable=True)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())

    __table_args__ = (Index("ix_usage_budgets_user", "user_id"),)


class BudgetAlert(Base):
    """One alert per (user, scope, threshold, day) so a user is warned
    before quota exhaustion without being spammed."""

    __tablename__ = "budget_alerts"

    id: Mapped[uuid.UUID] = _pk()
    user_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("profiles.id", ondelete="CASCADE"), nullable=False)
    scope: Mapped[str] = mapped_column(String(60), nullable=False)
    threshold_pct: Mapped[int] = mapped_column(Integer, nullable=False)
    alert_day: Mapped[date] = mapped_column(Date, nullable=False)
    message: Mapped[str] = mapped_column(String(300), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), **_timestamps())

    __table_args__ = (
        UniqueConstraint("user_id", "scope", "threshold_pct", "alert_day", name="uq_budget_alerts_dedupe"),
        Index("ix_budget_alerts_user", "user_id", "created_at"),
    )
