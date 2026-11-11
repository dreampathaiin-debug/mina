"""Phase 10 budget management.

Per-user daily/monthly USD budgets and token limits, a provider-level
daily budget, per-user concurrent request limits, pre-exhaustion alerts
(80% / 95% by default, one alert per scope+threshold+day) and hard stops
at the configured limits.

Budgeted spend is MANAGED spend only: BYOK calls are billed directly to
the user by the provider and are tracked separately for reporting.
Token limits apply to all traffic (they protect rate limits, not money).
All costs consulted here are ESTIMATES (cost_basis='estimate'); actual
provider billing is reconciled separately, never mixed into these
aggregates.
"""

from __future__ import annotations

import logging
import uuid as _uuid
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from typing import Sequence

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from mina_api.observability.models import BudgetAlert, UsageBudget, UsageEvent

logger = logging.getLogger("mina.observability")


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


def _uuid_id(value: str | None) -> _uuid.UUID | None:
    if value is None:
        return None
    if isinstance(value, _uuid.UUID):
        return value
    try:
        return _uuid.UUID(str(value))
    except ValueError:
        return None


class QuotaExceededError(Exception):
    """Raised on a hard stop so the request path can reject the call."""

    def __init__(self, scope: str, message: str) -> None:
        super().__init__(message)
        self.scope = scope
        self.message = message


@dataclass
class BudgetDecision:
    allowed: bool = True
    reason_code: str | None = None
    concurrent_limit: int | None = None
    alerts: list[dict] = field(default_factory=list)


class ActiveRequestRegistry:
    """Per-user in-flight request counter (per process). The budget
    check supplies the limit; a second process enforces its own share."""

    def __init__(self) -> None:
        self._counts: dict[str, int] = {}

    def acquire(self, user_id: str, limit: int | None) -> bool:
        if limit is not None and limit > 0:
            current = self._counts.get(user_id, 0)
            if current >= limit:
                return False
            self._counts[user_id] = current + 1
            return True
        self._counts[user_id] = self._counts.get(user_id, 0) + 1
        return True

    def release(self, user_id: str) -> None:
        current = self._counts.get(user_id, 0)
        if current <= 1:
            self._counts.pop(user_id, None)
        else:
            self._counts[user_id] = current - 1

    def in_flight(self, user_id: str) -> int:
        return self._counts.get(user_id, 0)


