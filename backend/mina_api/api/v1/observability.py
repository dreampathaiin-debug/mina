"""Phase 10 observability API: dashboard, usage events, budget limits."""

from __future__ import annotations

from datetime import date, datetime
from typing import Optional
from uuid import UUID

from fastapi import APIRouter, Depends, Query, Request
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from mina_api.database.models import Profile
from mina_api.database.session import get_db
from mina_api.observability.budgets import BudgetService
from mina_api.observability.metrics import build_dashboard
from mina_api.observability.models import BudgetAlert, UsageBudget, UsageEvent
from mina_api.security.auth import CurrentUser

router = APIRouter(prefix="/observability", tags=["observability"])
budget_router = APIRouter(prefix="/budgets", tags=["observability"])


def _is_admin(user: Profile) -> bool:
    return user.role == "admin"


class DashboardOut(BaseModel):
    window: dict
    total_requests: int
    active_users: Optional[int] = None
    requests_per_user: Optional[float] = None
    provider_usage: dict
    model_usage: dict
    token_consumption: dict
    estimated_managed_cost_usd: float
    estimated_byok_cost_usd: float
    estimated_total_cost_usd: float
    cost_note: str
    cost_estimates_available: bool
    cache_hit_rate: Optional[float] = None
    provider_failures: dict
    average_latency_ms: Optional[float] = None
    task_completion_rate: Optional[float] = None
    tool_success_rate: Optional[float] = None
    tool_executions: dict
    budget_snapshot: Optional[dict] = None


@router.get("/dashboard", response_model=DashboardOut)
async def get_dashboard(
    request: Request,
    user: CurrentUser,
    db: AsyncSession = Depends(get_db),
    user_id: Optional[str] = Query(None, description="Admin only: 'all' or a user id"),
) -> DashboardOut:
    scope_user: str | None = str(user.id)
    if user_id and _is_admin(user):
        scope_user = None if user_id == "all" else user_id
    dashboard = await build_dashboard(
        db,
        user_id=scope_user,
        budget_service=request.app.state.budget_service if scope_user else None,
    )
    return DashboardOut(**dashboard)


class UsageEventOut(BaseModel):
    id: UUID
    kind: str
    user_id: Optional[UUID] = None
    request_id: Optional[str] = None
    conversation_id: Optional[UUID] = None
    task_id: Optional[UUID] = None
    provider: Optional[str] = None
    model: Optional[str] = None
    selection_reason: Optional[str] = None
    input_tokens: int
    output_tokens: int
    estimated_cost_usd: Optional[float] = None
    cost_basis: str
    billing_source: Optional[str] = None
    cache_hit: Optional[str] = None
    latency_ms: Optional[int] = None
    retry_count: int
    fallback_count: int
    tool_slug: Optional[str] = None
    tool_duration_ms: Optional[int] = None
    success: Optional[bool] = None
    error_category: Optional[str] = None
    created_at: datetime

    model_config = {"from_attributes": True}


@router.get("/events", response_model=list[UsageEventOut])
async def list_events(
    user: CurrentUser,
    db: AsyncSession = Depends(get_db),
    kind: Optional[str] = Query(None),
    limit: int = Query(100, ge=1, le=500),
    user_id: Optional[str] = Query(None, description="Admin only"),
) -> list[UsageEvent]:
    query = select(UsageEvent).order_by(UsageEvent.created_at.desc()).limit(limit)
    if user_id and _is_admin(user):
        if user_id != "all":
            query = query.where(UsageEvent.user_id == UUID(user_id))
    else:
        query = query.where(UsageEvent.user_id == user.id)
    if kind:
        query = query.where(UsageEvent.kind == kind)
    return list((await db.execute(query)).scalars().all())


class AlertOut(BaseModel):
    id: UUID
    scope: str
    threshold_pct: int
    alert_day: date
    message: str
    created_at: datetime

    model_config = {"from_attributes": True}


@router.get("/alerts", response_model=list[AlertOut])
async def list_alerts(
    user: CurrentUser,
    db: AsyncSession = Depends(get_db),
    limit: int = Query(50, ge=1, le=200),
    user_id: Optional[str] = Query(None, description="Admin only"),
) -> list[BudgetAlert]:
    query = select(BudgetAlert).order_by(BudgetAlert.created_at.desc()).limit(limit)
    if user_id and _is_admin(user):
        if user_id != "all":
            query = query.where(BudgetAlert.user_id == UUID(user_id))
    else:
        query = query.where(BudgetAlert.user_id == user.id)
    return list((await db.execute(query)).scalars().all())


class BudgetUpdate(BaseModel):
    daily_budget_usd: Optional[float] = Field(None, ge=0)
    monthly_budget_usd: Optional[float] = Field(None, ge=0)
    daily_token_limit: Optional[int] = Field(None, ge=0)
    monthly_token_limit: Optional[int] = Field(None, ge=0)
    concurrent_request_limit: Optional[int] = Field(None, ge=0)


class BudgetOut(BaseModel):
    daily_budget_usd: Optional[float] = None
    monthly_budget_usd: Optional[float] = None
    daily_token_limit: Optional[int] = None
    monthly_token_limit: Optional[int] = None
    concurrent_request_limit: Optional[int] = None
    effective_limits: Optional[dict] = None
    updated_at: Optional[datetime] = None


@budget_router.get("", response_model=BudgetOut)
async def get_budgets(
    request: Request,
    user: CurrentUser,
    db: AsyncSession = Depends(get_db),
) -> BudgetOut:
    service: BudgetService = request.app.state.budget_service
    row = await service.get_or_create_budget(db, str(user.id))
    return BudgetOut(
        daily_budget_usd=row.daily_budget_usd,
        monthly_budget_usd=row.monthly_budget_usd,
        daily_token_limit=row.daily_token_limit,
        monthly_token_limit=row.monthly_token_limit,
        concurrent_request_limit=row.concurrent_request_limit,
        effective_limits=service.effective_limits(row),
        updated_at=row.updated_at,
    )


@budget_router.put("", response_model=BudgetOut)
async def set_budgets(
    payload: BudgetUpdate,
    request: Request,
    user: CurrentUser,
    db: AsyncSession = Depends(get_db),
) -> BudgetOut:
    service: BudgetService = request.app.state.budget_service
    row = await service.set_budget(
        db,
        str(user.id),
        **payload.model_dump(exclude_none=True),
    )
    return BudgetOut(
        daily_budget_usd=row.daily_budget_usd,
        monthly_budget_usd=row.monthly_budget_usd,
        daily_token_limit=row.daily_token_limit,
        monthly_token_limit=row.monthly_token_limit,
        concurrent_request_limit=row.concurrent_request_limit,
        effective_limits=service.effective_limits(row),
        updated_at=row.updated_at,
    )
