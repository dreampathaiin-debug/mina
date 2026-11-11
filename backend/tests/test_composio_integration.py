"""Phase 6 tests: production-grade Composio integration.

Covers the security spec with a fake Composio SDK: no key exposure,
per-user isolation (cross-user access impossible), schema validation,
approval policy, duplicate-execution prevention, and stable error
mapping for auth failures, expired connections and rate limits.
"""

from __future__ import annotations

import asyncio
import threading
import time
import types
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select

from composio import exceptions as composio_errors
from composio_client._exceptions import (
    AuthenticationError,
    RateLimitError,
)
from mina_api.core.config import Settings
from mina_api.database.models import Approval, Base, ComposioConnection, ComposioExecution
from mina_api.integrations.composio import (
    ComposioService,
    arguments_fingerprint,
    is_dangerous_tool,
    user_namespace,
)
from mina_api.main import create_app
from tests.conftest import ENCRYPTION_KEY, JWT_SECRET, SUPABASE_URL, headers_for, run_db
import jwt as pyjwt
import time as _time


def _token_for(user_id, **kwargs):
    now = int(_time.time())
    payload = {
        "sub": str(user_id),
        "aud": "authenticated",
        "iss": f"{SUPABASE_URL}/auth/v1",
        "iat": now - 5,
        "exp": now + 3600,
        "email": f"user-{str(user_id)[:8]}@example.com",
        "role": "authenticated",
    }
    return pyjwt.encode(payload, JWT_SECRET, algorithm="HS256")


def headers_for(user_id):
    return {"Authorization": f"Bearer {_token_for(user_id)}"}


# --------------------------------------------------------------------------
# Fake Composio SDK
# --------------------------------------------------------------------------

class FakeConnectionRequest:
    def __init__(self, request_id, status="INITIATED", redirect_url=None):
        self.id = request_id
        self.status = status
        self.redirect_url = redirect_url or "https://auth.composio.dev/fake-connect"


class FakeListPage:
    def __init__(self, items):
        self.items = items
        self.next_cursor = None


class FakeAccount:
    def __init__(self, account_id, user_ns, status, toolkit_slug):
        self.id = account_id
        self.user_id = user_ns
        self.status = status
        self.toolkit = types.SimpleNamespace(slug=toolkit_slug)
        self.created_at = "2026-11-11T12:00:00Z"


class FakeAuthConfig:
    def __init__(self, config_id, toolkit_slug, is_disabled=False):
        self.id = config_id
        self.toolkit = types.SimpleNamespace(slug=toolkit_slug)
        self.is_disabled = is_disabled


class FakeAuthConfigs:
    def __init__(self):
        self.items = []
        self.created_with = []

    def list(self, **kwargs):
        slugs = kwargs.get("toolkits")
        found = [c for c in self.items if not slugs or c.toolkit.slug in slugs]
        return FakeListPage(found)

    def create(self, toolkit, options):
        self.created_with.append((toolkit, dict(options)))
        config = FakeAuthConfig(f"ac_cfg_{len(self.items) + 1}", toolkit)
        self.items.append(config)
        return config


class FakeConnectedAccounts:
    def __init__(self):
        self.accounts: list[FakeAccount] = []
        self.deleted: list[tuple[str, object]] = []
        self.initiated: list[dict] = []

    def initiate(self, user_ns, auth_config_id, callback_url=None, allow_multiple=False, **kwargs):
        self.initiated.append(
            {"user_ns": user_ns, "auth_config_id": auth_config_id, "callback_url": callback_url}
        )
        request_id = f"ca_{len(self.initiated):03d}"
        return FakeConnectionRequest(request_id)

    def list(self, **kwargs):
        user_ids = kwargs.get("user_ids")
        toolkit_slugs = kwargs.get("toolkit_slugs")
        statuses = kwargs.get("statuses")
        found = list(self.accounts)
        if user_ids:
            found = [a for a in found if a.user_id in user_ids]
        if toolkit_slugs:
            found = [a for a in found if a.toolkit.slug in toolkit_slugs]
        if statuses:
            found = [a for a in found if a.status in statuses]
        return FakeListPage(found)

    def get(self, nanoid):
        for account in self.accounts:
            if account.id == nanoid:
                return account
        raise composio_errors.ConnectedAccountNotFoundError(nanoid)

    def delete(self, nanoid, revoke_on_delete=None):
        self.accounts = [a for a in self.accounts if a.id != nanoid]
        self.deleted.append((nanoid, revoke_on_delete))
        return types.SimpleNamespace(id=nanoid, deleted=True)


