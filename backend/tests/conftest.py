"""Shared fixtures: an app built from explicit Settings (never the cached
get_settings(), so env leaks between tests stay impossible), a real
SQLite-backed schema for Phase 2 integration tests, and Supabase-shaped
test JWTs for two distinct user identities."""

from __future__ import annotations

import asyncio
import time
from uuid import UUID, uuid4

import jwt as pyjwt
import pytest
from cryptography.fernet import Fernet
from fastapi.testclient import TestClient

from mina_api.core.config import Settings
from mina_api.database.models import Base, Profile
from mina_api.main import create_app

SUPABASE_URL = "https://test-project.supabase.co"
ENCRYPTION_KEY = Fernet.generate_key().decode()
JWT_SECRET = "test-jwt-secret-please-ignore"


def token_for(
    user_id: UUID,
    *,
    expired: bool = False,
    audience: str = "authenticated",
    issuer_url: str = SUPABASE_URL,
    key: str = JWT_SECRET,
) -> str:
    now = int(time.time())
    payload = {
        "sub": str(user_id),
        "aud": audience,
        "iss": f"{issuer_url}/auth/v1",
        "iat": now - 5,
        "exp": now - 100 if expired else now + 3600,
        "email": f"user-{str(user_id)[:8]}@example.com",
        "role": "authenticated",
    }
    return pyjwt.encode(payload, key, algorithm="HS256")


def headers_for(user_id: UUID, **kwargs) -> dict:
    return {"Authorization": f"Bearer {token_for(user_id, **kwargs)}"}


def run_db(db, coro_factory):
    """Run a small async DB helper on the test thread's own loop."""
    return asyncio.run(coro_factory())


def set_role(db, user_id: UUID, role: str) -> None:
    async def _go():
        async with db.session() as session:
            profile = await session.get(Profile, user_id)
            profile.role = role
            await session.commit()

    run_db(db, _go)


def count_rows(db, model, user_id: UUID) -> int:
    from sqlalchemy import func, select

    async def _go():
        async with db.session() as session:
            result = await session.execute(
                select(func.count()).select_from(model).where(model.user_id == user_id)
            )
            return result.scalar_one()

    return run_db(db, _go)


@pytest.fixture()
def settings() -> Settings:
    return Settings(
        log_json=False,
        cors_origins=["https://app.mina.example"],
        supabase_url=SUPABASE_URL,
        supabase_jwt_secret=JWT_SECRET,
        encryption_key=ENCRYPTION_KEY,
        supabase_audience="authenticated",
        database_url="sqlite+aiosqlite://",
    )


@pytest.fixture()
def app(settings: Settings):
    application = create_app(settings)
    engine = application.state.database.engine

    async def _create():
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)

    asyncio.run(_create())
    yield application
    asyncio.run(engine.dispose())


@pytest.fixture()
def client(app) -> TestClient:
    return TestClient(app)


@pytest.fixture()
def db(app):
    return app.state.database


@pytest.fixture()
def user_a() -> UUID:
    return uuid4()


@pytest.fixture()
def user_b() -> UUID:
    return uuid4()
