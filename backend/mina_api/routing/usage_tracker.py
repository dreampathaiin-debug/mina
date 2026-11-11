"""Provider RPM/TPM tracking, token usage ledger and cost estimation.

In-memory by design (Phase 4): the rate windows protect a single backend
instance's share of provider limits; durable per-user usage aggregation
lands with the billing/usage phase. Every record carries the request and
trace ids so callers can join on them later.
"""

from __future__ import annotations

import collections
import threading
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Deque
from uuid import UUID


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


@dataclass(frozen=True)
class UsageRecord:
    trace_id: UUID
    request_id: str | None
    model_id: str
    provider: str
    source: str
    prompt_tokens: int
    completion_tokens: int
    total_tokens: int
    cost_usd: float | None
    latency_ms: int | None
    at: datetime


class RateWindows:
    """Sliding-window RPM/TPM counters per (provider, credential)."""

    def __init__(self, window_seconds: float = 60.0) -> None:
        self.window_seconds = window_seconds
        self._lock = threading.Lock()
        self._hits: dict[tuple[str, UUID], Deque[datetime]] = collections.defaultdict(collections.deque)
        self._tokens: dict[tuple[str, UUID], Deque[tuple[datetime, int]]] = collections.defaultdict(collections.deque)

    def _prune(self, key, now: datetime) -> None:
        horizon = now - timedelta(seconds=self.window_seconds)
        hits = self._hits[key]
        while hits and hits[0] < horizon:
            hits.popleft()
        tokens = self._tokens[key]
        while tokens and tokens[0][0] < horizon:
            tokens.popleft()

    def counts(self, provider: str, credential_id: UUID, *, now: datetime | None = None) -> tuple[int, int]:
        now = now or _utcnow()
        key = (provider, credential_id)
        with self._lock:
            self._prune(key, now)
            return len(self._hits[key]), sum(t for _, t in self._tokens[key])

    def can_attempt(
        self,
        provider: str,
        credential_id: UUID,
        estimated_tokens: int,
        *,
        rpm_limit: int | None = None,
        tpm_limit: int | None = None,
        now: datetime | None = None,
    ) -> tuple[bool, str]:
        now = now or _utcnow()
        rpm, tpm = self.counts(provider, credential_id, now=now)
        if rpm_limit is not None and rpm + 1 > rpm_limit:
            return False, f"rpm {rpm}/{rpm_limit} in the last {int(self.window_seconds)}s"
        if tpm_limit is not None and tpm + estimated_tokens > tpm_limit:
            return False, f"tpm {tpm}+{estimated_tokens}/{tpm_limit} in the last {int(self.window_seconds)}s"
        return True, "ok"

    def record_attempt(self, provider: str, credential_id: UUID, tokens: int, *, now: datetime | None = None) -> None:
        now = now or _utcnow()
        key = (provider, credential_id)
        with self._lock:
            self._hits[key].append(now)
            self._tokens[key].append((now, tokens))


class UsageTracker:
    """Rate windows + the usage ledger + cost estimation."""

    def __init__(self, rate_windows: RateWindows | None = None) -> None:
        self.rate = rate_windows or RateWindows()
        self._ledger: list[UsageRecord] = []

    def can_attempt(
        self,
        provider: str,
        credential_id: UUID,
        estimated_tokens: int,
        *,
        rpm_limit: int | None = None,
        tpm_limit: int | None = None,
        now: datetime | None = None,
    ) -> tuple[bool, str]:
        return self.rate.can_attempt(
            provider, credential_id, estimated_tokens, rpm_limit=rpm_limit, tpm_limit=tpm_limit, now=now
        )

    def estimate_cost(
        self,
        spec,  # ModelSpec
        prompt_tokens: int,
        completion_tokens: int,
    ) -> float | None:
        if spec.input_price is None or spec.output_price is None:
            return None
        cost = (prompt_tokens / 1_000_000) * spec.input_price
        cost += (completion_tokens / 1_000_000) * spec.output_price
        return round(cost, 6)

    def estimate_plan_cost(self, spec, input_tokens: int, assumed_output_tokens: int = 512) -> float | None:
        return self.estimate_cost(spec, input_tokens, assumed_output_tokens)

    def record_usage(
        self,
        *,
        trace_id: UUID,
        request_id: str | None,
        model_id: str,
        provider: str,
        source: str,
        credential_id: UUID,
        prompt_tokens: int,
        completion_tokens: int,
        cost_usd: float | None,
        latency_ms: int | None,
        spec=None,
    ) -> UsageRecord:
        total = prompt_tokens + completion_tokens
        if spec is not None:
            self.rate.record_attempt(provider, credential_id, total)
        record = UsageRecord(
            trace_id=trace_id,
            request_id=request_id,
            model_id=model_id,
            provider=provider,
            source=source,
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
            total_tokens=total,
            cost_usd=cost_usd,
            latency_ms=latency_ms,
            at=_utcnow(),
        )
        self._ledger.append(record)
        return record

    def ledger(self) -> list[UsageRecord]:
        return list(self._ledger)

    def summary(self) -> dict[str, object]:
        total_prompt = sum(r.prompt_tokens for r in self._ledger)
        total_completion = sum(r.completion_tokens for r in self._ledger)
        costs = [r.cost_usd for r in self._ledger if r.cost_usd is not None]
        by_source: dict[str, int] = collections.defaultdict(int)
        for record in self._ledger:
            by_source[record.source] += record.total_tokens
        return {
            "requests": len(self._ledger),
            "prompt_tokens": total_prompt,
            "completion_tokens": total_completion,
            "cost_usd_known": round(sum(costs), 6) if costs else 0.0,
            "cost_unknown_requests": len(self._ledger) - len(costs),
            "tokens_by_source": dict(by_source),
        }
