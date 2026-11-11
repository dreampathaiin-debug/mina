"""The plaintext key is never stored, returned or logged."""

import logging
from uuid import uuid4

import httpx
import pytest
from fastapi.testclient import TestClient

from mina_api.core.config import Settings
from mina_api.database.models import Base, ProviderKey
from mina_api.main import create_app
from tests.conftest import ENCRYPTION_KEY, JWT_SECRET, SUPABASE_URL, headers_for
from sqlalchemy import select

KEY = "sk-openai-plaintext-live-key-112233445566"
K1 = ENCRYPTION_KEY


@pytest.fixture()
def leak_app():
    settings = Settings(
        log_json=False,
        cors_origins=["https://app.mina.example"],
        supabase_url=SUPABASE_URL,
        supabase_jwt_secret=JWT_SECRET,
        supabase_audience="authenticated",
        database_url="sqlite+aiosqlite://",
        encryption_key=K1,
    )
    app = create_app(settings)
    import asyncio

    async def _create():
        async with app.state.database.engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)

    asyncio.run(_create())

    def handler(request: httpx.Request) -> httpx.Response:
        # Echo the Authorization header back in the body, like some providers
        # do in debug mode: the client must still never surface the key.
        return httpx.Response(200, json={"echo": request.headers.get("Authorization", "")})

    app.state.http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return app


def test_key_never_stored_returned_or_logged(leak_app, caplog):
    user = uuid4()
    client = TestClient(leak_app)
    with caplog.at_level(logging.DEBUG):
        created = client.post(
            "/v1/providers/keys",
            json={"provider": "openai", "key": KEY},
            headers=headers_for(user),
        ).json()
        listed = client.get("/v1/providers/keys", headers=headers_for(user)).json()
        tested = client.post(f"/v1/providers/keys/{created['id']}/test", headers=headers_for(user)).json()
        policy = client.patch(
            "/v1/settings/ai-policy", json={"policy": "byok_only"}, headers=headers_for(user)
        ).json()
        missing = client.post(f"/v1/providers/keys/{uuid4()}/test", headers=headers_for(user))

    # 1) Stored: the DB row holds ciphertext, never the plaintext.
    async def _row():
        async with leak_app.state.database.session() as db:
            result = await db.execute(select(ProviderKey).where(ProviderKey.user_id == user))
            return result.scalars().one()

    import asyncio

    row = asyncio.run(_row())
    assert row.key_value != KEY and KEY not in row.key_value
    assert row.wrapped_dek != KEY and KEY not in row.wrapped_dek

    # 2) Returned: no API response carries the plaintext, even when a provider
    #    echoes the header back.
    for body in (str(created), str(listed), str(tested), str(policy), missing.text):
        assert KEY not in body

    # 3) Logged: no log record contains the plaintext.
    for record in caplog.records:
        assert KEY not in record.getMessage(), record.getMessage()
        assert KEY not in str(record.__dict__.get("extra", {}))
