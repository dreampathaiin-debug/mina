"""Phase 10: observability and usage analytics.

Accounting accuracy (every dashboard figure reconciles to the event
rows), quota enforcement (daily/monthly budgets, token limits, provider
caps, concurrency, alerts), and the privacy rules (no key material, no
raw prompt text, estimates clearly marked).
"""

from __future__ import annotations

import asyncio
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from uuid import uuid4

import pytest
from sqlalchemy import select

from tests.conftest import headers_for, run_db


async def _dashboard(db, user_id=None, budget_service=None):
    from mina_api.observability.metrics import build_dashboard

    async with db.session() as session:
        return await build_dashboard(session, user_id=user_id, budget_service=budget_service)
from mina_api.database.models import Profile
from mina_api.observability import (
    ActiveRequestRegistry,
    BudgetDecision,
    BudgetService,
    LangfuseExporter,
    ObservabilityRecorder,
    QuotaExceededError,
    build_dashboard,
)
from mina_api.observability.executor import ObservabilityExecutor
from mina_api.observability.context import (
    bind_observation_context,
    obs_cache_hit_var,
    obs_retry_count_var,
    reset_observation_context,
)
from mina_api.observability.langfuse import sanitize_metadata
from mina_api.observability.models import UsageEvent
from mina_api.observability.recorder import event_to_payload
from mina_api.routing.fallback_manager import ModelCallError


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


def _make_profile(db, user_id, role="user"):
    async def _go():
        async with db.session() as session:
            existing = await session.get(Profile, user_id)
            if existing is None:
                session.add(
                    Profile(
                        id=user_id,
                        email=f"user-{str(user_id)[:8]}@example.com",
                        role=role,
                    )
                )
                await session.commit()

    run_db(db, _go)


class FakeExporter:
    def __init__(self) -> None:
        self.payloads: list[dict] = []

    async def export(self, events):
        self.payloads.extend(events)
        return True


def _recorder(db, exporter: FakeExporter | None = None) -> ObservabilityRecorder:
    return ObservabilityRecorder(
        exporters=[exporter] if exporter else [],
        session_factory=db.session,
    )


# ------------------------------------------------------------------
# Accounting accuracy
# ------------------------------------------------------------------


def test_record_model_call_captures_full_tracking_set(app, db, user_a):
    _make_profile(db, user_a)
    recorder = _recorder(db)
    conversation_id = uuid4()
    task_id = uuid4()
    tokens = bind_observation_context(
        user_id=str(user_a), conversation_id=str(conversation_id), task_id=str(task_id)
    )
    try:
        event = run_db(
            db,
            lambda: recorder.record_model_call(
                None,
                user_id=str(user_a),
                provider="openai",
                model="gpt-4.1-mini",
                selection_reason="fast_group",
                input_tokens=120,
                output_tokens=80,
                estimated_cost_usd=0.0003,
                billing_source="managed",
                cache_hit="semantic",
                latency_ms=450,
                retry_count=1,
                fallback_count=2,
            ),
        )
    finally:
        reset_observation_context(tokens)

    assert event.kind == "model_call"
    assert event.provider == "openai"
    assert event.model == "gpt-4.1-mini"
    assert event.selection_reason == "fast_group"
    assert event.input_tokens == 120 and event.output_tokens == 80
    assert event.estimated_cost_usd == pytest.approx(0.0003)
    assert event.cost_basis == "estimate"  # estimates are clearly marked
    assert event.billing_source == "managed"
    assert event.cache_hit == "semantic"
    assert event.latency_ms == 450
    assert event.retry_count == 1 and event.fallback_count == 2
    assert event.success is True and event.error_category is None
    assert str(event.conversation_id) == str(conversation_id)
    assert str(event.task_id) == str(task_id)