class BudgetService:
    def __init__(
        self,
        *,
        provider_daily_budgets: dict[str, float] | None = None,
        default_daily_budget_usd: float | None = None,
        default_monthly_budget_usd: float | None = None,
        default_daily_token_limit: int | None = None,
        default_monthly_token_limit: int | None = None,
        alert_thresholds: Sequence[int] = (80, 95),
    ) -> None:
        self._provider_daily_budgets = {
            name.lower(): float(budget) for name, budget in (provider_daily_budgets or {}).items()
        }
        self._defaults = {
            "daily_budget_usd": default_daily_budget_usd,
            "monthly_budget_usd": default_monthly_budget_usd,
            "daily_token_limit": default_daily_token_limit,
            "monthly_token_limit": default_monthly_token_limit,
        }
        self._alert_thresholds = tuple(sorted({int(t) for t in alert_thresholds}, reverse=True))

    # -- limits -----------------------------------------------------------

    async def get_or_create_budget(self, db: AsyncSession, user_id: str) -> UsageBudget:
        row = (await db.execute(select(UsageBudget).where(UsageBudget.user_id == _uuid_id(user_id)))).scalar_one_or_none()
        if row is None:
            row = UsageBudget(user_id=_uuid_id(user_id))
            db.add(row)
            await db.commit()
            await db.refresh(row)
        return row

    def effective_limits(self, budget: UsageBudget) -> dict:
        """Row values win; unset fields fall back to global defaults."""
        return {
            field: getattr(budget, field) if getattr(budget, field) is not None else self._defaults[field]
            for field in self._defaults
        }

    async def set_budget(self, db: AsyncSession, user_id: str, **fields) -> UsageBudget:
        row = await self.get_or_create_budget(db, user_id)
        for field in ("daily_budget_usd", "monthly_budget_usd", "daily_token_limit", "monthly_token_limit", "concurrent_request_limit"):
            if field in fields and fields[field] is not None:
                value = fields[field]
                if field.endswith("_limit") and value is not None and value < 0:
                    raise ValueError(f"{field} must be non-negative")
                if field.endswith("_budget_usd") and value is not None and value < 0:
                    raise ValueError(f"{field} must be non-negative")
                setattr(row, field, value)
        row.updated_at = _utcnow()
        await db.commit()
        await db.refresh(row)
        return row

    # -- accounting --------------------------------------------------------

    @staticmethod
    async def _sum_spend(
        db: AsyncSession,
        user_id: str,
        *,
        since: datetime,
        billing_source: str | None = None,
        provider: str | None = None,
    ) -> float:
        stmt = select(func.coalesce(func.sum(UsageEvent.estimated_cost_usd), 0.0)).where(
            UsageEvent.kind == "model_call",
            UsageEvent.user_id == _uuid_id(user_id),
            UsageEvent.created_at >= since,
        )
        if billing_source:
            stmt = stmt.where(UsageEvent.billing_source == billing_source)
        if provider:
            stmt = stmt.where(UsageEvent.provider == provider)
        value = (await db.execute(stmt)).scalar_one()
        return float(value)

    @staticmethod
    async def _sum_tokens(db: AsyncSession, user_id: str, *, since: datetime) -> int:
        stmt = (
            select(
                func.coalesce(func.sum(UsageEvent.input_tokens), 0),
                func.coalesce(func.sum(UsageEvent.output_tokens), 0),
            ).where(
                UsageEvent.kind == "model_call",
                UsageEvent.user_id == _uuid_id(user_id),
                UsageEvent.created_at >= since,
            )
        )
        row = (await db.execute(stmt)).one()
        return int(row[0]) + int(row[1])

    async def snapshot(self, db: AsyncSession, user_id: str, *, now: datetime | None = None) -> dict:
        now = now or _utcnow()
        day_start = now.replace(hour=0, minute=0, second=0, microsecond=0)
        month_start = now.replace(day=1, hour=0, minute=0, second=0, microsecond=0)
        managed_day = await self._sum_spend(db, user_id, since=day_start, billing_source="managed")
        managed_month = await self._sum_spend(db, user_id, since=month_start, billing_source="managed")
        byok_day = await self._sum_spend(db, user_id, since=day_start, billing_source="byok")
        byok_month = await self._sum_spend(db, user_id, since=month_start, billing_source="byok")
        tokens_day = await self._sum_tokens(db, user_id, since=day_start)
        tokens_month = await self._sum_tokens(db, user_id, since=month_start)
        return {
            "managed_spend_today_usd": round(managed_day, 6),
            "managed_spend_month_usd": round(managed_month, 6),
            "byok_spend_today_usd": round(byok_day, 6),
            "byok_spend_month_usd": round(byok_month, 6),
            "tokens_today": tokens_day,
            "tokens_month": tokens_month,
        }

    # -- enforcement ---------------------------------------------------------

    async def check_request(
        self,
        db: AsyncSession,
        user_id: str,
        *,
        provider: str | None = None,
        now: datetime | None = None,
    ) -> BudgetDecision:
        now = now or _utcnow()
        budget = await self.get_or_create_budget(db, user_id)
        limits = self.effective_limits(budget)
        decision = BudgetDecision(
            concurrent_limit=budget.concurrent_request_limit
        )
        day_start = now.replace(hour=0, minute=0, second=0, microsecond=0)
        month_start = now.replace(day=1, hour=0, minute=0, second=0, microsecond=0)

        managed_day = await self._sum_spend(db, user_id, since=day_start, billing_source="managed")
        managed_month = await self._sum_spend(db, user_id, since=month_start, billing_source="managed")
        tokens_day = await self._sum_tokens(db, user_id, since=day_start)
        tokens_month = await self._sum_tokens(db, user_id, since=month_start)

        checks: list[tuple[str, float, float | None]] = [
            ("daily_budget", managed_day, limits["daily_budget_usd"]),
            ("monthly_budget", managed_month, limits["monthly_budget_usd"]),
            ("daily_tokens", tokens_day, limits["daily_token_limit"]),
            ("monthly_tokens", tokens_month, limits["monthly_token_limit"]),
        ]
        if provider and provider.lower() in self._provider_daily_budgets:
            provider_spend = await self._sum_spend(
                db, user_id, since=day_start, billing_source="managed", provider=provider.lower()
            )
            checks.append((f"provider:{provider.lower()}", provider_spend, self._provider_daily_budgets[provider.lower()]))

        for scope, used, limit in checks:
            if limit is None or limit <= 0:
                continue
            if used >= limit:
                decision.allowed = False
                decision.reason_code = f"{scope}_exhausted"
                logger.warning("budget_hard_stop user=%s scope=%s used=%s limit=%s", user_id, scope, used, limit)
                return decision
            alert = await self._maybe_alert(db, user_id, scope, used / limit, now)
            if alert is not None:
                decision.alerts.append(alert)

        return decision

    async def _maybe_alert(
        self, db: AsyncSession, user_id: str, scope: str, ratio: float, now: datetime
    ) -> dict | None:
        for threshold in self._alert_thresholds:
            if ratio * 100 < threshold:
                continue
            alert_day = now.date()
            existing = (
                await db.execute(
                    select(BudgetAlert).where(
                        BudgetAlert.user_id == _uuid_id(user_id),
                        BudgetAlert.scope == scope,
                        BudgetAlert.threshold_pct == threshold,
                        BudgetAlert.alert_day == alert_day,
                    )
                )
            ).scalar_one_or_none()
            if existing is not None:
                # Still active: surface it without writing a second row.
                return {
                    "scope": scope,
                    "threshold_pct": threshold,
                    "usage_pct": int(ratio * 100),
                    "message": existing.message,
                    "alert_day": str(alert_day),
                    "deduped": True,
                }
            message = f"{scope} usage at {int(ratio * 100)}% of limit"
            alert = BudgetAlert(
                user_id=_uuid_id(user_id),
                scope=scope,
                threshold_pct=threshold,
                alert_day=alert_day,
                message=message[:300],
            )
            db.add(alert)
            await db.commit()
            logger.warning("budget_alert user=%s scope=%s threshold=%s", user_id, scope, threshold)
            return {
                "scope": scope,
                "threshold_pct": threshold,
                "usage_pct": int(ratio * 100),
                "message": message,
                "alert_day": str(alert_day),
            }
        return None
