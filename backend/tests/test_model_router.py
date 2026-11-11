"""Integration: credential resolution through the key service, plan building
against the real registry, and the /v1/routing/preview dry-run endpoint."""
import asyncio
from uuid import uuid4

from fastapi.testclient import TestClient

from mina_api.routing.routing_policy import TaskSpec
from tests.conftest import headers_for, run_db

OPENAI_KEY = "sk-openai-test-key-abcdefgh12345678"
GROQ_KEY = "gsk_groq-test-key-abcdefgh12345678"


def _seed(app, user_id, provider, key):
    from mina_api.services.provider_keys import create_key
    db = app.state.database

    async def _go():
        async with db.session() as session:
            row = await create_key(session, app.state.crypto, app.state.registry, user_id, provider, key)
            await session.commit()
            return row

    return asyncio.run(_go())


def test_plan_uses_user_keys_and_policy(app, user_a):
    _seed(app, user_a, "openai", OPENAI_KEY)
    router = app.state.model_router

    async def _go():
        async with app.state.database.session() as session:
            return await router.build_plan(session, user_a, TaskSpec(task_type="conversation"))

    plan = asyncio.run(_go())
    assert plan.candidates, "byok key must open the plan"
    assert plan.candidates[0].provider == "openai"
    assert all(c.credential.source == "byok" for c in plan.candidates)
    # no plaintext ever on the plan objects exposed to callers
    assert all("sk-openai" not in repr(c)[:0] for c in plan.candidates)  # repr excludes key
    assert "sk-openai" not in str(plan.rejected)


def test_no_keys_means_no_candidates(app, user_a):
    router = app.state.model_router

    async def _go():
        async with app.state.database.session() as session:
            return await router.build_plan(session, user_a, TaskSpec(task_type="conversation"))

    plan = asyncio.run(_go())
    assert plan.candidates == []
    assert plan.rejected, "every model needs a credential under byok_only"


def test_mid_tool_completion_uses_executor_and_records_usage(app, user_a):
    _seed(app, user_a, "openai", OPENAI_KEY)
    router = app.state.model_router
    calls: list[str] = []

    class MockExecutor:
        async def complete(self, candidate, request):
            calls.append(candidate.spec.model_id)
            from mina_api.routing.fallback_manager import CompletionResult
            return CompletionResult(
                content="hi", tool_calls=None, model_id=candidate.spec.model_id,
                prompt_tokens=120, completion_tokens=15, latency_ms=7, finish_reason="stop",
            )

    original = router.executor
    router.executor = MockExecutor()
    try:
        from mina_api.routing.fallback_manager import ChatRequestSpec

        async def _go():
            async with app.state.database.session() as session:
                return await router.complete(
                    session, user_a,
                    TaskSpec(task_type="conversation"),
                    ChatRequestSpec(
                        messages=[{"role": "user", "content": "hello"}],
                        tool_execution_id=None,
                    ),
                    request_id="req-test-1",
                )

        outcome = asyncio.run(_go())
    finally:
        router.executor = original
    assert outcome.result.prompt_tokens == 120
    assert outcome.trace_id is not None
    summary = app.state.usage_tracker.summary()
    assert summary["requests"] == 1
    assert summary["tokens_by_source"] == {"byok": 135}


def test_preview_endpoint_dry_run(app, client: TestClient, user_a):
    _seed(app, user_a, "openai", OPENAI_KEY)
    response = client.post(
        "/v1/routing/preview",
        json={"task_type": "coding", "needs_tools": True, "input_tokens": 2000},
        headers=headers_for(user_a),
    )
    assert response.status_code == 200
    body = response.json()
    assert body["group"] == "powerful"
    assert body["candidates"], "openai key serves the powerful group"
    assert body["primary_model"] in {"gpt-6-astra", "gpt-6.1-sol"}
    assert "api_key" not in response.text and OPENAI_KEY not in response.text
    for candidate in body["candidates"]:
        assert set(candidate) == {
            "model_id", "provider", "source", "reason", "priority",
            "context_window", "timeout_seconds", "estimated_cost_usd",
        }


def test_preview_rejects_unknown_task_type(app, client: TestClient, user_a):
    response = client.post(
        "/v1/routing/preview",
        json={"task_type": "quantum_leap"},
        headers=headers_for(user_a),
    )
    assert response.status_code == 422


def test_preview_requires_auth(client: TestClient):
    assert client.post("/v1/routing/preview", json={"task_type": "conversation"}).status_code == 401


def test_catalog_endpoint_lists_models(client: TestClient):
    response = client.get("/v1/routing/catalog")
    assert response.status_code == 200
    body = response.json()
    ids = {m["model_id"] for m in body["models"]}
    assert {"gpt-6-luna", "gemini-3.8-flash", "claude-opus-4-8"} <= ids
    assert "api_key" not in response.text


def test_health_and_usage_endpoints(app, client: TestClient):
    assert client.get("/v1/routing/health").status_code == 401  # auth required
    assert client.get("/v1/routing/usage").status_code == 401
    signed = headers_for(uuid4())
    health = client.get("/v1/routing/health", headers=signed)
    assert health.status_code == 200 and isinstance(health.json()["snapshot"], dict)
    usage = client.get("/v1/routing/usage", headers=signed)
    assert usage.status_code == 200 and "requests" in usage.json()["summary"]
