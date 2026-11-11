"""Phase 5 integration: the optimizer runs inside the full model router,
replays are scoped and safe, and every saving it claims is measured."""
import asyncio
from uuid import uuid4

from fastapi.testclient import TestClient

from mina_api.routing.fallback_manager import ChatRequestSpec, CompletionResult
from mina_api.routing.routing_policy import TaskSpec
from tests.conftest import headers_for

OPENAI_KEY = "sk-openai-test-key-abcdefgh12345678"
SAFE_TEXT = ("summarize the quarterly plan and list the three biggest risks we discussed in detail for the board. " * 45)
UNSAFE_TEXT = ("please pay the invoice for the vendor right now. " * 30)


def _seed(app, user_id, provider, key):
    from mina_api.services.provider_keys import create_key

    async def _go():
        async with app.state.database.session() as session:
            row = await create_key(
                session, app.state.crypto, app.state.registry, user_id, provider, key
            )
            await session.commit()
            return row

    return asyncio.run(_go())


class RecordingExecutor:
    """Stands in for the real LiteLLM executor under the optimizer."""

    def __init__(self):
        self.calls: list[ChatRequestSpec] = []

    async def complete(self, candidate, request):
        self.calls.append(request)
        return CompletionResult(
            content="quarterly plan summary: three risks, budget, hiring, churn.",
            tool_calls=None, model_id=candidate.spec.model_id,
            prompt_tokens=650, completion_tokens=30, latency_ms=5, finish_reason="stop",
        )


def _complete(app, user_id, text):
    router = app.state.model_router

    async def _go():
        async with app.state.database.session() as session:
            return await router.complete(
                session, user_id,
                TaskSpec(task_type="conversation"),
                ChatRequestSpec(messages=[{"role": "user", "content": text}], tool_execution_id=None),
                request_id=f"req-{uuid4()}",
            )

    return asyncio.run(_go())


def test_metrics_endpoint_requires_auth(app, client: TestClient):
    assert client.get("/v1/optimizer/metrics").status_code == 401


def test_exact_cache_replay_is_user_scoped_and_measured(app, client: TestClient, user_a, user_b):
    _seed(app, user_a, "openai", OPENAI_KEY)
    _seed(app, user_b, "openai", OPENAI_KEY)
    mock = RecordingExecutor()
    app.state.model_router.executor._base.base = mock

    first = _complete(app, user_a, SAFE_TEXT)
    assert first.result.prompt_tokens == 650, "first call must be a real completion"
    assert len(mock.calls) == 1

    second = _complete(app, user_a, SAFE_TEXT)
    assert len(mock.calls) == 1, "the second identical request must replay from cache"
    assert second.result.prompt_tokens == 0, "a replay is free on the input side"
    assert second.result.content == first.result.content

    # a different user with the same question gets their own completion
    third = _complete(app, user_b, SAFE_TEXT)
    assert len(mock.calls) == 2, "caches are scoped by user; no cross-user replay"
    assert third.result.prompt_tokens == 650

    # the mock saw the optimized request: final user text preserved,
    # task-specific output limit applied
    seen = mock.calls[0]
    assert seen.messages[-1]["content"] == SAFE_TEXT
    assert seen.max_tokens == 2048  # conversation task limit

    response = client.get("/v1/optimizer/metrics", headers=headers_for(user_a))
    assert response.status_code == 200
    metrics = response.json()
    assert metrics["requests"] == 3
    assert metrics["exact_hits"] == 1
    assert 0 < metrics["cache_hit_rate"] < 1
    assert metrics["input_tokens_before"] >= metrics["input_tokens_after"] > 0
    assert metrics["cache_tokens_saved"] > 0, "the replay's saving must be measured, not assumed"
    assert metrics["total_tokens_saved"] == metrics["cache_tokens_saved"] + metrics["context_tokens_saved"]


def test_unsafe_requests_are_never_cached(app, client: TestClient, user_a):
    _seed(app, user_a, "openai", OPENAI_KEY)
    mock = RecordingExecutor()
    app.state.model_router.executor._base.base = mock

    _complete(app, user_a, UNSAFE_TEXT)
    _complete(app, user_a, UNSAFE_TEXT)
    assert len(mock.calls) == 2, "a payment request must hit the model both times"

    response = client.get("/v1/optimizer/metrics", headers=headers_for(user_a))
    metrics = response.json()
    assert metrics["exact_hits"] == 0
    assert metrics["cache_tokens_saved"] == 0


def test_quality_failures_are_counted(app, client: TestClient, user_a):
    ledger = app.state.optimizer_ledger
    ledger.record_quality_failure("output refused after optimization", model_id="gpt-6-luna")
    response = client.get("/v1/optimizer/metrics", headers=headers_for(user_a))
    metrics = response.json()
    assert metrics["quality_failures"] == 1
    assert "output refused after optimization" in metrics["recent_quality_failure_reasons"]


def test_optimizer_off_switch_skips_the_layer(app, user_a):
    from mina_api.core.config import Settings
    from mina_api.main import create_app as _build

    settings = Settings(log_json=False, cors_origins=["https://x.example"], optimizer_enabled=False)
    plain = _build(settings)
    from mina_api.optimizer.pipeline import OptimizingExecutor

    assert not isinstance(plain.state.model_router.executor, OptimizingExecutor)