def test_dashboard_reconciles_to_events(app, db, user_a):
    _make_profile(db, user_a)
    recorder = _recorder(db)

    async def seed():
        await recorder.record_model_call(
            None,
            user_id=str(user_a),
            provider="openai",
            model="gpt-4.1-mini",
            input_tokens=100,
            output_tokens=50,
            estimated_cost_usd=0.10,
            billing_source="managed",
            cache_hit="exact",
            latency_ms=200,
        )
        await recorder.record_model_call(
            None,
            user_id=str(user_a),
            provider="anthropic",
            model="claude-3-5-haiku",
            input_tokens=200,
            output_tokens=100,
            estimated_cost_usd=0.20,
            billing_source="managed",
            cache_hit="none",
            latency_ms=400,
        )
        await recorder.record_model_call(
            None,
            user_id=str(user_a),
            provider="groq",
            model="llama-3.3-70b",
            input_tokens=300,
            output_tokens=150,
            estimated_cost_usd=0.50,
            billing_source="byok",
            latency_ms=600,
        )
        await recorder.record_model_call(
            None,
            user_id=str(user_a),
            provider="openai",
            model="gpt-4.1-mini",
            error_category="rate_limit",
        )

    run_db(db, seed)
    dashboard = run_db(db, lambda: _dashboard(db, user_id=str(user_a)))

    assert dashboard["total_requests"] == 4
    assert dashboard["provider_usage"] == {"openai": 2, "anthropic": 1, "groq": 1}
    assert dashboard["model_usage"] == {"gpt-4.1-mini": 2, "claude-3-5-haiku": 1, "llama-3.3-70b": 1}
    assert dashboard["token_consumption"] == {"input": 600, "output": 300, "total": 900}
    # Managed and BYOK spend reported separately, never merged.
    assert dashboard["estimated_managed_cost_usd"] == pytest.approx(0.30)
    assert dashboard["estimated_byok_cost_usd"] == pytest.approx(0.50)
    assert dashboard["estimated_total_cost_usd"] == pytest.approx(0.80)
    assert dashboard["cost_note"].startswith("All cost figures are estimates")
    # 1 hit, 1 miss tracked => rate 0.5.
    assert dashboard["cache_hit_rate"] == pytest.approx(0.5)
    assert dashboard["provider_failures"] == {"openai": 1}
    assert dashboard["average_latency_ms"] == pytest.approx(400.0)


def test_dashboard_user_scope_isolation(app, db, user_a, user_b):
    _make_profile(db, user_a)
    _make_profile(db, user_b)
    recorder = _recorder(db)

    async def seed():
        await recorder.record_model_call(
            None,
            user_id=str(user_a),
            provider="openai",
            model="gpt-4.1-mini",
            input_tokens=10,
            output_tokens=5,
            estimated_cost_usd=0.01,
            billing_source="managed",
        )
        await recorder.record_model_call(
            None,
            user_id=str(user_b),
            provider="anthropic",
            model="claude-3-5-haiku",
            input_tokens=20,
            output_tokens=10,
            estimated_cost_usd=0.02,
            billing_source="byok",
        )

    run_db(db, seed)

    own = run_db(db, lambda: _dashboard(db, user_id=str(user_a)))
    assert own["total_requests"] == 1
    assert own["active_users"] is None  # not meaningful per user
    assert own["provider_usage"] == {"openai": 1}
    assert own["estimated_managed_cost_usd"] == pytest.approx(0.01)

    global_dash = run_db(db, lambda: _dashboard(db, user_id=None))
    assert global_dash["total_requests"] == 2
    assert global_dash["active_users"] == 2
    assert global_dash["requests_per_user"] == 1.0

    b_dash = run_db(db, lambda: _dashboard(db, user_id=str(user_b)))
    assert b_dash["provider_usage"] == {"anthropic": 1}


def test_task_completion_and_tool_success_rates(app, db, user_a):
    _make_profile(db, user_a)
    recorder = _recorder(db)

    async def seed():
        await recorder.record_tool_execution(
            None, user_id=str(user_a), tool_slug="gmail_send_email", duration_ms=120, success=True
        )
        await recorder.record_tool_execution(
            None, user_id=str(user_a), tool_slug="gmail_send_email", duration_ms=90, success=True
        )
        await recorder.record_tool_execution(
            None, user_id=str(user_a), tool_slug="gcal_create_event", duration_ms=140, success=False,
            error_category="tool_error",
        )
        await recorder.record_tool_execution(
            None, user_id=str(user_a), tool_slug="github_create_issue", duration_ms=110, success=True
        )

    run_db(db, seed)
    dashboard = run_db(db, lambda: _dashboard(db, user_id=str(user_a)))
    assert dashboard["tool_executions"] == {"successes": 3, "failures": 1}
    assert dashboard["tool_success_rate"] == pytest.approx(0.75)


