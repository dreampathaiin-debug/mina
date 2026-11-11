"""Phase 5 cache tests: scope, expiry, invalidation, and the safety gate
that keeps side-effecting requests out of every cache."""
import time

import pytest

from mina_api.optimizer.response_cache import (
    ExactResponseCache,
    SemanticResponseCache,
    exact_key,
    scope_key,
)
from mina_api.optimizer.safety import evaluate_cache_safety
from mina_api.optimizer.semantic import HashEmbedder, InMemorySemanticStore
from mina_api.optimizer.stores import (
    EmbeddingCache,
    InMemoryTemporaryCache,
    ToolSchemaCache,
)


def result():
    return {"content": "hello", "prompt_tokens": 100, "completion_tokens": 20}


def safe_decision():
    return evaluate_cache_safety(
        task_type="conversation", tools=None,
        final_user_text="what is the capital of France",
        has_tool_state=False, temperature=None, tool_execution_id=None,
    )


def unsafe_text(text):
    return evaluate_cache_safety(
        task_type="conversation", tools=None, final_user_text=text,
        has_tool_state=False, temperature=None, tool_execution_id=None,
    )


class TestExactCache:
    def test_hit_only_within_scope(self):
        cache = ExactResponseCache(InMemoryTemporaryCache(), ttl_seconds=60)
        messages = [{"role": "user", "content": "say hi"}]
        scope_u1 = scope_key("u1", "gpt-6-luna", "byok_first")
        key = exact_key(scope_u1, messages=messages, tools=None, temperature=None, max_tokens=None)
        assert cache.get(key) is None
        cache.put(key, result(), user_id="u1", conversation_id=None)
        assert cache.get(key) == result()
        # a different user / model / policy gets a different key and no answer
        other_user = exact_key(scope_key("u2", "gpt-6-luna", "byok_first"), messages=messages, tools=None, temperature=None, max_tokens=None)
        other_model = exact_key(scope_key("u1", "gemini-3.8-flash", "byok_first"), messages=messages, tools=None, temperature=None, max_tokens=None)
        other_policy = exact_key(scope_key("u1", "gpt-6-luna", "managed_only"), messages=messages, tools=None, temperature=None, max_tokens=None)
        assert cache.get(other_user) is None
        assert cache.get(other_model) is None
        assert cache.get(other_policy) is None

    def test_expiry(self):
        cache = ExactResponseCache(InMemoryTemporaryCache(), ttl_seconds=0.05)
        key = exact_key("s", messages=[{"role": "user", "content": "q"}], tools=None, temperature=None, max_tokens=None)
        cache.put(key, result(), user_id="u1", conversation_id=None)
        time.sleep(0.08)
        assert cache.get(key) is None

    def test_invalidation_by_user(self):
        cache = ExactResponseCache(InMemoryTemporaryCache(), ttl_seconds=60)
        key = exact_key(scope_key("u1", "m", "p"), messages=[{"role": "user", "content": "q"}], tools=None, temperature=None, max_tokens=None)
        cache.put(key, result(), user_id="u1", conversation_id=None)
        assert cache.invalidate_user("u1") >= 1
        assert cache.get(key) is None


class TestCacheSafety:
    UNSAFE_TASKS = [
        "payment", "order", "email_send", "account_change",
        "balance_check", "availability_check", "booking",
    ]

    def test_unsafe_task_types_never_cacheable(self):
        for task in self.UNSAFE_TASKS:
            decision = evaluate_cache_safety(
                task_type=task, tools=None, final_user_text="what is 2 plus 2",
                has_tool_state=False, temperature=None, tool_execution_id=None,
            )
            assert decision.cacheable is False, task
            assert decision.reason

    def test_unsafe_tools_never_cacheable(self):
        decision = evaluate_cache_safety(
            task_type="conversation",
            tools=[{"type": "function", "function": {"name": "send_email"}}],
            final_user_text="what is 2 plus 2",
            has_tool_state=False, temperature=None, tool_execution_id=None,
        )
        assert decision.cacheable is False

    def test_unsafe_intent_in_text_never_cacheable(self):
        for text in (
            "pay the invoice of rs 500",
            "send an email to the client",
            "place an order for the pizza",
            "change my account email address",
            "check my live account balance",
            "is the hotel available tonight",
        ):
            assert unsafe_text(text).cacheable is False, text

    def test_non_deterministic_sampling_not_cacheable(self):
        decision = evaluate_cache_safety(
            task_type="conversation", tools=None, final_user_text="hi",
            has_tool_state=False, temperature=0.7, tool_execution_id=None,
        )
        assert decision.cacheable is False

    def test_mid_tool_round_not_cacheable(self):
        for kwargs in ({"tool_execution_id": "round-1", "has_tool_state": False},
                       {"has_tool_state": True, "tool_execution_id": None}):
            decision = evaluate_cache_safety(
                task_type="conversation", tools=None, final_user_text="hi",
                temperature=None, **kwargs,
            )
            assert decision.cacheable is False, kwargs

    def test_safe_request_cacheable(self):
        assert safe_decision().cacheable is True