class FakeTool:
    def __init__(self, slug, name, description, toolkit_slug, tags, schema):
        self.slug = slug
        self.name = name
        self.description = description
        self.toolkit = toolkit_slug
        self.tags = tags
        self.input_parameters = schema
        self.output_parameters = {"type": "object"}
        self.available_versions = ["latest"]
        self.version = "latest"


class FakeTools:
    def __init__(self):
        self.tools: list[FakeTool] = []
        self.executed: list[dict] = []
        self.sleep_seconds = 0.0

    def get_raw_composio_tools(self, **kwargs):
        found = list(self.tools)
        if kwargs.get("search"):
            needle = kwargs["search"].lower()
            found = [
                t for t in found
                if needle in t.slug.lower() or needle in (t.description or "").lower()
            ]
        if kwargs.get("toolkits"):
            found = [t for t in found if t.toolkit in kwargs["toolkits"]]
        if kwargs.get("tools"):
            found = [t for t in found if t.slug in kwargs["tools"]]
        limit = kwargs.get("limit")
        return found[:limit] if limit else found

    def execute(self, slug, arguments, connected_account_id=None, **kwargs):
        self.executed.append(
            {"slug": slug, "arguments": dict(arguments), "account_id": connected_account_id}
        )
        if self.sleep_seconds:
            time.sleep(self.sleep_seconds)
        return {"successful": True, "data": {"ok": True}, "error": None}


class FakeToolkits:
    KNOWN = {"gmail", "googlecalendar", "googledrive", "github", "slack", "notion", "linkedin", "airtable"}

    def get(self, slug=None, **kwargs):
        if slug not in self.KNOWN:
            raise composio_errors.NotFoundError(f"toolkit {slug} not found")
        return types.SimpleNamespace(slug=slug, name=slug)


class FakeComposio:
    def __init__(self):
        self.connected_accounts = FakeConnectedAccounts()
        self.auth_configs = FakeAuthConfigs()
        self.tools = FakeTools()
        self.toolkits = FakeToolkits()


def seed_catalog(fake: FakeComposio) -> None:
    list_schema = {"type": "object", "properties": {"max_results": {"type": "integer"}}}
    send_schema = {
        "type": "object",
        "properties": {"to": {"type": "string"}, "body": {"type": "string"}},
        "required": ["to", "body"],
        "additionalProperties": False,
    }
    fake.tools.tools = [
        FakeTool("gmail_list_messages", "List messages", "Lists Gmail messages",
                 "gmail", ["read"], list_schema),
        FakeTool("gmail_send_email", "Send email", "Sends a Gmail email",
                 "gmail", ["write"], send_schema),
        FakeTool("github_list_issues", "List issues", "Lists GitHub issues",
                 "github", ["read"], list_schema),
    ]



# --------------------------------------------------------------------------
# Fixtures
# --------------------------------------------------------------------------

def _build_app(api_key: str | None):
    cs_settings = Settings(
        log_json=False,
        cors_origins=["https://app.mina.example"],
        supabase_url=SUPABASE_URL,
        supabase_jwt_secret=JWT_SECRET,
        encryption_key=ENCRYPTION_KEY,
        supabase_audience="authenticated",
        database_url="sqlite+aiosqlite://",
        composio_api_key=api_key,
    )
    application = create_app(cs_settings)

    async def _create():
        async with application.state.database.engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)

    run_db(application.state.database, _create)
    return application


