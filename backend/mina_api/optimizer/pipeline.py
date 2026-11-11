"""The optimization pipeline and its executor.

OptimizingExecutor wraps a base executor with the fallback-manager
contract (complete(candidate, request)) and applies, in order:

  1. token estimation of the raw request
  2. cache lookup (exact, then semantic) - gated by CacheSafety, scoped by
     user + model + policy
  3. context optimization (system dedupe, task state, memory retrieval,
     recent-turn selection + summarization, atomic tool-turn preservation)
  4. dynamic tool schema selection
  5. task-specific output token limit
  6. provider prompt-cache markers on the stable prefix
  7. the base model call, then response-store (safety-gated) + metrics

Mid-tool rounds are pinned: history stays verbatim and caching is skipped.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, replace

from mina_api.optimizer.context_optimizer import optimize_context
from mina_api.optimizer.memory_retrieval import MemoryItem
from mina_api.optimizer.metrics import MetricsLedger, OptimizationRecord
from mina_api.optimizer.output_limits import apply_output_limit
from mina_api.optimizer.prompt_caching import apply_cache_markers, supports_prompt_caching
from mina_api.optimizer.response_cache import ExactResponseCache, SemanticResponseCache, exact_key, scope_key
from mina_api.optimizer.safety import evaluate_cache_safety
from mina_api.optimizer.semantic import Embedder
from mina_api.optimizer.stores import TemporaryCache, ToolSchemaCache
from mina_api.optimizer.summarization import Summarizer
from mina_api.optimizer.token_estimation import estimate_messages, estimate_request, estimate_text
from mina_api.optimizer.tool_selection import select_tools
from mina_api.routing.fallback_manager import ChatRequestSpec, CompletionResult
from mina_api.routing.provider_registry import ModelSpec
from mina_api.routing.routing_policy import Candidate
from mina_api.observability.context import obs_cache_hit_var


@dataclass(frozen=True)
class OptimizerConfig:
    enabled: bool = True
    response_cache_ttl_seconds: float = 3600.0
    semantic_cache_enabled: bool = True
    semantic_threshold: float = 0.94
    min_cache_input_tokens: int = 512
    memory_budget_tokens: int = 1024
    tool_budget_tokens: int = 4000
    tool_selection_enabled: bool = True
    prompt_caching_enabled: bool = True
    context_reserve_ratio: float = 0.85


@dataclass(frozen=True)
class CompletionPayload:
    """The cached part of a CompletionResult (everything but model_id,
    which belongs to the candidate that served the replay)."""

    content: str | None
    tool_calls: list[dict] | None
    prompt_tokens: int
    completion_tokens: int
    finish_reason: str | None

    def to_result(self, model_id: str, latency_ms: int = 0) -> CompletionResult:
        return CompletionResult(
            content=self.content, tool_calls=self.tool_calls, model_id=model_id,
            prompt_tokens=self.prompt_tokens, completion_tokens=self.completion_tokens,
            latency_ms=latency_ms, finish_reason=self.finish_reason,
        )

    @classmethod
    def from_result(cls, result: CompletionResult) -> CompletionPayload:
        return cls(
            content=result.content, tool_calls=result.tool_calls,
            prompt_tokens=result.prompt_tokens, completion_tokens=result.completion_tokens,
            finish_reason=result.finish_reason,
        )


class OptimizingExecutor:
    def __init__(
        self,
        base_executor,
        *,
        cache: TemporaryCache,
        semantic_store,
        embedder: Embedder,
        summarizer: Summarizer,
        ledger: MetricsLedger,
        config,
    ) -> None:
        self.base = base_executor
        self.cache = cache
        self.exact_cache = ExactResponseCache(cache, ttl_seconds=config.response_cache_ttl_seconds)
        self.semantic_cache = SemanticResponseCache(
            semantic_store, embedder,
            threshold=config.semantic_threshold,
            min_tokens_to_store=config.min_cache_input_tokens,
        )
        self.tool_cache = ToolSchemaCache(cache)
        self.summarizer = summarizer
        self.ledger = ledger
        self.config = config

    async def complete(self, candidate: Candidate, request: ChatRequestSpec) -> CompletionResult:
        spec: ModelSpec = candidate.spec  # type: ignore[assignment]
        started = time.monotonic()
        if request.metadata.get("optimizer_enabled") is False:
            return await self.base.complete(candidate, request)

        meta = request.metadata
        user_id = str(meta.get("user_id", "anonymous"))
        policy = str(meta.get("policy", "byok_only"))
        task_type = str(meta.get("task_type", "conversation"))
        memories = meta.get("memories") or []
        memory_items = [MemoryItem(id=m["id"], text=m["text"], importance=m.get("importance", 0.5)) for m in memories]
        query_text = self._final_user_text(request.messages)

        decision = evaluate_cache_safety(
            task_type=task_type,
            tools=request.tools,
            final_user_text=query_text,
            has_tool_state=self._has_tool_state(request.messages),
            temperature=request.temperature,
            tool_execution_id=request.tool_execution_id,
        )

        tokens_before = estimate_request(request.messages, tools=request.tools, provider=spec.provider)
        scope = scope_key(user_id, spec.model_id, policy)

        # --- cache replay path (exact first, then semantic) ---
        if decision.cacheable:
            key = exact_key(
                scope, messages=request.messages, tools=request.tools,
                temperature=request.temperature, max_tokens=request.max_tokens,
            )
            payload_dict = self.exact_cache.get(key)
            if payload_dict is None and self.config.semantic_cache_enabled:
                found = self.semantic_cache.lookup(scope, query_text or "")
                if found is not None:
                    payload_dict, _score = found
                    cache_type = "semantic"
                else:
                    cache_type = None
            else:
                cache_type = "exact" if payload_dict else None
            if payload_dict:
                payload = CompletionPayload(**payload_dict)
                hit_tokens = estimate_request(request.messages, tools=request.tools, provider=spec.provider)
                self.ledger.record(
                    OptimizationRecord(
                        user_id=user_id,
                        tokens_before=hit_tokens, tokens_after=0,
                        output_tokens=payload.completion_tokens,
                        cache_hit=cache_type,
                        cache_saved_tokens=hit_tokens,
                        est_savings_usd=self._cost(hit_tokens, spec),
                        latency_ms=int((time.monotonic() - started) * 1000),
                        model_id=spec.model_id, task_type=task_type,
                    )
                )
                await self.ledger.flush()
                obs_cache_hit_var.set(cache_type or "none")
                return payload.to_result(spec.model_id, latency_ms=int((time.monotonic() - started) * 1000))

        # --- optimization path ---
        preserve = request.tool_execution_id is not None or self._has_tool_state(request.messages)
        output_reserve = apply_output_limit(task_type, request.max_tokens, spec.max_output_tokens)
        optimized = optimize_context(
            messages=request.messages,
            tools_tokens=estimate_request([], tools=request.tools, provider=spec.provider),
            model_context_window=spec.context_window,
            output_reserve=output_reserve,
            query_text=query_text or "",
            task_state=meta.get("task_state"),
            memories=memory_items,
            memory_budget_tokens=self.config.memory_budget_tokens,
            summarizer=self.summarizer,
            context_reserve_ratio=self.config.context_reserve_ratio,
            provider=spec.provider,
            preserve_full_history=preserve,
        )

        selected_tools = request.tools
        if request.tools and self.config.tool_selection_enabled:
            selected_tools = select_tools(
                query_text or "", request.tools,
                required_tools=meta.get("required_tools"),
                budget_tokens=self.config.tool_budget_tokens,
                provider=spec.provider,
            )
            for tool in selected_tools:
                if self.tool_cache.get(tool) is None:
                    self.tool_cache.put(tool, tool)  # normalized/serialized form cached

        limit = output_reserve  # already task- and model-clamped
        out_messages = optimized.messages
        if self.config.prompt_caching_enabled:
            prefix_tokens = sum(
                estimate_text(m.get("content") if isinstance(m.get("content"), str) else "", spec.provider)
                + 4
                for m in out_messages
                if m.get("role") == "system"
            )
            mark = apply_cache_markers(out_messages, spec.provider, prefix_tokens)
            if mark.marked_prefix > 0:
                out_messages = out_messages  # markers were written in place above

        optimized_request = replace(
            request,
            messages=out_messages,
            tools=selected_tools,
            max_tokens=limit,
        )
        result = await self.base.complete(candidate, optimized_request)

        tokens_after = estimate_request(
            optimized_request.messages, tools=optimized_request.tools, provider=spec.provider
        )
        self.ledger.record(
            OptimizationRecord(
                user_id=user_id,
                tokens_before=tokens_before, tokens_after=tokens_after,
                output_tokens=result.completion_tokens,
                cache_hit=None, latency_ms=int((time.monotonic() - started) * 1000),
                model_id=spec.model_id, task_type=task_type,
            )
        )
        await self.ledger.flush()
        obs_cache_hit_var.set("none")

        # --- store for future replay (safety already checked above) ---
        if (
            decision.cacheable
            and result.finish_reason in {"stop", None}
            and tokens_before >= self.config.min_cache_input_tokens
        ):
            payload = CompletionPayload.from_result(result)
            payload_dict = {
                "content": payload.content, "tool_calls": payload.tool_calls,
                "prompt_tokens": 0,  # a replay pays no input tokens
                "completion_tokens": payload.completion_tokens,
                "finish_reason": payload.finish_reason,
            }
            key = exact_key(
                scope, messages=request.messages, tools=request.tools,
                temperature=request.temperature, max_tokens=request.max_tokens,
            )
            self.exact_cache.put(
                key, payload_dict, user_id=user_id, conversation_id=request.conversation_id
            )
            if self.config.semantic_cache_enabled:
                self.semantic_cache.store(
                    scope, query_text or "", payload_dict,
                    input_tokens=tokens_before, decision=decision,
                )

        return result

    @staticmethod
    def _final_user_text(messages: list[dict]) -> str | None:
        for message in reversed(messages):
            if message.get("role") == "user":
                content = message.get("content")
                return content if isinstance(content, str) else json_safe(content)
        return None

    @staticmethod
    def _has_tool_state(messages: list[dict]) -> bool:
        return any(
            m.get("role") == "tool" or m.get("tool_call_id") or m.get("tool_calls")
            for m in messages
        )

    @staticmethod
    def _cost(tokens: int, spec: ModelSpec) -> float:
        if spec.input_price is None:
            return 0.0
        return tokens / 1_000_000 * spec.input_price


def json_safe(value) -> str:
    import json

    return json.dumps(value, default=str)
