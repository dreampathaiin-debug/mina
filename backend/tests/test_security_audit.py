"""Regression tests from the Nov 2026 independent security audit.

Covers the audit's attack scenarios end to end: stolen or forged JWTs,
log-injection via hostile request ids, request flooding, and replay of an
approved dangerous Composio action. Tenant-isolation, key-encryption,
prompt-hygiene and cross-user cases are covered by the phase suites and
are cited in the audit report rather than duplicated here.
"""

import asyncio
import time as _time
from uuid import UUID, uuid4

import jwt as pyjwt
import pytest
from fastapi.testclient import TestClient

from mina_api.database.models import Approval
from mina_api.integrations.composio import ComposioService, arguments_fingerprint
from tests.conftest import JWT_SECRET, SUPABASE_URL, headers_for, run_db, token_for
from tests.test_composio_integration import (
    FakeComposio,
    _build_app,
    seed_account,
    seed_catalog,
)


def _forged(sub: UUID | None, **overrides) -> str:
    now = int(_time.time())
    payload = {
        "aud": "authenticated",
        "iss": f"{SUPABASE_URL}/auth/v1",
        "iat": now - 5,
        "exp": now + 3600,
    }
    if sub is not None:
        payload["sub"] = str(sub)
    payload.update(overrides)
    key = payload.pop("_key", JWT_SECRET)
    return pyjwt.encode(payload, key, algorithm="HS256")


@pytest.mark.parametrize(
    "make_headers",
    [
        pytest.param(lambda u: {"Authorization": f"Bearer {token_for(u, expired=True)}"}, id="expired"),
        pytest.param(lambda u: {"Authorization": f"Bearer {_forged(u, _key='attacker-key')}"}, id="forged-signature"),
        pytest.param(lambda u: {"Authorization": f"Bearer {token_for(u, audience='everyone')}"}, id="wrong-audience"),
        pytest.param(lambda u: {"Authorization": f"Bearer {token_for(u, issuer_url='https://evil.example.com')}"}, id="wrong-issuer"),
        pytest.param(lambda u: {"Authorization": f"Bearer {_forged(None)}"}, id="missing-sub"),
    ],
)
def test_bad_tokens_are_rejected(app, client, make_headers):
    user = uuid4()
    response = client.get("/v1/me", headers=make_headers(user))
    assert response.status_code == 401


def test_hostile_request_id_is_sanitized(client):
    evil = "id../../x"
    response = client.get("/v1/me", headers={"X-Request-ID": evil})
    assert response.headers.get("x-request-id") not in (None, evil)


def test_rate_limit_blocks_flood_and_spares_health(app, client, user_a):
    app.state.settings.rate_limit_requests_per_minute = 3
    try:
        statuses = [client.get("/v1/me", headers=headers_for(user_a)).status_code for _ in range(4)]
        assert statuses[:3] == [200, 200, 200]
        assert statuses[3] == 429
        assert client.get("/v1/me", headers=headers_for(user_a)).status_code == 429
        assert client.get("/v1/health").status_code == 200
    finally:
        app.state.settings.rate_limit_requests_per_minute = 120


@pytest.fixture()
def sec_app():
    """App with a Composio key and a fake SDK installed."""
    application = _build_app("ac_test_key_000000")
    fake = FakeComposio()
    seed_catalog(fake)
    service = ComposioService(application.state.settings, client_factory=lambda key, timeout: fake)
    application.state.composio = service
    application.state.composio_fake = fake
    yield application
    asyncio.run(application.state.database.engine.dispose())


@pytest.fixture()
def sec_client(sec_app):
    return TestClient(sec_app)


def test_approved_dangerous_action_cannot_be_replayed(sec_client, user_a):
    app = sec_client.app
    seed_account(app, user_a, "gmail")
    payload = {
        "tool_slug": "gmail_send_email",
        "arguments": {"to": "friend@example.com", "body": "hello"},
    }
    fingerprint = arguments_fingerprint(payload["arguments"])

    async def _approve():
        async with app.state.database.session() as session:
            session.add(
                Approval(
                    user_id=user_a,
                    status="approved",
                    request={
                        "kind": "composio_tool",
                        "tool_slug": "gmail_send_email",
                        "args_fingerprint": fingerprint,
                    },
                )
            )
            await session.commit()

    run_db(app.state.database, _approve)

    first = sec_client.post("/v1/tools/execute", json=payload, headers=headers_for(user_a))
    assert first.status_code == 201, first.text
    # The same fingerprint riding the same approval must never send again.
    second = sec_client.post("/v1/tools/execute", json=payload, headers=headers_for(user_a))
    assert second.status_code == 403
    assert second.json()["detail"]["error"] == "approval_required"
    assert len(app.state.composio_fake.tools.executed) == 1
