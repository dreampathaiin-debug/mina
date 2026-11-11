"""Temporary caches.

Redis holds temporary cache entries when MINA_REDIS_URL is set; every
cache degrades gracefully to a process-local TTL store when it is not
(mostly tests and free-tier deploys). All stores support tag-scoped
invalidation, expiration, and scope-by-construction keys.
"""

from __future__ import annotations

import abc
import time
from dataclasses import dataclass, field


class TemporaryCache(abc.ABC):
    @abc.abstractmethod
    def get(self, key: str): ...

    @abc.abstractmethod
    def set(self, key: str, value, ttl_seconds: float, tags: frozenset[str] | None = None) -> None: ...

    @abc.abstractmethod
    def delete(self, key: str) -> None: ...

    @abc.abstractmethod
    def invalidate_tags(self, tags: set[str]) -> int: ...

    @abc.abstractmethod
    def clear(self) -> None: ...


@dataclass
class _Entry:
    value: object
    expires_at: float
    tags: frozenset[str] = field(default_factory=frozenset)


class InMemoryTemporaryCache(TemporaryCache):
    """Process-local TTL cache with tag invalidation. Stand-in for Redis."""

    def __init__(self) -> None:
        self._entries: dict[str, _Entry] = {}
        self.hits = 0
        self.misses = 0

    def get(self, key: str):
        entry = self._entries.get(key)
        if entry is None:
            self.misses += 1
            return None
        if entry.expires_at < time.monotonic():
            del self._entries[key]
            self.misses += 1
            return None
        self.hits += 1
        return entry.value

    def set(self, key, value, ttl_seconds, tags=None):
        self._entries[key] = _Entry(
            value=value, expires_at=time.monotonic() + max(0.0, ttl_seconds), tags=tags or frozenset()
        )

    def delete(self, key: str) -> None:
        self._entries.pop(key, None)

    def invalidate_tags(self, tags: set[str]) -> int:
        doomed = [k for k, e in self._entries.items() if e.tags & tags]
        for key in doomed:
            del self._entries[key]
        return len(doomed)

    def clear(self) -> None:
        self._entries.clear()


class RedisTemporaryCache(TemporaryCache):
    """Redis-backed cache. Keys are plain strings; tags are tracked in a
    per-tag Redis set so invalidate_tags stays O(1) per tag."""

    def __init__(self, url: str, *, namespace: str = "mina:opt") -> None:
        import redis.asyncio  # imported lazily: only needed when Redis is configured

        self._namespace = namespace
        self._redis = redis.asyncio.from_url(url, decode_responses=True)

    def _key(self, key: str) -> str:
        return f"{self._namespace}:{key}"

    def _tag_key(self, tag: str) -> str:
        return f"{self._namespace}:tag:{tag}"

    async def aget(self, key: str):
        import json as _json

        raw = await self._redis.get(self._key(key))
        return _json.loads(raw) if raw else None

    async def aset(self, key: str, value, ttl_seconds: float, tags=None) -> None:
        import json as _json

        await self._redis.set(self._key(key), _json.dumps(value, default=str), ex=int(max(1, ttl_seconds)))
        for tag in tags or ():
            await self._redis.sadd(self._tag_key(tag), key)

    async def adelete(self, key: str) -> None:
        await self._redis.delete(self._key(key))

    async def ainvalidate_tags(self, tags: set[str]) -> int:
        removed = 0
        for tag in tags:
            keys = await self._redis.smembers(self._tag_key(tag))
            if keys:
                await self._redis.delete(*(self._key(k) for k in keys), self._tag_key(tag))
                removed += len(keys)
        return removed

    # Sync protocol surface (used by tests and non-async callers) falls back
    # to running the async calls on a private loop.
    def get(self, key: str):
        import asyncio

        return asyncio.run(self.aget(key))

    def set(self, key, value, ttl_seconds, tags=None):
        import asyncio

        asyncio.run(self.aset(key, value, ttl_seconds, tags))

    def delete(self, key: str) -> None:
        import asyncio

        asyncio.run(self.adelete(key))

    def invalidate_tags(self, tags: set[str]) -> int:
        import asyncio

        return asyncio.run(self.ainvalidate_tags(tags))

    def clear(self) -> None:
        import asyncio

        async def _go():
            async for key in self._redis.scan_iter(f"{self._namespace}:*"):
                await self._redis.delete(key)

        asyncio.run(_go())


class ToolSchemaCache:
    """Caches serialized tool schemas by fingerprint, so a request whose
    tool set did not change reuses the encoded forms."""

    def __init__(self, cache: TemporaryCache, ttl_seconds: float = 3600.0) -> None:
        self._cache = cache
        self._ttl = ttl_seconds

    def get(self, tool: dict):
        from mina_api.optimizer.tool_selection import fingerprint

        return self._cache.get(f"tools:{fingerprint(tool)}")

    def put(self, tool: dict, serialized) -> None:
        from mina_api.optimizer.tool_selection import fingerprint

        self._cache.set(
            f"tools:{fingerprint(tool)}",
            serialized,
            self._ttl,
            tags=frozenset({"tools"}),
        )


class EmbeddingCache:
    """Caches vectors by (embedder id, text hash): embeddings are pure
    functions of the text, so hits are exact."""

    def __init__(self, cache: TemporaryCache, ttl_seconds: float = 86400.0) -> None:
        self._cache = cache
        self._ttl = ttl_seconds

    @staticmethod
    def _key(embedder_id: str, text: str) -> str:
        import hashlib

        return "emb:" + hashlib.sha256(f"{embedder_id}:{text}".encode()).hexdigest()

    def get(self, embedder_id: str, text: str):
        return self._cache.get(self._key(embedder_id, text))

    def put(self, embedder_id: str, text: str, vector: list[float]) -> None:
        self._cache.set(self._key(embedder_id, text), vector, self._ttl, tags=frozenset({"embeddings"}))