# ------------------------------------------------------------------
# Budget enforcement
# ------------------------------------------------------------------


def _service(**kwargs) -> BudgetService:
    return BudgetService(**kwargs)


def _record_managed_spend(db, recorder, user_id, cost, provider="openai"):
    return run_db(
        db,
        lambda: recorder.record_model_call(
            None,
            user_id=str(user_id),
            provider=provider,
            model="m",
            estimated_cost_usd=cost,
            billing_source="managed",
        ),
    )


def test_daily_budget_hard_stop_and_alert(app, db, user_a):
    _make_profile(db, user_a)
    service = _service(default_daily_budget_usd=1.0)
    recorder = _recorder(db)

    first = run_db(db, lambda: _check(service, db, str(user_a)))
    assert first.allowed is True

    _record_managed_spend(db, recorder, user_a, 0.90)  # 90% of $1.00

    mid = run_db(db, lambda: _check(service, db, str(user_a)))
    assert mid.allowed is True
    # Pre-exhaustion alerts fired at 80% and 95%? 90% crosses 80 only.
    assert [alert["scope"] for alert in mid.alerts] == ["daily_budget"]
    assert mid.alerts[0]["threshold_pct"] == 80

    # Same day, same threshold: deduped, no second alert row.
    again = run_db(db, lambda: _check(service, db, str(user_a)))
    assert [alert["scope"] for alert in again.alerts] == ["daily_budget"]

    _record_managed_spend(db, recorder, user_a, 0.20)  # $1.10 total >= $1.00
    blocked = run_db(db, lambda: _check(service, db, str(user_a)))
    assert blocked.allowed is False
    assert blocked.reason_code == "daily_budget_exhausted"


async def _check(service, db, user_id, provider=None):
    async with db.session() as session:
        return await service.check_request(session, user_id, provider=provider)


def test_byok_spend_is_never_budgeted(app, db, user_a):
    _make_profile(db, user_a)
    service = _service(default_daily_budget_usd=1.0)
    recorder = _recorder(db)

    async def seed():
        for _ in range(5):
            await recorder.record_model_call(
                None,
                user_id=str(user_a),
                provider="groq",
                model="llama-3.3-70b",
                estimated_cost_usd=2.0,
                billing_source="byok",
            )

    run_db(db, seed)
    decision = run_db(db, lambda: _check(service, db, str(user_a)))
    assert decision.allowed is True


def test_monthly_budget_hard_stop(app, db, user_a):
    _make_profile(db, user_a)
    service = _service(default_monthly_budget_usd=1.0)
    recorder = _recorder(db)
    _record_managed_spend(db, recorder, user_a, 0.60)
    _record_managed_spend(db, recorder, user_a, 0.60)
    decision = run_db(db, lambda: _check(service, db, str(user_a)))
    assert decision.allowed is False
    assert decision.reason_code == "monthly_budget_exhausted"


def test_token_limits_enforced(app, db, user_a):
    _make_profile(db, user_a)
    service = _service(default_daily_token_limit=1000, default_monthly_token_limit=10**9)
    recorder = _recorder(db)

    async def seed():
        await recorder.record_model_call(
            None,
            user_id=str(user_a),
            provider="openai",
            model="m",
            input_tokens=700,
            output_tokens=400,  # 1100 today, on BYOK: still counts
            billing_source="byok",
        )

    run_db(db, seed)
    decision = run_db(db, lambda: _check(service, db, str(user_a)))
    assert decision.allowed is False
    assert decision.reason_code == "daily_tokens_exhausted"


def test_monthly_token_limit(app, db, user_a):
    _make_profile(db, user_a)
    service = _service(default_daily_token_limit=None, default_monthly_token_limit=100)
    recorder = _recorder(db)

    async def seed():
        await recorder.record_model_call(
            None,
            user_id=str(user_a),
            provider="openai",
            model="m",
            input_tokens=150,
            output_tokens=0,
            billing_source="managed",
        )

    run_db(db, seed)
    decision = run_db(db, lambda: _check(service, db, str(user_a)))
    assert decision.allowed is False
    assert decision.reason_code == "monthly_tokens_exhausted"


