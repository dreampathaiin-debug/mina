"""Phase 5: optimizer metrics endpoint (read-only)."""

from datetime import datetime, timezone

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel

from mina_api.security.auth import CurrentUser

router = APIRouter(prefix="/optimizer", tags=["optimizer"])


class MetricsOut(BaseModel):
    requests: int
    exact_hits: int
    semantic_hits: int
    cache_hit_rate: float
    input_tokens_before: int
    input_tokens_after: int
    context_tokens_saved: int
    cache_tokens_saved: int
    total_tokens_saved: int
    output_tokens: int
    estimated_cost_savings_usd: float
    avg_latency_ms: float | None
    quality_failures: int
    recent_quality_failure_reasons: list[str]
    generated_at: datetime


@router.get("/metrics", response_model=MetricsOut)
async def optimizer_metrics(request: Request, user: CurrentUser) -> MetricsOut:
    summary = request.app.state.optimizer_ledger.summary()
    return MetricsOut(generated_at=datetime.now(timezone.utc), **summary)
