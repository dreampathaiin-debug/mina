"""Key priority policy: managed credits are never charged silently."""

from fastapi.testclient import TestClient

from mina_api.core.config import Settings
from mina_api.database.models import Base
from mina_api.main import create_app
from mina_api.security.crypto import EnvelopeCrypto
from mina_api.services.provider_keys import (
    create_key,
    provision_managed_keys,
    select_keys_for_request,
)
from tests.conftest import ENCRYPTION_KEY, JWT_SECRET, SUPABASE_URL, headers_for
from uuid import uuid4
import asyncio

K1 = ENCRYPTION_KEY
BYOK_KEY = "sk-openai-byok-key-abcdefgh12345678"


def _app(managed_json: str = '{"openai": "sk-managed-server-side-key-99999999"}'):
    settings = Settings(
        log_json=False,
        cors_origins=["https://app.mina.example"],
        supabase_url=SUPABASE_URL,
        supabase_jwt_secret=JWT_SECRET,
        supabase_audience="authenticated",
        database_url="sqlite+aiosqlite://",
        encryption_key=K1,
        managed_provider_keys=managed_json,
    )
    return create_app(settings)


def _seed(app, user_id):
    async def _go():
        async with app.state.database.session() as db:
            await provision_managed_keys(db, app.state.crypto, {"openai": "sk-managed-server-side-key-99999999"})
            await create_key(db, app.state.crypto, app.state.registry, user_id, "openai", BYOK_KEY)

    asyncio.run(_go())


def test_default_policy_is_byok_only():
    user = uuid4()
    app = _app()
    async def _go():
        async with app.state.database.engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
    asyncio.run(_go())
    _seed(app, user)
    async def _select():
        async with app.state.database.session() as db:
            return await select_keys_for_request(db, user, "openai")
    selected = asyncio.run(_select())
    sources = {row.source for row in selected}
    assert sources == {"byok"}


def _set_policy_via_api(app, user, **payload):
    client = TestClient(app)
    response = client.patch("/v1/settings/ai-policy", json=payload, headers=headers_for(user))
    assert response.status_code == 200


def test_byok_first_without_explicit_fallback_never_returns_managed():
    user = uuid4()
    app = _app()
    async def _go():
        async with app.state.database.engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
    asyncio.run(_go())
    _seed(app, user)
    _set_policy_via_api(app, user, policy="byok_first")
    async def _select():
        async with app.state.database.session() as db:
            return await select_keys_for_request(db, user, "openai")
    selected = asyncio.run(_select())
    assert {row.source for row in selected} == {"byok"}


def test_byok_first_with_explicit_fallback_returns_managed_too():
    user = uuid4()
    app = _app()
    async def _go():
        async with app.state.database.engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
    asyncio.run(_go())
    _seed(app, user)
    _set_policy_via_api(app, user, policy="byok_first", allow_managed_fallback=True)
    async def _select():
        async with app.state.database.session() as db:
            return await select_keys_for_request(db, user, "openai")
    selected = asyncio.run(_select())
    assert [row.source for row in selected] == ["byok", "managed"]


def test_managed_only_returns_only_managed():
    user = uuid4()
    app = _app()
    async def _go():
        async with app.state.database.engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
    asyncio.run(_go())
    _seed(app, user)
    _set_policy_via_api(app, user, policy="managed_only")
    async def _select():
        async with app.state.database.session() as db:
            return await select_keys_for_request(db, user, "openai")
    selected = asyncio.run(_select())
    assert {row.source for row in selected} == {"managed"}


def test_managed_keys_seeded_at_startup_but_never_listed_to_user():
    user = uuid4()
    app = _app()
    async def _go():
        async with app.state.database.engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
    asyncio.run(_go())
    import json as _json
    # simulate the lifespan hook
    managed = _json.loads(app.state.settings.managed_provider_keys)
    async def _seed_only():
        async with app.state.database.session() as db:
            await provision_managed_keys(db, app.state.crypto, managed)
    asyncio.run(_seed_only())
    client = TestClient(app)
    listed = client.get("/v1/providers/keys", headers=headers_for(user)).json()
    assert listed == []  # managed rows are invisible to clients


def test_managed_seeding_is_idempotent():
    app = _app()
    async def _go():
        async with app.state.database.engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
        async with app.state.database.session() as db:
            first = await provision_managed_keys(db, app.state.crypto, {"openai": "sk-managed-server-side-key-99999999"})
            second = await provision_managed_keys(db, app.state.crypto, {"openai": "sk-managed-server-side-key-99999999"})
            assert first == 1 and second == 0
    asyncio.run(_go())
