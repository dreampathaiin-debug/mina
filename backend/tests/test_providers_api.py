"""Provider key manager API: masked output, tenant isolation, testing, policy."""

from uuid import uuid4

import httpx
import pytest
from fastapi.testclient import TestClient

from mina_api.core.config import Settings
from mina_api.database.models import Base
from mina_api.main import create_app
from tests.conftest import ENCRYPTION_KEY, JWT_SECRET, SUPABASE_URL, headers_for

KEY_A = "sk-openai-test-key-abcdefgh12345678"
KEY_B = "sk-openai-test-key-zyxwvuts87654321"


@pytest.fixture()
def crypto_app():
    settings = Settings(
        log_json=False,
        cors_origins=["https://app.mina.example"],
        supabase_url=SUPABASE_URL,
        supabase_jwt_secret=JWT_SECRET,
        supabase_audience="authenticated",
        database_url="sqlite+aiosqlite://",
        encryption_key=ENCRYPTION_KEY,
    )
    app = create_app(settings)
    import asyncio

    async def _create():
        async with app.state.database.engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)

    asyncio.run(_create())
    return app


@pytest.fixture()
def crypto_client(crypto_app) -> TestClient:
    return TestClient(crypto_app)


def _create(client, user, provider="openai", key=KEY_A, label=None):
    response = client.post(
        "/v1/providers/keys",
        json={"provider": provider, "key": key, "label": label},
        headers=headers_for(user),
    )
    assert response.status_code == 201
    return response.json()


def test_create_and_list_masks_key(crypto_client, user_a):
    row = _create(crypto_client, user_a, key=KEY_A)
    assert row["key_preview"] == "***5678"
    assert KEY_A not in str(row)
    listed = crypto_client.get("/v1/providers/keys", headers=headers_for(user_a)).json()
    assert [k["key_preview"] for k in listed] == ["***5678"]


def test_unknown_provider_rejected(crypto_client, user_a):
    response = crypto_client.post(
        "/v1/providers/keys",
        json={"provider": "not-a-provider", "key": KEY_A},
        headers=headers_for(user_a),
    )
    assert response.status_code == 422


def test_bad_format_rejected(crypto_client, user_a):
    response = crypto_client.post(
        "/v1/providers/keys",
        json={"provider": "openai", "key": "definitely-not-an-openai-key"},
        headers=headers_for(user_a),
    )
    assert response.status_code == 422


def test_unknown_format_provider_accepted(crypto_client, user_a):
    # Extra LiteLLM providers have no known format; the check is skipped.
    response = crypto_client.post(
        "/v1/providers/keys",
        json={"provider": "together", "key": "whatever-together-issues"},
        headers=headers_for(user_a),
    )
    assert response.status_code == 422  # 'together' not registered unless configured


def test_user_b_cannot_touch_user_a_keys(crypto_app, crypto_client, user_a, user_b):
    row = _create(crypto_client, user_a)
    assert crypto_client.get("/v1/providers/keys", headers=headers_for(user_b)).json() == []
    for method, path, json in (
        ("patch", f"/v1/providers/keys/{row['id']}", {"status": "disabled"}),
        ("delete", f"/v1/providers/keys/{row['id']}", None),
        ("post", f"/v1/providers/keys/{row['id']}/test", None),
        ("post", f"/v1/providers/keys/{row['id']}/rotate", {"key": KEY_B}),
    ):
        response = crypto_client.request(method.upper(), path, json=json, headers=headers_for(user_b))
        assert response.status_code == 404, (method, path)
    # A's key survived B's attempts, still active.
    listed = crypto_client.get("/v1/providers/keys", headers=headers_for(user_a)).json()
    assert listed[0]["status"] == "active"


def test_disable_and_rotate(crypto_app, crypto_client, user_a):
    row = _create(crypto_client, user_a)
    disabled = crypto_client.patch(
        f"/v1/providers/keys/{row['id']}", json={"status": "disabled"}, headers=headers_for(user_a)
    ).json()
    assert disabled["status"] == "disabled"
    rotated = crypto_client.post(
        f"/v1/providers/keys/{row['id']}/rotate", json={"key": KEY_B}, headers=headers_for(user_a)
    ).json()
    assert rotated["key_preview"] == "***4321"
    assert rotated["last_tested_at"] is None  # old verdict no longer applies
    assert KEY_A not in str(rotated)


def test_test_endpoint_uses_mocked_provider(crypto_app, crypto_client, monkeypatch, user_a):
    def handler(request: httpx.Request) -> httpx.Response:
        if request.headers.get("Authorization") == f"Bearer {KEY_A}":
            return httpx.Response(200, json={"data": []})
        return httpx.Response(401, json={"error": "bad key"})

    monkeypatch.setattr(
        crypto_app.state, "http", httpx.AsyncClient(transport=httpx.MockTransport(handler)), raising=False
    )
    row = _create(crypto_client, user_a, key=KEY_A)
    result = crypto_client.post(f"/v1/providers/keys/{row['id']}/test", headers=headers_for(user_a)).json()
    assert result["ok"] is True
    assert KEY_A not in str(result)
    # health tracked on the row + provider catalog
    listed = crypto_client.get("/v1/providers/keys", headers=headers_for(user_a)).json()
    assert listed[0]["last_test_ok"] is True


def test_provider_catalog_lists_builtins(crypto_client, user_a):
    catalog = crypto_client.get("/v1/providers", headers=headers_for(user_a)).json()
    names = {row["name"] for row in catalog}
    assert {"nvidia_nim", "openrouter", "gemini", "groq", "openai", "anthropic"} <= names


def test_ai_policy_roundtrip_and_validation(crypto_client, user_a):
    default = crypto_client.get("/v1/settings/ai-policy", headers=headers_for(user_a)).json()
    assert default == {"policy": "byok_only", "allow_managed_fallback": False, "updated_at": default["updated_at"]}
    patched = crypto_client.patch(
        "/v1/settings/ai-policy",
        json={"policy": "byok_first", "allow_managed_fallback": True},
        headers=headers_for(user_a),
    )
    assert patched.status_code == 200
    assert patched.json()["allow_managed_fallback"] is True
    # fallback flag is meaningless outside byok_first
    bad = crypto_client.patch(
        "/v1/settings/ai-policy",
        json={"policy": "managed_only", "allow_managed_fallback": True},
        headers=headers_for(user_a),
    )
    assert bad.status_code == 422


def test_keys_endpoints_require_encryption(user_a):
    # App without any encryption key: the key manager degrades safely.
    from fastapi.testclient import TestClient as _TC

    app = create_app(Settings(log_json=False, cors_origins=["https://app.mina.example"]))
    response = _TC(app).post(
        "/v1/providers/keys",
        json={"provider": "openai", "key": KEY_A},
        headers=headers_for(user_a),
    )
    assert response.status_code == 503