@pytest.fixture()
def cs_app():
    """App with a Composio key and a fake SDK installed."""
    application = _build_app("ac_test_key_000000")
    fake = FakeComposio()
    seed_catalog(fake)
    service = ComposioService(
        application.state.settings, client_factory=lambda key, timeout: fake
    )
    application.state.composio = service
    application.state.composio_fake = fake
    yield application
    asyncio.run(application.state.database.engine.dispose())


@pytest.fixture()
def cs_client(cs_app):
    from fastapi.testclient import TestClient

    return TestClient(cs_app)


@pytest.fixture()
def user_a():
    return uuid4()


@pytest.fixture()
def user_b():
    return uuid4()


def seed_account(app, user_id, toolkit="gmail", status="ACTIVE", account_id=None):
    fake = app.state.composio_fake
    account = FakeAccount(
        account_id or f"ca_{uuid4().hex[:8]}", user_namespace(user_id), status, toolkit
    )
    fake.connected_accounts.accounts.append(account)
    return account


# --------------------------------------------------------------------------
# Availability and key exposure
# --------------------------------------------------------------------------

def test_endpoints_answer_503_without_api_key():
    app = _build_app(None)
    try:
        client = TestClient(app)
        for method, url, payload in (
            ("post", "/v1/connections/gmail/connect", {}),
            ("get", "/v1/connections", None),
            ("get", "/v1/tools/search", None),
            ("post", "/v1/tools/execute", {"tool_slug": "gmail_list_messages", "arguments": {}}),
        ):
            if method == "post":
                response = client.post(url, json=payload, headers=headers_for(uuid4()))
            else:
                response = client.get(url, headers=headers_for(uuid4()))
            assert response.status_code == 503, (url, response.status_code)
            assert "MINA_COMPOSIO_API_KEY" in response.json()["detail"]
    finally:
        asyncio.run(app.state.database.engine.dispose())


def test_composio_api_key_never_reaches_android(cs_client, user_a):
    responses = [
        cs_client.post("/v1/connections/gmail/connect", json={}, headers=headers_for(user_a)),
        cs_client.get("/v1/connections", headers=headers_for(user_a)),
        cs_client.get("/v1/tools/search?q=send", headers=headers_for(user_a)),
    ]
    for response in responses:
        assert response.status_code in (200, 201), response.text
        assert "ac_test_key" not in response.text


# --------------------------------------------------------------------------
# Connection flow
# --------------------------------------------------------------------------

def test_connect_returns_redirect_url_and_reuses_auth_config(cs_client, user_a):
    first = cs_client.post("/v1/connections/gmail/connect", json={}, headers=headers_for(user_a))
    assert first.status_code == 201, first.text
    body = first.json()
    assert body["toolkit"] == "gmail"
    assert body["status"] == "INITIATED"
    assert body["redirect_url"].startswith("https://")
    fake = cs_client.app.state.composio_fake
    assert fake.connected_accounts.initiated[0]["user_ns"] == user_namespace(user_a)
    # Managed auth config created once, reused after.
    assert fake.auth_configs.created_with == [("gmail", {"type": "use_composio_managed_auth"})]
    cs_client.post("/v1/connections/gmail/connect", json={}, headers=headers_for(user_a))
    assert len(fake.auth_configs.created_with) == 1


def test_connect_unknown_toolkit_is_404(cs_client, user_a):
    response = cs_client.post("/v1/connections/crypto/connect", json={}, headers=headers_for(user_a))
    assert response.status_code == 404


def test_connect_supports_toolkits_beyond_the_headline_set(cs_client, user_a):
    response = cs_client.post("/v1/connections/airtable/connect", json={}, headers=headers_for(user_a))
    assert response.status_code == 201
    assert response.json()["toolkit"] == "airtable"


def test_list_connections_returns_only_own(cs_client, user_a, user_b):
    app = cs_client.app
    seed_account(app, user_a, "gmail")
    seed_account(app, user_b, "github")
    response = cs_client.get("/v1/connections", headers=headers_for(user_a))
    assert response.status_code == 200
    toolkits = {item["toolkit"] for item in response.json()["items"]}
    assert toolkits == {"gmail"}


