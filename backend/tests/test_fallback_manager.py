"""Execution failure policy: auth never retried, Retry-After honoured,
bounded 5xx retries, context escalation, and the mid-tool pin."""
import asyncio
from datetime import datetime, timedelta, timezone
from uuid import uuid4

import pytest

from mina_api.routing.fallback_manager import (
    ChatRequestSpec,
    CompletionResult,
    FallbackManager,
    ModelCallError,
    RoutingExhausted,
)
from mina_api.routing.provider_health import ProviderHealth
from mina_api.routing.routing_policy import (
    Candidate,
    ModelGroup,
    ResolvedCredential,
    RoutePlan,
    TaskSpec,
    UserPrefs,
)
from mina_api.routing.usage_tracker import UsageTracker


def spec(mid="m1", provider="openai", tools=True, context=100_000, priority=10):
    class S:
        pass
    s = S()
    s.model_id = mid
    s.provider = provider
    s.tools = tools
    s.context_window = context
    s.input_price = 0.10
    s.output_price = 0.50
    s.priority = priority
    s.timeout_seconds = 30
    s.groups = {"balanced"}
    s.display_name = mid
    s.max_output_tokens = 4096
    s.vision = False
    s.json_mode = True
    s.enabled = True
    s.supports_group = lambda g: g in s.groups
    return s


def candidate(mid="m1", provider="openai", source="byok"):
    return Candidate(
        spec=spec(mid, provider),
        credential=ResolvedCredential(uuid4(), provider, source, "sk-x"),
        reason="group_match",
    )


def plan(*candidates):
    return RoutePlan(
        trace_id=uuid4(), request_id=None, group=ModelGroup.BALANCED,
        candidates=list(candidates), task=TaskSpec(task_type="conversation"), prefs=UserPrefs(),
    )


class ScriptedExecutor:
    """Raises or returns per call, in order."""

    def __init__(self, script):
        self.script = script
        self.calls: list[str] = []

    async def complete(self, candidate, request):
        self.calls.append(candidate.spec.model_id)
        outcome = self.script[len(self.calls) - 1]
        if isinstance(outcome, Exception):
            raise outcome
        return outcome


def result(mid="m1", content="ok", prompt=100, completion=10):
    return CompletionResult(
        content=content, tool_calls=None, model_id=mid, prompt_tokens=prompt,
        completion_tokens=completion, latency_ms=5, finish_reason="stop",
    )


def make_manager():
    health = ProviderHealth()
    usage = UsageTracker()
    return FallbackManager(health, usage, max_retries=2, retry_backoff_seconds=0.0), health, usage


def test_success_on_first_candidate():
    manager, _, _ = make_manager()
    executor = ScriptedExecutor([result()])

    async def go():
        return await manager.execute(plan(candidate("m1")), ChatRequestSpec(messages=[]), executor)

    outcome = asyncio.run(go())
    assert outcome.result.content == "ok"
    assert outcome.cost_usd == pytest.approx(100 / 1e6 * 0.10 + 10 / 1e6 * 0.50)


def test_auth_failure_is_never_retried_and_moves_on():
    manager, health, _ = make_manager()
    c1, c2 = candidate("m1"), candidate("m2", provider="groq")
    executor = ScriptedExecutor([
        ModelCallError("auth", status_code=401, detail="bad key"),
        result("m2"),
    ])

    async def go():
        return await manager.execute(plan(c1, c2), ChatRequestSpec(messages=[]), executor)

    outcome = asyncio.run(go())
    assert outcome.result.model_id == "m2"
    assert executor.calls == ["m1", "m2"]  # no second try on m1
    ok, reason = health.is_available("openai", c1.credential.credential_id)
    # Right now the auth cooldown holds; even after it lapses the credential
    # stays unhealthy until the user re-tests the key.
    assert not ok and "cooling down" in reason
    ok, reason = health.is_available("openai", c1.credential.credential_id, now=datetime.now(timezone.utc) + timedelta(seconds=901))
    assert not ok and "unhealthy" in reason


def test_auth_failure_blocks_sibling_models_on_the_same_credential():
    manager, _, _ = make_manager()
    shared = ResolvedCredential(uuid4(), "openai", "byok", "sk-shared")
    c1 = Candidate(spec=spec("m1"), credential=shared, reason="group_match")
    c2 = Candidate(spec=spec("m2"), credential=shared, reason="group_match")
    executor = ScriptedExecutor([ModelCallError("auth", status_code=401, detail="bad key")])

    async def go():
        return await manager.execute(plan(c1, c2), ChatRequestSpec(messages=[]), executor)

    with pytest.raises(RoutingExhausted) as info:
        asyncio.run(go())
    assert executor.calls == ["m1"]  # m2 shares the broken key: skipped, not retried