def test_provider_budget_blocks_only_that_provider(app, db, user_a):
    _make_profile(db, user_a)
    service = _service(provider_daily_budgets={"openai": 1.0})
    recorder = _recorder(db)
    _record_managed_spend(db, recorder, user_a, 1.20, provider="openai")

    openai_decision = run_db(db, lambda: _check(service, db, str(user_a), provider="openai"))
    assert openai_decision.allowed is False
    assert openai_decision.reason_code == "provider:openai_exhausted"

    anthropic_decision = run_db(db, lambda: _check(service, db, str(user_a), provider="anthropic"))
    assert anthropic_decision.allowed is True


def test_concurrent_request_registry():
    registry = ActiveRequestRegistry()
    assert registry.acquire("u", limit=2) is True
    assert registry.acquire("u", limit=2) is True
    assert registry.acquire("u", limit=2) is False
    registry.release("u")
    assert registry.acquire("u", limit=2) is True
    registry.release("u")
    registry.release("u")
    assert registry.in_flight("u") == 0


# ------------------------------------------------------------------
# Executor wrapper
# ------------------------------------------------------------------


class FakeBaseExecutor:
    def __init__(self, result=None, error: ModelCallError | None = None) -> None:
        self.result = result
        self.error = error
        self.calls = 0

    async def complete(self, candidate, request):
        self.calls += 1
        if self.error is not None:
            raise self.error
        return self.result


def _candidate(source="managed", provider="openai", model_id="gpt-4.1-mini", reason="fast_group"):
    spec = SimpleNamespace(provider=provider, model_id=model_id, input_price=0.35, output_price=1.5)
    credential = SimpleNamespace(credential_id=uuid4(), provider=provider, source=source, api_key="sk-DO-NOT-LOG")
    return SimpleNamespace(spec=spec, credential=credential, model_id=model_id, reason=reason)


def _request(user_id, **meta):
    return SimpleNamespace(metadata={"user_id": user_id, **meta})


@pytest.fixture()
def completion_result():
    from mina_api.routing.fallback_manager import CompletionResult

    return CompletionResult(
        content="ok",
        tool_calls=None,
        model_id="gpt-4.1-mini",
        prompt_tokens=100,
        completion_tokens=50,
        latency_ms=120,
        finish_reason="stop",
    )


def test_executor_records_success(app, db, user_a, completion_result):
    _make_profile(db, user_a)
    recorder = _recorder(db)
    base = FakeBaseExecutor(result=completion_result)
    checks: list[str] = []

    async def budget_check(user_id, provider):
        checks.append(provider or "")
        return BudgetDecision(concurrent_limit=None)

    executor = ObservabilityExecutor(
        base,
        recorder=recorder,
        budget_check=budget_check,
        active_requests=ActiveRequestRegistry(),
        cost_estimator=lambda spec, pt, ct: round(pt / 1_000_000 * 0.35 + ct / 1_000_000 * 1.5, 6),
    )
    result = run_db(db, lambda: executor.complete(_candidate(), _request(str(user_a))))
    assert result.completion_tokens == 50
    assert base.calls == 1
    assert checks == ["openai"]

    async def read_event():
        async with db.session() as session:
            rows = (await session.execute(select(UsageEvent))).scalars().all()
            return rows

    events = run_db(db, read_event)
    assert len(events) == 1
    event = events[0]
    assert event.kind == "model_call"
    assert event.provider == "openai"
    assert event.model == "gpt-4.1-mini"
    assert event.selection_reason == "fast_group"
    assert event.input_tokens == 100 and event.output_tokens == 50
    assert event.estimated_cost_usd == pytest.approx(0.0001, abs=1e-4)
    assert event.billing_source == "managed"
    assert event.latency_ms is not None and event.latency_ms >= 0
    assert event.error_category is None