def test_connection_status_owner_only(cs_client, user_a, user_b):
    account = seed_account(cs_client.app, user_a, "gmail")
    ok = cs_client.get(f"/v1/connections/{account.id}", headers=headers_for(user_a))
    assert ok.status_code == 200
    assert ok.json()["status"] == "ACTIVE"
    foreign = cs_client.get(f"/v1/connections/{account.id}", headers=headers_for(user_b))
    assert foreign.status_code == 404


def test_disconnect_revokes_and_cross_user_is_404(cs_client, user_a, user_b):
    app = cs_client.app
    account = seed_account(app, user_a, "gmail")
    # Seed the DB mirror row.
    async def _seed():
        async with app.state.database.session() as db:
            db.add(ComposioConnection(user_id=user_a, provider="gmail", account_id=account.id))
            await db.commit()
    run_db(app.state.database, _seed)

    foreign = cs_client.delete(f"/v1/connections/{account.id}", headers=headers_for(user_b))
    assert foreign.status_code == 404
    assert account.id in [a.id for a in app.state.composio_fake.connected_accounts.accounts]

    ok = cs_client.delete(f"/v1/connections/{account.id}", headers=headers_for(user_a))
    assert ok.status_code == 204
    fake = app.state.composio_fake
    assert (account.id, True) in fake.connected_accounts.deleted
    remaining = [a.id for a in fake.connected_accounts.accounts]
    assert account.id not in remaining


# --------------------------------------------------------------------------
# Tool discovery
# --------------------------------------------------------------------------

def test_search_returns_light_items_and_schema_on_demand(cs_client, user_a):
    light = cs_client.get("/v1/tools/search?q=send", headers=headers_for(user_a))
    assert light.status_code == 200
    items = light.json()["items"]
    assert [item["slug"] for item in items] == ["gmail_send_email"]
    assert all("input_parameters" not in item for item in items)

    full = cs_client.get(
        "/v1/tools/search?q=send&include_schemas=true", headers=headers_for(user_a)
    )
    schema = full.json()["items"][0]["input_parameters"]
    assert schema["required"] == ["to", "body"]

    by_toolkit = cs_client.get("/v1/tools/search?toolkits=github", headers=headers_for(user_a))
    assert [item["slug"] for item in by_toolkit.json()["items"]] == ["github_list_issues"]

    none = cs_client.get("/v1/tools/search?q=zzz_nothing", headers=headers_for(user_a))
    assert none.json()["items"] == []


# --------------------------------------------------------------------------
# Execution
# --------------------------------------------------------------------------

def test_execute_success_logs_metadata_without_secrets(cs_client, user_a, db):
    app = cs_client.app
    account = seed_account(app, user_a, "gmail")
    payload = {
        "tool_slug": "gmail_list_messages",
        "arguments": {"max_results": 5, "secret_marker": "hunter2-do-not-log"},
    }
    response = cs_client.post("/v1/tools/execute", json=payload, headers=headers_for(user_a))
    assert response.status_code == 201, response.text
    body = response.json()
    assert body["successful"] is True
    assert body["status"] == "completed"
    assert body["argument_keys"] == ["max_results", "secret_marker"]
    # The raw argument value never lands in the response or the log.
    assert "hunter2-do-not-log" not in response.text

    async def _check():
        async with app.state.database.session() as session:
            rows = (
                await session.execute(
                    select(ComposioExecution).where(ComposioExecution.user_id == user_a)
                )
            ).scalars().all()
            return rows

    rows = run_db(app.state.database, _check)
    assert len(rows) == 1
    record = rows[0]
    assert record.args_fingerprint == arguments_fingerprint(payload["arguments"])
    assert record.connected_account_id == account.id
    assert "hunter2-do-not-log" not in (record.result_excerpt or "")
    execution_id = body["id"]
    got = cs_client.get(f"/v1/tools/executions/{execution_id}", headers=headers_for(user_a))
    assert got.status_code == 200
    assert got.json()["tool_slug"] == "gmail_list_messages"


