"""Routing API: catalog + dry-run plan previews.

Preview builds the exact plan the router would execute (credential
resolution, health gates, rate limits, capability and context checks)
without calling any model, so the app can show the choice before spend.
Credential plaintext never leaves this service.
"""

from __future__ import annotations

from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy.ext.asyncio import AsyncSession

from mina_api.database.session import get_db
from mina_api.routing.routing_policy import TaskSpec, UserPrefs
from mina_api.schemas.routing import (
    CandidateOut,
    CatalogModelOut,
    CatalogOut,
    CatalogProviderOut,
    HealthSnapshotOut,
    RejectionOut,
    RoutingPreviewIn,
    RoutingPreviewOut,
    UsageSummaryOut,
)
from mina_api.security.auth import CurrentUser

router = APIRouter(prefix="/routing", tags=["routing"])


def _router(request: Request):
    return request.app.state.model_router


@router.get("/catalog", response_model=CatalogOut)
async def get_catalog(request: Request) -> CatalogOut:
    engine = _router(request)
    registry = engine.registry
    return CatalogOut(
        models=[
            CatalogModelOut(
                model_id=spec.model_id,
                provider=spec.provider,
                display_name=spec.display_name,
                litellm_model=spec.litellm_model,
                groups=sorted(spec.groups),
                capabilities={"tools": spec.tools, "vision": spec.vision, "json_mode": spec.json_mode},
                context_window=spec.context_window,
                max_output_tokens=spec.max_output_tokens,
                priority=spec.priority,
                timeout_seconds=spec.timeout_seconds,
                pricing_usd_per_mtok=(
                    {"input": spec.input_price, "output": spec.output_price}
                    if spec.input_price is not None and spec.output_price is not None
                    else None
                ),
                enabled=spec.enabled,
            )
            for spec in registry.models
        ],
        providers=[
            CatalogProviderOut(
                provider=caps.provider, rpm_limit=caps.rpm_limit, tpm_limit=caps.tpm_limit,
                latency_class=caps.latency_class,
            )
            for caps in registry.providers.values()
        ],
    )


@router.post("/preview", response_model=RoutingPreviewOut)
async def preview_plan(
    payload: RoutingPreviewIn,
    request: Request,
    user: CurrentUser,
    db: AsyncSession = Depends(get_db),
) -> RoutingPreviewOut:
    engine = _router(request)
    task = TaskSpec(
        task_type=payload.task_type,
        needs_tools=payload.needs_tools,
        input_tokens=payload.input_tokens,
        max_output_tokens=payload.max_output_tokens,
        allow_context_escalation=payload.allow_context_escalation,
    )
    try:
        plan = await engine.build_plan(db, user.id, task, request_id=getattr(request.state, "request_id", None))
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    return RoutingPreviewOut(
        trace_id=plan.trace_id,
        group=plan.group.value,
        candidates=[
            CandidateOut(
                model_id=c.spec.model_id,
                provider=c.spec.provider,
                source=c.credential.source,
                reason=c.reason,
                priority=c.spec.priority,
                context_window=c.spec.context_window,
                timeout_seconds=c.spec.timeout_seconds,
                estimated_cost_usd=engine.usage.estimate_plan_cost(c.spec, payload.input_tokens),
            )
            for c in plan.candidates
        ],
        rejected=[RejectionOut(model_id=r.model_id, provider=r.provider, reason=r.reason) for r in plan.rejected],
        primary_model=plan.primary.model_id if plan.primary else None,
    )


@router.get("/health", response_model=HealthSnapshotOut)
async def health_snapshot(request: Request, user: CurrentUser) -> HealthSnapshotOut:
    return HealthSnapshotOut(snapshot=_router(request).health.snapshot())


@router.get("/usage", response_model=UsageSummaryOut)
async def usage_summary(request: Request, user: CurrentUser) -> UsageSummaryOut:
    return UsageSummaryOut(
        summary=_router(request).usage.summary(),
        generated_at=datetime.now(timezone.utc),
    )