class TestSemanticCache:
    TEXT = "the quarterly plan review covers budgets and hiring and marketing spend"

    def cache(self, threshold=0.60, min_tokens=1):
        return SemanticResponseCache(
            InMemorySemanticStore(), HashEmbedder(), threshold=threshold, min_tokens_to_store=min_tokens
        )

    def test_high_similarity_hits_low_misses(self):
        cache = self.cache()
        scope = scope_key("u1", "m", "p")
        paraphrase = "the quarterly plan review covers marketing spend, hiring and budgets"
        assert cache.store(scope, self.TEXT, result(), input_tokens=100, decision=safe_decision())
        hit = cache.lookup(scope, paraphrase)
        assert hit is not None and hit[0]["content"] == "hello" and 0.0 < hit[1] <= 1.0
        miss = cache.lookup(scope, "completely unrelated topic about a cricket match in chennai")
        assert miss is None

    def test_scoped_by_user(self):
        cache = self.cache()
        scope = scope_key("u1", "m", "p")
        cache.store(scope, self.TEXT, result(), input_tokens=100, decision=safe_decision())
        assert cache.lookup(scope_key("u2", "m", "p"), self.TEXT) is None

    def test_unsafe_decision_never_stored(self):
        cache = self.cache()
        stored = cache.store(
            scope_key("u1", "m", "p"), self.TEXT, result(), input_tokens=100,
            decision=evaluate_cache_safety(
                task_type="payment", tools=None, final_user_text=None,
                has_tool_state=False, temperature=None, tool_execution_id=None,
            ),
        )
        assert stored is False
        assert cache.lookup(scope_key("u1", "m", "p"), self.TEXT) is None

    def test_below_min_tokens_not_stored(self):
        cache = self.cache(min_tokens=1000)
        assert cache.store(scope_key("u1", "m", "p"), self.TEXT, result(), input_tokens=50, decision=safe_decision()) is False

    def test_invalidation_by_scope_prefix(self):
        cache = self.cache()
        scope = scope_key("u1", "m", "p")
        cache.store(scope, self.TEXT, result(), input_tokens=100, decision=safe_decision())
        assert cache.invalidate_scope(scope) >= 1
        assert cache.lookup(scope, self.TEXT) is None


class TestSecondaryCaches:
    def test_tool_schema_cache_roundtrip(self):
        cache = ToolSchemaCache(InMemoryTemporaryCache(), ttl_seconds=60)
        tool = {"type": "function", "function": {"name": "search_flights", "parameters": {"type": "object"}}}
        assert cache.get(tool) is None
        cache.put(tool, {"encoded": True})
        assert cache.get(tool) == {"encoded": True}
        # a different tool does not collide
        other = {"type": "function", "function": {"name": "calendar_write"}}
        assert cache.get(other) is None

    def test_embedding_cache_reuses_vectors(self):
        cache = EmbeddingCache(InMemoryTemporaryCache(), ttl_seconds=60)
        vec = [0.1, 0.2]
        cache.put("hash-256", "hello world", vec)
        assert cache.get("hash-256", "hello world") == vec
        assert cache.get("hash-256", "other text") is None

    def test_in_memory_temporary_cache_expires(self):
        cache = InMemoryTemporaryCache()
        cache.set("k", {"a": 1}, 0.05)
        assert cache.get("k") == {"a": 1}
        time.sleep(0.08)
        assert cache.get("k") is None
