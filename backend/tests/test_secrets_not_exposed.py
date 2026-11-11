"""The Supabase service role key never reaches a client response."""

import asyncio
from uuid import uuid4

from fastapi.testclient import TestClient

from mina_api.core.config import Settings
from mina_api.database.models import Base
from mina_api.main import create_app
from tests.conftest import JWT_SECRET, SUPABASE_URL, headers_for

SERVICE_KEY = "service-role-secret-value-never-leak"


def _client_with_service_key() -> TestClient:
    settings = Settings(
        log_json=False,
        cors_origins=["https://app.mina.example"],
        supabase_url=SUPABASE_URL,
        supabase_jwt_secret=JWT_SECRET,
        supabase_audience="authenticated",
        supabase_service_key=SERVICE_KEY,
        database_url="sqlite+aiosqlite://",
    )
    app = create_app(settings)
    engine = app.state.database.engine

    async def _create():
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)

    asyncio.run(_create())
    return TestClient(app)


def test_service_key_absent_from_every_response():
    client = _client_with_service_key()
    user = uuid4()
    probes = [
        client.get("/v1/version"),
        client.get("/v1/openapi.json"),
        client.get("/v1/me", headers=headers_for(user)),
        client.get("/v1/me"),
        client.post("/v1/conversations", json={"title": "x"}, headers=headers_for(user)),
        client.get("/v1/admin/users", headers=headers_for(user)),
    ]
    for probe in probes:
        assert SERVICE_KEY not in probe.text, probe.request.path