def test_execute_unknown_tool_is_404(cs_client, user_a):
    response = cs_client.post(
        "/v1/tools/execute",
        json={"tool_slug": "gmail_nope", "arguments": {}},
        headers=headers_for(user_a),
    )
    assert response.status_code == 404


def test_execute_validates_arguments_against_schema(cs_client, user_a):
    seed_account(cs_client.app, user_a, "gmail")
    missing = cs_client.post(
        "/v1/tools/execute",
        json={"tool_slug": "gmail_send_email", "arguments": {"to": "a@b.c"}},
        headers=headers_for(user_a),
    )
    assert missing.status_code == 422
    assert any("body" in v for v in missing.json()["detail"]["schema_violations"])

    wrong_type = cs_client.post(
        "/v1/tools/execute",
        json={"tool_slug": "gmail_send_email", "arguments": {"to": 5, "body": "hi"}},
        headers=headers_for(user_a),
    )
    assert wrong_type.status_code == 422

    unexpected = cs_client.post(
        "/v1/tools/execute",
        json={
            "tool_slug": "gmail_send_email",
            "arguments": {"to": "a@b.c", "body": "hi", "hacker": "field"},
        },
        headers=headers_for(user_a),
    )
    assert unexpected.status_code == 422
    assert any("hacker" in v for v in unexpected.json()["detail"]["schema_violations"])


def test_execute_requires_connected_account(cs_client, user_a):
    response = cs_client.post(
        "/v1/tools/execute",
        json={"tool_slug": "gmail_list_messages", "arguments": {}},
        headers=headers_for(user_a),
    )
    assert response.status_code == 409
    assert response.json()["detail"]["error"] == "connection_required"


def test_execute_rejects_foreign_connected_account(cs_client, user_a, user_b):
    account = seed_account(cs_client.app, user_a, "gmail")
    response = cs_client.post(
        "/v1/tools/execute",
        json={
            "tool_slug": "gmail_list_messages",
            "arguments": {},
            "connected_account_id": account.id,
        },
        headers=headers_for(user_b),
    )
    assert response.status_code == 404


def test_execute_rejects_toolkit_mismatch(cs_client, user_a):
    account = seed_account(cs_client.app, user_a, "github")
    response = cs_client.post(
        "/v1/tools/execute",
        json={
            "tool_slug": "gmail_list_messages",
            "arguments": {},
            "connected_account_id": account.id,
        },
        headers=headers_for(user_a),
    )
    assert response.status_code == 400


def test_execute_on_expired_connection_asks_reconnect(cs_client, user_a):
    seed_account(cs_client.app, user_a, "gmail", status="EXPIRED")
    response = cs_client.post(
        "/v1/tools/execute",
        json={"tool_slug": "gmail_list_messages", "arguments": {}},
        headers=headers_for(user_a),
    )
    assert response.status_code == 409
    assert response.json()["detail"]["error"] == "connection_inactive"


def test_execute_on_revoked_connection_asks_reconnect(cs_client, user_a):
    seed_account(cs_client.app, user_a, "gmail", status="REVOKED")
    response = cs_client.post(
        "/v1/tools/execute",
        json={"tool_slug": "gmail_list_messages", "arguments": {}},
        headers=headers_for(user_a),
    )
    assert response.status_code == 409


# --------------------------------------------------------------------------
# Approval policy
# --------------------------------------------------------------------------

def test_dangerous_tool_classification():
    assert is_dangerous_tool("gmail_send_email")
    assert is_dangerous_tool("slack_invite_user")
    assert is_dangerous_tool("github_delete_file")
    assert not is_dangerous_tool("gmail_list_messages")
    assert not is_dangerous_tool("github_get_repo")


def test_dangerous_tool_requires_approval_then_allows(cs_client, user_a, db):
    app = cs_client.app
    seed_account(app, user_a, "gmail")
    payload = {
        "tool_slug": "gmail_send_email",
        "arguments": {"to": "friend@example.com", "body": "hello"},
    }
    blocked = cs_client.post("/v1/tools/execute", json=payload, headers=headers_for(user_a))
    assert blocked.status_code == 403
    assert blocked.json()["detail"]["error"] == "approval_required"
    assert app.state.composio_fake.tools.executed == []

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
            await db.commit() if False else await session.commit()

    run_db(app.state.database, _approve)

    allowed = cs_client.post("/v1/tools/execute", json=payload, headers=headers_for(user_a))
    assert allowed.status_code == 201, allowed.text
    assert len(app.state.composio_fake.tools.executed) == 1


