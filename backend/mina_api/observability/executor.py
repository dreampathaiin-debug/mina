"""Phase 10 observed executor: budget gate + usage accounting.

Wraps the routing executor (base or OptimizingExecutor). Before every
attempt it enforces budgets (hard stops raise QuotaExceededError, which
surfaces to the client as 429) and takes a concurrency slot; after the
attempt it records one usage_event with the full tracking set. Keys and
tokens never enter the recorder.
"""

from __future__ import annotations

import time

from mina_api.observability.budgets import ActiveRequestRegistry, QuotaExceededError
from mina_api.observability.context import (
    obs_cache_hit_var,
    obs_conversation_id_var,
    obs_fallback_count_var,
    obs_retry_count_var,
    obs_task_id_var,
    obs_user_id_var,
)
from mina_api.observability.recorder import ObservabilityRecorder
from mina_api.routing.fallback_manager import ModelCallError

import typing

if typing.TYPE_CHECKING:  # pragma: no cover
    from mina_api.routing.routing_policy import Candidate

# The middleware module owns request-id propagation; re-use its var.
from mina_api.core.logging import request_id_var as obs_request_id_var


class ObservabilityExecutor:
    def __init__(
        self,
        base,
        *,
        recorder: ObservabilityRecorder,
        budget_check=None,
        active_requests: ActiveRequestRegistry | None = None,
        cost_estimator=None,
    ) -> None:
        self._base = base
        self._recorder = recorder
        # budget_check(user_id, provider) -> BudgetDecision; raises QuotaExceededError on hard stop.
        self._budget_check = budget_check
        self._active = active_requests
        self._cost_estimator = cost_estimator

    async def complete(self, candidate: "Candidate", request) -> object:
        meta = getattr(request, "metadata", None) or {}
        user_id = meta.get("user_id") or obs_user_id_var.get()
        spec = candidate.spec
        provider = getattr(spec, "provider", None)
        decision = None
        slot_taken = False

        # Reset per-attempt ambient routing facts so a previous call in
        # the same context cannot leak into this event.
        obs_cache_hit_var.set(None)
        obs_retry_count_var.set(0)
        obs_fallback_count_var.set(0)

        if self._budget_check is not None and user_id:
            decision = await self._budget_check(str(user_id), provider)
            if self._active is not None:
                if not self._active.acquire(str(user_id), decision.concurrent_limit):
                    raise QuotaExceededError("concurrent_requests", "Concurrent request limit reached")
                slot_taken = True

        started = time.perf_counter()
        try:
            result = await self._base.complete(candidate, request)
        except ModelCallError as exc:
            if user_id:
                await self._record_error(candidate, str(user_id), exc)
            raise
        except QuotaExceededError:
            raise
        except Exception as exc:  # unknown failure, still accounted
            if user_id:
                fake = ModelCallError("unknown", detail=str(exc))
                await self._record_error(candidate, str(user_id), fake)
            raise
        finally:
            if slot_taken and self._active is not None and user_id:
                self._active.release(str(user_id))

        latency_ms = int((time.perf_counter() - started) * 1000)
        prompt_tokens = getattr(result, "prompt_tokens", 0) or 0
        completion_tokens = getattr(result, "completion_tokens", 0) or 0
        estimated_cost = None
        if self._cost_estimator is not None:
            estimated_cost = self._cost_estimator(spec, prompt_tokens, completion_tokens)
        if user_id:
            await self._recorder.record_model_call(
                None,  # recorder manages its own session via factory below when None
                user_id=str(user_id),
                provider=provider,
                model=candidate.model_id,
                selection_reason=candidate.reason,
                request_id=obs_request_id_var.get(),
                conversation_id=meta.get("conversation_id") or obs_conversation_id_var.get(),
                task_id=meta.get("task_id") or obs_task_id_var.get(),
                input_tokens=prompt_tokens,
                output_tokens=completion_tokens,
                estimated_cost_usd=estimated_cost,
                billing_source=getattr(candidate.credential, "source", None),
                cache_hit=obs_cache_hit_var.get(),
                latency_ms=latency_ms,
                retry_count=obs_retry_count_var.get(),
                fallback_count=obs_fallback_count_var.get(),
            )
        return result

    async def _record_error(self, candidate: "Candidate", user_id: str, exc: ModelCallError) -> None:
        spec = candidate.spec
        estimated_cost = None
        if self._cost_estimator is not None:
            estimated_cost = self._cost_estimator(spec, 0, 0)
        await self._recorder.record_model_call(
            None,
            user_id=user_id,
            provider=getattr(spec, "provider", None),
            model=candidate.model_id,
            selection_reason=candidate.reason,
            request_id=obs_request_id_var.get(),
            conversation_id=obs_conversation_id_var.get(),
            task_id=obs_task_id_var.get(),
            input_tokens=0,
            output_tokens=0,
            estimated_cost_usd=estimated_cost,
            billing_source=getattr(candidate.credential, "source", None),
            cache_hit=obs_cache_hit_var.get(),
            latency_ms=None,
            retry_count=obs_retry_count_var.get(),
            fallback_count=obs_fallback_count_var.get(),
            error_category=exc.kind,
        )
