"""Execution with the failure policy, fallback, retries and the breaker.

The manager consumes an ordered RoutingPlan and drives attempts:

- 401: credential marked unhealthy; NEVER retried; next candidate.
- 403: reported as an access restriction; next candidate.
- 429: Retry-After honored as a cooldown; a compatible candidate takes
  the request now.
- 5xx/timeout/network: bounded retries on the same candidate, then the
  next candidate.
- context overflow: no retry; the plan's later (larger-context) candidates
  take over, otherwise the caller is told to compact.
- unsupported tools: prevented at planning time; a runtime refusal is
  treated as terminal for that candidate.

Tool-execution safety: when request.tool_execution_id is set, the manager
is LOCKED to the (model, credential) that produced the current tool round.
It may retry that exact candidate within bounds, but never switches to a
different model or credential mid-round; instead it raises so the caller
can surface the state instead of silently corrupting the tool loop.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from datetime import datetime
from mina_api.observability.context import obs_fallback_count_var, obs_retry_count_var
from uuid import UUID, uuid4


@dataclass(frozen=True)
class ChatRequestSpec:
    messages: list[dict]
    tools: list[dict] | None = None
    temperature: float | None = None
    max_tokens: int | None = None
    # Set while an agent loop is mid tool round: the model is pinned.
    tool_execution_id: str | None = None
    task_id: str | None = None
    conversation_id: str | None = None
    metadata: dict = field(default_factory=dict)


@dataclass(frozen=True)
class CompletionResult:
    content: str | None
    tool_calls: list[dict] | None
    model_id: str
    prompt_tokens: int
    completion_tokens: int
    latency_ms: int
    finish_reason: str | None = None


@dataclass(frozen=True)
class AttemptLog:
    attempt_id: UUID
    trace_id: UUID
    candidate_index: int
    model_id: str
    provider: str
    credential_id: UUID
    source: str
    error_kind: str | None
    detail: str | None
    retry_number: int
    latency_ms: int | None
    at: datetime


class ModelCallError(Exception):
    def __init__(self, kind: str, *, status_code: int | None = None,
                 retry_after: float | None = None, detail: str = "") -> None:
        super().__init__(detail or kind)
        self.kind = kind
        self.status_code = status_code
        self.retry_after = retry_after
        self.detail = detail


class RoutingExhausted(Exception):
    def __init__(self, message: str, attempts: list[AttemptLog]) -> None:
        super().__init__(message)
        self.attempts = attempts


@dataclass(frozen=True)
class CompletionOutcome:
    result: CompletionResult
    candidate: object  # Candidate
    trace_id: UUID
    attempts: list[AttemptLog]
    cost_usd: float | None


class FallbackManager:
    RETRYABLE = {"server", "timeout", "network"}

    def __init__(self, health, usage, *, max_retries: int = 2, retry_backoff_seconds: float = 0.5) -> None:
        self.health = health
        self.usage = usage
        self.max_retries = max_retries
        self.retry_backoff_seconds = retry_backoff_seconds

    async def execute(
        self,
        plan,  # RoutePlan
        request: ChatRequestSpec,
        executor,  # CompletionExecutor
    ) -> CompletionOutcome:
        if not plan.candidates:
            raise RoutingExhausted(
                "no routable candidate: every model was rejected by policy, health or rate limits",
                attempts=[],
            )

        locked_model: str | None = None
        locked_credential: UUID | None = None
        attempts: list[AttemptLog] = []

        for index, candidate in enumerate(plan.candidates):
            spec = candidate.spec
            # Re-check the health gate per candidate: a 401 on one model makes
            # its credential unhealthy, so sibling models on the same key are
            # skipped instead of blindly retried with the broken key.
            ok, _gate = self.health.is_available(
                candidate.provider, candidate.credential.credential_id
            )
            if not ok and not (request.tool_execution_id is not None and index == 0):
                continue
            if request.tool_execution_id is not None:
                # Mid tool round: only the exact issuing model+credential may run.
                if locked_model is not None and (
                    spec.model_id != locked_model or candidate.credential.credential_id != locked_credential
                ):
                    continue
                if locked_model is None and index > 0:
                    # The round was issued by an earlier model; candidates ahead of it
                    # in the plan are different models and are forbidden mid-round.
                    raise RoutingExhausted(
                        "tool execution in progress; model and credential are pinned for this round",
                        attempts=attempts,
                    )

            retry_number = 0
            while True:
                attempt_id = uuid4()
                started = datetime.now().astimezone()
                # Phase 10: publish routing facts for the observability layer.
                obs_retry_count_var.set(retry_number)
                obs_fallback_count_var.set(index)
                try:
                    result = await executor.complete(candidate, request)
                except ModelCallError as exc:
                    latency_ms = int((datetime.now().astimezone() - started).total_seconds() * 1000)
                    attempts.append(AttemptLog(
                        attempt_id=attempt_id, trace_id=plan.trace_id, candidate_index=index,
                        model_id=spec.model_id, provider=candidate.provider,
                        credential_id=candidate.credential.credential_id, source=candidate.credential.source,
                        error_kind=exc.kind, detail=exc.detail, retry_number=retry_number,
                        latency_ms=latency_ms, at=started,
                    ))
                    self.health.mark_failure(
                        candidate.provider, candidate.credential.credential_id, exc.kind,
                        retry_after_seconds=exc.retry_after,
                    )
                    if request.tool_execution_id is not None:
                        # Pinned round: never fall back to another model. Bounded
                        # retry only for transient kinds; everything else surfaces.
                        if exc.kind in self.RETRYABLE and retry_number < self.max_retries:
                            retry_number += 1
                            await asyncio.sleep(self.retry_backoff_seconds * retry_number)
                            continue
                        raise RoutingExhausted(
                            f"tool round {request.tool_execution_id} failed on its pinned model "
                            f"{spec.model_id} ({exc.kind}); not switching models mid-round",
                            attempts=attempts,
                        )
                    if exc.kind == "auth":
                        break  # credential unhealthy; next candidate, never a blind retry
                    if exc.kind in ("forbidden", "context_overflow", "unsupported_tools", "unknown"):
                        break
                    if exc.kind == "rate_limited":
                        break  # cooldown recorded; next candidate serves now
                    if exc.kind in self.RETRYABLE and retry_number < self.max_retries:
                        retry_number += 1
                        await asyncio.sleep(self.retry_backoff_seconds * retry_number)
                        continue
                    break
                except Exception as exc:  # executor bug: treat as unknown, move on
                    attempts.append(AttemptLog(
                        attempt_id=attempt_id, trace_id=plan.trace_id, candidate_index=index,
                        model_id=spec.model_id, provider=candidate.provider,
                        credential_id=candidate.credential.credential_id, source=candidate.credential.source,
                        error_kind="unknown", detail=f"{type(exc).__name__}: {exc}", retry_number=retry_number,
                        latency_ms=None, at=started,
                    ))
                    self.health.mark_failure(candidate.provider, candidate.credential.credential_id, "unknown")
                    break

                # Success path
                latency_ms = result.latency_ms
                self.health.mark_success(candidate.provider, candidate.credential.credential_id)
                cost = self.usage.estimate_cost(spec, result.prompt_tokens, result.completion_tokens)
                self.usage.record_usage(
                    trace_id=plan.trace_id, request_id=plan.request_id, model_id=result.model_id,
                    provider=candidate.provider, source=candidate.credential.source,
                    credential_id=candidate.credential.credential_id,
                    prompt_tokens=result.prompt_tokens, completion_tokens=result.completion_tokens,
                    cost_usd=cost, latency_ms=latency_ms, spec=spec,
                )
                return CompletionOutcome(
                    result=result, candidate=candidate, trace_id=plan.trace_id,
                    attempts=attempts, cost_usd=cost,
                )

            if request.tool_execution_id is not None:
                locked_model = locked_model or spec.model_id
                locked_credential = locked_credential or candidate.credential.credential_id

        raise RoutingExhausted(
            "all candidates failed; last errors: "
            + "; ".join(f"{a.model_id}:{a.error_kind}" for a in attempts[-5:]),
            attempts=attempts,
        )