def test_other_user_cannot_reuse_approval(cs_client, user_a, user_b):
    app = cs_client.app
    seed_account(app, user_b, "gmail")
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
    response = cs_client.post("/v1/tools/execute", json=payload, headers=headers_for(user_b))
    assert response.status_code == 403


# --------------------------------------------------------------------------
# Error mapping and duplicate prevention
# --------------------------------------------------------------------------

def test_rate_limit_maps_to_429(cs_app, user_a):
    fake = cs_app.state.composio_fake
    seed_account(cs_app, user_a, "gmail")

    def _boom(*args, **kwargs):
        raise RateLimitError(
            "slow down",
            response=types.SimpleNamespace(
                status_code=429, headers={},
                request=types.SimpleNamespace(method="POST", url="https://backend.composio.dev/"),
            ),
            body=None,
        )

    fake.tools.execute = _boom
    service = cs_app.state.composio

    async def _run():
        async with cs_app.state.database.session() as session:
            from sqlalchemy.ext.asyncio import AsyncSession  # noqa: F401

            with pytest.raises(Exception) as excinfo:
                await service.execute(
                    user_a, session, "gmail_list_messages", {}
                )
            return excinfo.value

    exc = run_db(cs_app.state.database, _run)
    assert exc.status_code == 429


def test_auth_failure_maps_to_reauthorization(cs_app, user_a):
    seed_account(cs_app, user_a, "gmail")

    def _boom(*args, **kwargs):
        raise AuthenticationError(
            "unauthorized",
            response=types.SimpleNamespace(
                status_code=401, headers={},
                request=types.SimpleNamespace(method="POST", url="https://backend.composio.dev/"),
            ),
            body=None,
        )

    cs_app.state.composio_fake.tools.execute = _boom
    service = cs_app.state.composio

    async def _run():
        async with cs_app.state.database.session() as session:
            with pytest.raises(Exception) as excinfo:
                await service.execute(user_a, session, "gmail_list_messages", {})
            return excinfo.value

    exc = run_db(cs_app.state.database, _run)
    assert exc.status_code == 409
    assert exc.detail == "reauthorization_required"


def test_duplicate_execution_is_prevented(cs_app, user_a):
    """Two identical concurrent executions: only one runs."""
    seed_account(cs_app, user_a, "gmail")
    cs_app.state.composio_fake.tools.sleep_seconds = 0.3
    service: ComposioService = cs_app.state.composio

    async def _run():
        async def one():
            async with cs_app.state.database.session() as session:
                return await service.execute(user_a, session, "gmail_list_messages", {})

        async def two():
            async with cs_app.state.database.session() as session:
                return await service.execute(user_a, session, "gmail_list_messages", {})

        results = await asyncio.gather(one(), two(), return_exceptions=True)
        return results

    results = run_db(cs_app.state.database, _run)
    outcomes = sorted(
        (201 if not isinstance(r, Exception) else getattr(r, "status_code", None))
        for r in results
    )
    assert outcomes == [201, 409], results
    assert len(cs_app.state.composio_fake.tools.executed) == 1


def test_execution_get_is_owner_only(cs_client, user_a, user_b):
    app = cs_client.app
    seed_account(app, user_a, "gmail")
    created = cs_client.post(
        "/v1/tools/execute",
        json={"tool_slug": "gmail_list_messages", "arguments": {}},
        headers=headers_for(user_a),
    )
    execution_id = created.json()["id"]
    ok = cs_client.get(f"/v1/tools/executions/{execution_id}", headers=headers_for(user_a))
    assert ok.status_code == 200
    foreign = cs_client.get(f"/v1/tools/executions/{execution_id}", headers=headers_for(user_b))
    assert foreign.status_code == 404