def test_executor_hard_stop_prevents_model_call(app, db, user_a):
    _make_profile(db, user_a)
    recorder = _recorder(db)
    base = FakeBaseExecutor(result=completion_result)

    async def budget_check(user_id, provider):
        raise QuotaExceededError("daily_budget", "daily managed budget exhausted")

    executor = ObservabilityExecutor(
        base, recorder=recorder, budget_check=budget_check, active_requests=ActiveRequestRegistry()
    )
    with pytest.raises(QuotaExceededError):
        run_db(db, lambda: executor.complete(_candidate(), _request(str(user_a))))
    assert base.calls == 0  # the model is never called past the hard stop


def test_executor_error_event(app, db, user_a):
    _make_profile(db, user_a)
    recorder = _recorder(db)
    base = FakeBaseExecutor(error=ModelCallError("rate_limit", status_code=429))
    executor = ObservabilityExecutor(
        base, recorder=recorder, budget_check=None, active_requests=ActiveRequestRegistry()
    )
    with pytest.raises(ModelCallError):
        run_db(db, lambda: executor.complete(_candidate(), _request(str(user_a))))

    async def read_events():
        async with db.session() as session:
            return list((await session.execute(select(UsageEvent))).scalars().all())

    events = run_db(db, read_events)
    assert len(events) == 1
    assert events[0].error_category == "rate_limit"
    assert events[0].success is False
    assert events[0].input_tokens == 0


def test_executor_byok_billing_source_and_concurrency(app, db, user_a, completion_result):
    _make_profile(db, user_a)
    recorder = _recorder(db)
    registry = ActiveRequestRegistry()
    base = FakeBaseExecutor(result=completion_result)

    async def budget_check(user_id, provider):
        return BudgetDecision(concurrent_limit=5)

    executor = ObservabilityExecutor(
        base, recorder=recorder, budget_check=budget_check, active_requests=registry,
        cost_estimator=lambda spec, pt, ct: 0.001,
    )
    run_db(db, lambda: executor.complete(_candidate(source="byok"), _request(str(user_a))))
    assert registry.in_flight(str(user_a)) == 0  # slot released after the call

    async def read_events():
        async with db.session() as session:
            return list((await session.execute(select(UsageEvent))).scalars().all())

    events = run_db(db, read_events)
    assert events[0].billing_source == "byok"


# ------------------------------------------------------------------
# Privacy: never keys, never raw prompts, estimates marked
# ------------------------------------------------------------------


def test_no_key_or_prompt_material_in_payloads(app, db, user_a):
    _make_profile(db, user_a)
    exporter = FakeExporter()
    recorder = _recorder(db, exporter)

    run_db(
        db,
        lambda: recorder.record_model_call(
            None,
            user_id=str(user_a),
            provider="openai",
            model="gpt-4.1-mini",
            estimated_cost_usd=0.01,
            billing_source="managed",
        ),
    )
    assert len(exporter.payloads) == 1
    payload = exporter.payloads[0]
    flat = repr(payload).lower()
    assert "sk-do-not-log" not in flat
    assert "authorization" not in flat
    assert "api_key" not in flat
    # No prompt/completion content fields at all.
    assert "content" not in flat and "messages" not in flat
    assert payload["cost_basis"] == "estimate"
    assert payload["billing_source"] == "managed"


def test_sanitize_strips_forbidden_keys():
    dirty = {
        "model": "gpt-4.1-mini",
        "api_key": "sk-secret",
        "Authorization": "Bearer x",
        "prompt": "user text",
        "nested": {"token": "abc", "provider": "openai"},
        "list": [{"secret_key": 1, "ok": 2}],
    }
    clean = sanitize_metadata(dirty)
    assert clean == {"model": "gpt-4.1-mini", "nested": {"provider": "openai"}, "list": [{"ok": 2}]}


def test_langfuse_exporter_disabled_without_config():
    settings = SimpleNamespace(langfuse_host=None, langfuse_public_key=None, langfuse_secret_key=None)
    assert LangfuseExporter.from_settings(settings) is None
    configured = SimpleNamespace(
        langfuse_host="https://langfuse.example", langfuse_public_key="pk", langfuse_secret_key="sk"
    )
    exporter = LangfuseExporter.from_settings(configured)
    assert exporter is not None
    # Never sends the secret key anywhere except the auth header.
    assert exporter._auth == ("pk", "sk")


