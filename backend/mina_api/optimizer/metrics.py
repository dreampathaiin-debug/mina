"""Optimizer metrics: nothing is claimed without being measured.

Tracks, per optimization event: input tokens before/after optimization,
output tokens, cache hits (exact / semantic), estimated token and cost
savings, latency, and model quality failures (replays or optimizations a
caller rejects). Savings use the model registry's own input price, so a
model without published pricing reports token savings only.
"""

from __future__ import annotations

import threading
import time
from dataclasses import dataclass, field


@dataclass
class OptimizationRecord:
    tokens_before: int
    tokens_after: int
    user_id: str = ""
    output_tokens: int = 0
    cache_hit: str | None = None          # None | "exact" | "semantic"
    cache_saved_tokens: int = 0
    est_savings_usd: float = 0.0
    latency_ms: int = 0
    model_id: str = ""
    task_type: str = ""
    quality_failure: bool = False
    failure_reason: str = ""
    created_at: float = field(default_factory=time.time)


class MetricsLedger:
    def __init__(self, *, persist=None, max_events: int = 1000) -> None:
        self._records: list[OptimizationRecord] = []
        self._max_events = max_events
        self._lock = threading.Lock()
        self._persist = persist  # async callable(record_dict) -> None, optional
        self._pending: list[OptimizationRecord] = []

    def record(self, record: OptimizationRecord) -> None:
        with self._lock:
            self._records.append(record)
            if len(self._records) > self._max_events:
                self._records = self._records[-self._max_events :]

    async def flush(self) -> None:
        """Persist pending records within the caller's live event loop.

        Deliberately awaited, never fire-and-forget: a dangling write task
        killed by a closing event loop leaves the database pool holding a
        dirty connection. Failures never break inference; an unpersisted
        metric stays in the in-memory ledger.
        """
        if self._persist is None:
            return
        with self._lock:
            pending = self._pending
            self._pending = []
        if not pending:
            return
        for record in pending:
            try:
                await self._persist(self._as_dict(record))
            except Exception:
                pass

    def record_quality_failure(self, reason: str, *, model_id: str = "", task_type: str = "") -> None:
        rec = OptimizationRecord(
            tokens_before=0, tokens_after=0, quality_failure=True,
            failure_reason=reason, model_id=model_id, task_type=task_type,
        )
        with self._lock:
            self._records.append(rec)
            self._pending.append(rec)
            if len(self._records) > self._max_events:
                self._records = self._records[-self._max_events :]

    @staticmethod
    def _as_dict(record: OptimizationRecord) -> dict:
        return {
            "user_id": record.user_id,
            "tokens_before": record.tokens_before,
            "tokens_after": record.tokens_after,
            "output_tokens": record.output_tokens,
            "cache_hit": record.cache_hit,
            "cache_saved_tokens": record.cache_saved_tokens,
            "est_savings_usd": record.est_savings_usd,
            "latency_ms": record.latency_ms,
            "model_id": record.model_id,
            "task_type": record.task_type,
            "quality_failure": record.quality_failure,
            "failure_reason": record.failure_reason,
        }

    def summary(self) -> dict:
        with self._lock:
            records = list(self._records)
        requests = [r for r in records if not r.quality_failure]
        hits = [r for r in requests if r.cache_hit]
        exact_hits = [r for r in hits if r.cache_hit == "exact"]
        semantic_hits = [r for r in hits if r.cache_hit == "semantic"]
        tokens_before = sum(r.tokens_before for r in requests)
        tokens_after = sum(r.tokens_after for r in requests)
        context_saved = sum(
            max(0, r.tokens_before - r.tokens_after) for r in requests if not r.cache_hit
        )
        failures = [r for r in records if r.quality_failure]
        latencies = [r.latency_ms for r in requests if r.latency_ms > 0]
        return {
            "requests": len(requests),
            "exact_hits": len(exact_hits),
            "semantic_hits": len(semantic_hits),
            "cache_hit_rate": round(len(hits) / len(requests), 4) if requests else 0.0,
            "input_tokens_before": tokens_before,
            "input_tokens_after": tokens_after,
            "context_tokens_saved": context_saved,
            "cache_tokens_saved": sum(r.cache_saved_tokens for r in hits),
            "total_tokens_saved": context_saved + sum(r.cache_saved_tokens for r in hits),
            "output_tokens": sum(r.output_tokens for r in requests),
            "estimated_cost_savings_usd": round(sum(r.est_savings_usd for r in hits), 6),
            "avg_latency_ms": round(sum(latencies) / len(latencies), 1) if latencies else None,
            "quality_failures": len(failures),
            "recent_quality_failure_reasons": [r.failure_reason for r in failures[-5:]],
        }
