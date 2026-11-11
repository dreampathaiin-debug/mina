"""Phase 10 usage event recorder.

Single writer for usage_events. Records METADATA ONLY: ids, provider,
model, token counts, estimated cost, billing source, latency, errors.
API keys, OAuth tokens and raw prompt/completion text never reach this
layer. Every event also goes to structured logs and to any configured
exporter (Langfuse).
"""

from __future__ import annotations

import logging
import uuid as _uuid
from datetime import datetime, timezone
from contextlib import asynccontextmanager
from typing import Any, Protocol

from sqlalchemy.ext.asyncio import AsyncSession

from mina_api.observability.langfuse import sanitize_metadata
from mina_api.observability.context import (
    obs_cache_hit_var,
    obs_conversation_id_var,
    obs_fallback_count_var,
    obs_retry_count_var,
    obs_task_id_var,
    obs_user_id_var,
)
from mina_api.observability.models import BudgetAlert, UsageEvent

logger = logging.getLogger("mina.observability")


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


def _coerce_uuid(value: str | None) -> _uuid.UUID | None:
    """Coerce an ambient string id into the UUID the column expects."""
    if value is None:
        return None
    if isinstance(value, _uuid.UUID):
        return value
    try:
        return _uuid.UUID(str(value))
    except ValueError:
        return None


def event_to_payload(event: UsageEvent) -> dict:
    """Exporter-shaped dict of an event. Metadata only, sanitized."""
    return sanitize_metadata(
        {
            "kind": event.kind,
            "user_id": str(event.user_id) if event.user_id else None,
            "request_id": event.request_id,
            "conversation_id": str(event.conversation_id) if event.conversation_id else None,
            "task_id": str(event.task_id) if event.task_id else None,
            "provider": event.provider,
            "model": event.model,
            "selection_reason": event.selection_reason,
            "input_tokens": event.input_tokens,
            "output_tokens": event.output_tokens,
            "estimated_cost_usd": event.estimated_cost_usd,
            "cost_basis": event.cost_basis,
            "billing_source": event.billing_source,
            "cache_hit": event.cache_hit,
            "latency_ms": event.latency_ms,
            "retry_count": event.retry_count,
            "fallback_count": event.fallback_count,
            "tool_slug": event.tool_slug,
            "tool_duration_ms": event.tool_duration_ms,
            "success": event.success,
            "error_category": event.error_category,
            "created_at": event.created_at.isoformat() if event.created_at else None,
        }
    )


class Exporter(Protocol):
    async def export(self, events: list[dict]) -> bool: ...


class ObservabilityRecorder:
    def __init__(self, *, exporters: list[Exporter] | None = None, session_factory=None) -> None:
        self._exporters = exporters or []
        self._session_factory = session_factory

    @asynccontextmanager
    async def _own_db(self):
        """Open a short-lived session when the caller passed none."""
        if self._session_factory is None:
            raise RuntimeError("no session_factory configured for recorder")
        async with self._session_factory() as db:
            yield db

    async def record_model_call(
        self,
        db: AsyncSession | None,
        *,
        user_id: str | None,
        provider: str,
        model: str,
        selection_reason: str | None = None,
        request_id: str | None = None,
        conversation_id: str | None = None,
        task_id: str | None = None,
        input_tokens: int = 0,
        output_tokens: int = 0,
        estimated_cost_usd: float | None = None,
        billing_source: str | None = None,
        cache_hit: str | None = None,
        latency_ms: int | None = None,
        retry_count: int = 0,
        fallback_count: int = 0,
        error_category: str | None = None,
    ) -> UsageEvent:
        # Ambient correlation falls back to the observation context so a
        # call site that passes no ids still records the full chain.
        eff_user_id = user_id if user_id is not None else obs_user_id_var.get()
        eff_conversation = conversation_id if conversation_id is not None else obs_conversation_id_var.get()
        eff_task = task_id if task_id is not None else obs_task_id_var.get()
        eff_cache = cache_hit if cache_hit is not None else obs_cache_hit_var.get()
        eff_retries = retry_count if retry_count else obs_retry_count_var.get()
        eff_fallbacks = fallback_count if fallback_count else obs_fallback_count_var.get()

        def _build() -> UsageEvent:
            return UsageEvent(
                kind="model_call",
                user_id=_coerce_uuid(eff_user_id),
                request_id=request_id,
                conversation_id=_coerce_uuid(eff_conversation),
                task_id=_coerce_uuid(eff_task),
                provider=provider,
                model=model,
                selection_reason=selection_reason,
                input_tokens=input_tokens,
                output_tokens=output_tokens,
                estimated_cost_usd=estimated_cost_usd,
                cost_basis="estimate",
                billing_source=billing_source,
                cache_hit=eff_cache,
                latency_ms=latency_ms,
                retry_count=eff_retries,
                fallback_count=eff_fallbacks,
                success=error_category is None,
                error_category=error_category,
            )

        return await self._persist(db, _build)

    async def record_tool_execution(
        self,
        db: AsyncSession | None,
        *,
        user_id: str | None,
        tool_slug: str,
        duration_ms: int,
        success: bool,
        request_id: str | None = None,
        conversation_id: str | None = None,
        task_id: str | None = None,
        provider: str | None = None,
        error_category: str | None = None,
    ) -> UsageEvent:
        eff_user_id = user_id if user_id is not None else obs_user_id_var.get()
        eff_conversation = conversation_id if conversation_id is not None else obs_conversation_id_var.get()
        eff_task = task_id if task_id is not None else obs_task_id_var.get()

        def _build() -> UsageEvent:
            return UsageEvent(
                kind="tool_execution",
                user_id=_coerce_uuid(eff_user_id),
                request_id=request_id,
                conversation_id=_coerce_uuid(eff_conversation),
                task_id=_coerce_uuid(eff_task),
                tool_slug=tool_slug,
                tool_duration_ms=duration_ms,
                provider=provider,
                success=success,
                error_category=error_category,
            )

        return await self._persist(db, _build)

    async def _persist(self, db: AsyncSession | None, build) -> UsageEvent:
        if db is not None:
            event = build()
            db.add(event)
            await db.commit()
            await db.refresh(event)
        else:
            async with self._own_db() as session:
                event = build()
                session.add(event)
                await session.commit()
                await session.refresh(event)
        self._fan_out(event)
        return event

    def _fan_out(self, event: UsageEvent) -> None:
        payload = event_to_payload(event)
        logger.info(
            "usage_event",
            extra={"event": "usage_event", **{k: v for k, v in payload.items() if v is not None}},
        )
        if self._exporters:
            self._dispatch(payload)

    def _dispatch(self, payload: dict) -> None:
        # Exporters run best-effort and off the hot path: a slow or
        # failing sink must never block the request.
        import asyncio

        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:  # pragma: no cover - outside an event loop
            return
        for exporter in self._exporters:
            loop.create_task(self._export_one(exporter, payload))

    async def _export_one(self, exporter: Exporter, payload: dict) -> None:
        try:
            await exporter.export([payload])
        except Exception:  # noqa: BLE001
            logger.warning("usage_export_error", exc_info=True)