# ------------------------------------------------------------------
# API surface
# ------------------------------------------------------------------


def test_api_dashboard_and_events_user_scope(app, client, db, user_a, user_b):
    _make_profile(db, user_a)
    _make_profile(db, user_b)
    recorder = _recorder(db)
    run_db(
        db,
        lambda: recorder.record_model_call(
            None,
            user_id=str(user_a),
            provider="openai",
            model="gpt-4.1-mini",
            estimated_cost_usd=0.01,
            billing_source="managed",
        ),
    )
    run_db(
        db,
        lambda: recorder.record_model_call(
            None,
            user_id=str(user_b),
            provider="anthropic",
            model="claude-3-5-haiku",
            estimated_cost_usd=0.02,
            billing_source="byok",
        ),
    )

    response = client.get("/v1/observability/dashboard", headers=headers_for(user_a))
    assert response.status_code == 200
    body = response.json()
    assert body["total_requests"] == 1
    assert body["provider_usage"] == {"openai": 1}
    assert "budget_snapshot" in body

    events = client.get("/v1/observability/events", headers=headers_for(user_a))
    assert events.status_code == 200
    rows = events.json()
    assert len(rows) == 1 and rows[0]["provider"] == "openai"

    # Another user never sees user_a's data.
    other = client.get("/v1/observability/events", headers=headers_for(user_b))
    rows_b = other.json()
    assert len(rows_b) == 1 and rows_b[0]["provider"] == "anthropic"


def test_api_admin_sees_all(app, client, db, user_a, user_b):
    _make_profile(db, user_a)
    _make_profile(db, user_b, role="admin")
    recorder = _recorder(db)
    run_db(
        db,
        lambda: recorder.record_model_call(
            None, user_id=str(user_a), provider="openai", model="m", estimated_cost_usd=0.01, billing_source="managed"
        ),
    )
    run_db(
        db,
        lambda: recorder.record_model_call(
            None, user_id=str(user_b), provider="openai", model="m", estimated_cost_usd=0.02, billing_source="managed"
        ),
    )
    response = client.get("/v1/observability/dashboard?user_id=all", headers=headers_for(user_b))
    assert response.status_code == 200
    assert response.json()["total_requests"] == 2
    assert response.json()["active_users"] == 2

    # Non-admin cannot widen scope.
    forbidden = client.get("/v1/observability/dashboard?user_id=all", headers=headers_for(user_a))
    assert forbidden.status_code == 200
    assert forbidden.json()["total_requests"] == 1  # still scoped to self


def test_api_budgets_roundtrip(app, client, db, user_a):
    _make_profile(db, user_a)

    initial = client.get("/v1/budgets", headers=headers_for(user_a))
    assert initial.status_code == 200
    assert initial.json()["daily_budget_usd"] is None

    updated = client.put(
        "/v1/budgets",
        headers=headers_for(user_a),
        json={"daily_budget_usd": 5.0, "daily_token_limit": 100000, "concurrent_request_limit": 3},
    )
    assert updated.status_code == 200
    body = updated.json()
    assert body["daily_budget_usd"] == 5.0
    assert body["daily_token_limit"] == 100000
    assert body["concurrent_request_limit"] == 3

    check = client.get("/v1/budgets", headers=headers_for(user_a))
    assert check.json()["daily_budget_usd"] == 5.0

    invalid = client.put(
        "/v1/budgets", headers=headers_for(user_a), json={"daily_budget_usd": -1}
    )
    assert invalid.status_code == 422


def test_api_alerts_endpoint(app, client, db, user_a):
    _make_profile(db, user_a)
    service = BudgetService(default_daily_budget_usd=1.0)
    recorder = _recorder(db)
    _record_managed_spend(db, recorder, user_a, 0.90)
    run_db(db, lambda: _check(service, db, str(user_a)))

    response = client.get("/v1/observability/alerts", headers=headers_for(user_a))
    assert response.status_code == 200
    alerts = response.json()
    assert len(alerts) == 1
    assert alerts[0]["scope"] == "daily_budget"
    assert alerts[0]["threshold_pct"] == 80
