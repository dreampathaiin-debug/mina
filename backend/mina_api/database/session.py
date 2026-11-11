"""Database engine, sessions and the FastAPI get_db dependency.

MINA_DATABASE_URL selects the engine (asyncpg for Postgres/Supabase in
production, aiosqlite for tests). When the URL is unset the readiness
probe reports "skipped" and get_db refuses to open a session, so no code
path can silently run without a database.
"""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from typing import Any

from fastapi import HTTPException, Request
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker, create_async_engine

logger = logging.getLogger("mina.database")


def _to_async_url(url: str) -> str:
    if url.startswith("postgresql+asyncpg://"):
        return url
    if url.startswith("postgres://"):
        return url.replace("postgres://", "postgresql+asyncpg://", 1)
    if url.startswith("postgresql://"):
        return url.replace("postgresql://", "postgresql+asyncpg://", 1)
    return url


def _engine_kwargs(url: str) -> dict[str, Any]:
    kwargs: dict[str, Any] = {"pool_pre_ping": True}
    if url.startswith("sqlite"):
        from sqlalchemy.pool import StaticPool

        kwargs.update({"poolclass": StaticPool, "connect_args": {"check_same_thread": False}})
    return kwargs


class Database:
    def __init__(self, url: str | None) -> None:
        self._engine: AsyncEngine | None = (
            create_async_engine(_to_async_url(url), **_engine_kwargs(url)) if url else None
        )
        self._sessions = (
            async_sessionmaker(self._engine, expire_on_commit=False) if self._engine else None
        )

    @property
    def engine(self) -> AsyncEngine | None:
        return self._engine

    def session(self) -> AsyncSession:
        if self._sessions is None:
            raise RuntimeError("MINA_DATABASE_URL is not configured")
        return self._sessions()

    async def ready(self) -> str:
        """Returns "ok", "skipped" or "down". Never raises."""
        if self._engine is None:
            return "skipped"
        try:
            async with self._engine.connect() as conn:
                await conn.execute(text("SELECT 1"))
            return "ok"
        except Exception:
            logger.exception("database_readiness_failed", extra={"event": "database_readiness_failed"})
            return "down"

    async def close(self) -> None:
        if self._engine is not None:
            await self._engine.dispose()


async def get_db(request: Request) -> AsyncIterator[AsyncSession]:
    database: Database = request.app.state.database
    try:
        session = database.session()
    except RuntimeError:
        raise HTTPException(status_code=503, detail="Database is not configured")
    async with session:
        yield session
