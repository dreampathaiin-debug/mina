"""Database access for Phase 1.

Only the readiness probe exists so far: when MINA_DATABASE_URL is set we
hold one async SQLAlchemy engine and probe with SELECT 1; when unset the
probe reports "skipped" so a free-tier deploy boots with zero required
infrastructure. Later phases (users, memories, tasks) extend this module
instead of introducing a second connection layer.
"""

from __future__ import annotations

import logging

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine, create_async_engine

logger = logging.getLogger("mina.database")

# Supabase/Render Postgres URLs arrive as postgres:// or postgresql://;
# SQLAlchemy's async driver needs the postgresql+asyncpg scheme.
def _to_async_url(url: str) -> str:
    if url.startswith("postgresql+asyncpg://") or url.startswith("postgresql+asyncpg://"):
        return url
    if url.startswith("postgres://"):
        return url.replace("postgres://", "postgresql+asyncpg://", 1)
    if url.startswith("postgresql://"):
        return url.replace("postgresql://", "postgresql+asyncpg://", 1)
    return url


class Database:
    def __init__(self, url: str | None) -> None:
        self._engine: AsyncEngine | None = create_async_engine(_to_async_url(url), pool_pre_ping=True) if url else None

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