def test_rate_limit_respects_retry_after_and_falls_back():
    manager, health, _ = make_manager()
    c1, c2 = candidate("m1"), candidate("m2", provider="groq")
    executor = ScriptedExecutor([
        ModelCallError("rate_limited", status_code=429, retry_after=300, detail="slow down"),
        result("m2"),
    ])

    async def go():
        return await manager.execute(plan(c1, c2), ChatRequestSpec(messages=[]), executor)

    outcome = asyncio.run(go())
    assert outcome.result.model_id == "m2"
    ok, reason = health.is_available("openai", c1.credential.credential_id)
    assert not ok and "cooling down" in reason


def test_server_error_retries_bounded_then_falls_back():
    manager, _, _ = make_manager()
    c1, c2 = candidate("m1"), candidate("m2", provider="groq")
    executor = ScriptedExecutor([
        ModelCallError("server", status_code=500, detail="boom"),
        ModelCallError("server", status_code=500, detail="boom"),
        ModelCallError("server", status_code=500, detail="boom"),
        result("m2"),
    ])

    async def go():
        return await manager.execute(plan(c1, c2), ChatRequestSpec(messages=[]), executor)

    outcome = asyncio.run(go())
    assert outcome.result.model_id == "m2"
    assert executor.calls.count("m1") == 3  # initial + 2 bounded retries


def test_timeout_retries_bounded():
    manager, _, _ = make_manager()
    executor = ScriptedExecutor([ModelCallError("timeout", detail="slow"), result()])

    async def go():
        return await manager.execute(plan(candidate()), ChatRequestSpec(messages=[]), executor)

    outcome = asyncio.run(go())
    assert outcome.result.content == "ok"
    assert executor.calls == ["m1", "m1"]


def test_context_overflow_uses_larger_context_candidate():
    manager, _, _ = make_manager()
    small, big = candidate("m-small"), candidate("m-big")
    executor = ScriptedExecutor([
        ModelCallError("context_overflow", detail="prompt too long"),
        result("m-big"),
    ])

    async def go():
        return await manager.execute(plan(small, big), ChatRequestSpec(messages=[]), executor)

    outcome = asyncio.run(go())
    assert outcome.result.model_id == "m-big"


def test_forbidden_is_reported_not_retried():
    manager, _, _ = make_manager()
    executor = ScriptedExecutor([
        ModelCallError("forbidden", status_code=403, detail="region blocked"),
        result("m2"),
    ])

    async def go():
        return await manager.execute(
            plan(candidate("m1"), candidate("m2", provider="groq")), ChatRequestSpec(messages=[]), executor
        )

    outcome = asyncio.run(go())
    assert outcome.result.model_id == "m2"
    assert executor.calls == ["m1", "m2"]


def test_exhausted_raises_with_attempts():
    manager, _, _ = make_manager()
    executor = ScriptedExecutor([
        ModelCallError("forbidden", status_code=403), ModelCallError("forbidden", status_code=403),
    ])

    async def go():
        await manager.execute(
            plan(candidate("m1"), candidate("m2", provider="groq")), ChatRequestSpec(messages=[]), executor
        )

    with pytest.raises(RoutingExhausted) as info:
        asyncio.run(go())
    assert len(info.value.attempts) == 2


def test_mid_tool_round_is_pinned_to_its_model():
    manager, _, _ = make_manager()
    c1, c2 = candidate("m1"), candidate("m2", provider="groq")
    executor = ScriptedExecutor([
        ModelCallError("server", status_code=500),
        ModelCallError("server", status_code=500),
        ModelCallError("server", status_code=500),
    ])
    request = ChatRequestSpec(messages=[], tool_execution_id="tool-exec-1")

    async def go():
        await manager.execute(plan(c1, c2), request, executor)

    with pytest.raises(RoutingExhausted) as info:
        asyncio.run(go())
    assert "not switching models mid-round" in str(info.value)
    assert executor.calls == ["m1", "m1", "m1"]


def test_mid_tool_round_never_falls_back_even_on_auth():
    manager, _, _ = make_manager()
    executor = ScriptedExecutor([ModelCallError("auth", status_code=401)])
    request = ChatRequestSpec(messages=[], tool_execution_id="tool-exec-9")

    async def go():
        await manager.execute(
            plan(candidate("m1"), candidate("m2", provider="groq")), request, executor
        )

    with pytest.raises(RoutingExhausted):
        asyncio.run(go())
    assert executor.calls == ["m1"]


def test_no_candidates_raises_clean():
    manager, _, _ = make_manager()

    async def go():
        await manager.execute(plan(), ChatRequestSpec(messages=[]), ScriptedExecutor([]))

    with pytest.raises(RoutingExhausted):
        asyncio.run(go())
