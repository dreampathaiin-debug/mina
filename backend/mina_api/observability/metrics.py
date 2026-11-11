"""Phase 10 dashboard metrics, computed from usage_events.

All money figures are ESTIMATES (cost_basis='estimate'); managed and
BYOK spend are reported separately and never merged. Task completion
rate reads the durable task table; tool success rate reads tool events.
"""

from __future__ import annotations

import uuid as _uuid
from datetime import datetime, timezone

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from mina_api.observability.budgets import BudgetService
from mina_api.observability.models import UsageEvent
from mina_api.taskengine.engine import TERMINAL_STATES, TaskState


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


async def build_dashboard(
    db: AsyncSession,
    *,
    user_id: str | None = None,
    budget_service: BudgetService | None = None,
    now: datetime | None = None,
) -> dict:
    now = now or _utcnow()
    base = select(
        UsageEvent.kind,
        UsageEvent.provider,
        UsageEvent.model,
        UsageEvent.billing_source,
        UsageEvent.cache_hit,
        UsageEvent.error_category,
        UsageEvent.success,
        UsageEvent.latency_ms,
        UsageEvent.input_tokens,
        UsageEvent.output_tokens,
        UsageEvent.estimated_cost_usd,
        UsageEvent.user_id,
    ).where(UsageEvent.created_at <= now)
    if user_id is not None:
        base = base.where(UsageEvent.user_id == _uuid_id(user_id))
    rows = (await db.execute(base)).all()

    model_events = [r for r in rows if r.kind == "model_call"]
    tool_events = [r for r in rows if r.kind == "tool_execution"]

    total_requests = len(model_events)
    provider_usage: dict[str, int] = {}
    model_usage: dict[str, int] = {}
    provider_failures: dict[str, int] = {}
    input_tokens = output_tokens = 0
    managed_cost = byok_cost = 0.0
    cost_known = False
    latencies: list[int] = []
    cache_hits = cache_misses = 0

    for r in model_events:
        if r.provider:
            provider_usage[r.provider] = provider_usage.get(r.provider, 0) + 1
            if r.error_category:
                provider_failures[r.provider] = provider_failures.get(r.provider, 0) + 1
        if r.model:
            model_usage[r.model] = model_usage.get(r.model, 0) + 1
        input_tokens += r.input_tokens or 0
        output_tokens += r.output_tokens or 0
        if r.estimated_cost_usd is not None:
            cost_known = True
            if r.billing_source == "managed":
                managed_cost += r.estimated_cost_usd
            elif r.billing_source == "byok":
                byok_cost += r.estimated_cost_usd
        if r.latency_ms is not None:
            latencies.append(r.latency_ms)
        if r.cache_hit == "exact" or r.cache_hit == "semantic":
            cache_hits += 1
        elif r.cache_hit == "none":
            cache_misses += 1

    active_users = len({r.user_id for r in model_events if r.user_id}) if user_id is None else None
    cache_denominator = cache_hits + cache_misses

    tool_successes = sum(1 for r in tool_events if r.success)
    tool_failures = sum(1 for r in tool_events if not r.success)

    task_completion_rate = None
    if user_id is not None:
        from mina_api.database.models import DurableTask

        statuses = [s.value if hasattr(s, "value") else s for s in TERMINAL_STATES]
        stmt = select(DurableTask.status).where(
            DurableTask.user_id == _uuid_id(user_id),
            DurableTask.status.in_(statuses),
        )
        terminal = [row[0] for row in (await db.execute(stmt)).all()]
        completed = sum(1 for s in terminal if s == TaskState.COMPLETED.value)
        if terminal:
            task_completion_rate = round(completed / len(terminal), 4)

    snapshot: dict | None = None
    if budget_service is not None and user_id is not None:
        snapshot = await budget_service.snapshot(db, user_id, now=now)

    dashboard: dict = {
        "window": {"as_of": now.isoformat()},
        "total_requests": total_requests,
        "active_users": active_users,
        "requests_per_user": (round(total_requests / active_users, 2) if active_users else None),
        "provider_usage": provider_usage,
        "model_usage": model_usage,
        "token_consumption": {"input": input_tokens, "output": output_tokens, "total": input_tokens + output_tokens},
        "estimated_managed_cost_usd": round(managed_cost, 6),
        "estimated_byok_cost_usd": round(byok_cost, 6),
        "estimated_total_cost_usd": round(managed_cost + byok_cost, 6),
        "cost_note": "All cost figures are estimates from model pricing; they are not actual provider billing.",
        "cost_estimates_available": cost_known or total_requests == 0,
        "cache_hit_rate": (round(cache_hits / cache_denominator, 4) if cache_denominator else None),
        "provider_failures": provider_failures,
        "average_latency_ms": (round(sum(latencies) / len(latencies), 1) if latencies else None),
        "task_completion_rate": task_completion_rate,
        "tool_success_rate": (round(tool_successes / (tool_successes + tool_failures), 4) if (tool_successes + tool_failures) else None),
        "tool_executions": {"successes": tool_successes, "failures": tool_failures},
    }
    if snapshot is not None:
        dashboard["budget_snapshot"] = snapshot
    return dashboard
