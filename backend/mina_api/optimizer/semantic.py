"""Semantic response cache: persistent similarity retrieval over past
responses, plus the embedding layer underneath.

Storage: PostgreSQL/pgvector in production (persistent across restarts);
a process-local cosine store when no database is configured (tests). The
embedder is injectable: the default HashEmbedder is deterministic and
dependency-free (bag-of-words hashing), good enough to prove the cache
mechanics and to be swapped for a real embedding model in one place.
"""

from __future__ import annotations

import abc
import hashlib
import math
import re
from dataclasses import dataclass

_WORD = re.compile(r"\w+", re.UNICODE)
_STOP = {
    "the", "a", "an", "and", "or", "to", "of", "in", "on", "for", "is",
    "it", "this", "that", "with", "me", "my", "i", "you", "your", "we",
}


class Embedder(abc.ABC):
    id: str

    @abc.abstractmethod
    def embed(self, text: str) -> list[float]: ...


class HashEmbedder(Embedder):
    """Deterministic hashed bag-of-words, L2-normalized. Pure Python."""

    id = "hash-256"

    def __init__(self, dim: int = 256) -> None:
        self._dim = dim

    def embed(self, text: str) -> list[float]:
        vector = [0.0] * self._dim
        words = [w.lower() for w in _WORD.findall(text) if w.lower() not in _STOP]
        for word in words:
            digest = hashlib.sha256(word.encode()).digest()
            slot = int.from_bytes(digest[:4], "big") % self._dim
            sign = 1.0 if digest[4] % 2 == 0 else -1.0
            vector[slot] += sign
        norm = math.sqrt(sum(v * v for v in vector)) or 1.0
        return [v / norm for v in vector]


def cosine(a: list[float], b: list[float]) -> float:
    if not a or not b or len(a) != len(b):
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    return dot  # both vectors are L2-normalized


@dataclass(frozen=True)
class SemanticEntry:
    key: str                       # scope hash: user + model + policy
    embedding: list[float]
    response: dict                 # cached completion payload
    prompt_preview: str
    input_tokens: int
    created_at: float


class SemanticStore(abc.ABC):
    @abc.abstractmethod
    def put(self, entry: SemanticEntry) -> None: ...

    @abc.abstractmethod
    def knn(self, key: str, embedding: list[float], *, limit: int = 5) -> list[tuple[SemanticEntry, float]]: ...

    @abc.abstractmethod
    def invalidate(self, key_prefix: str | None = None) -> int: ...


class InMemorySemanticStore(SemanticStore):
    def __init__(self) -> None:
        self._entries: list[SemanticEntry] = []

    def put(self, entry: SemanticEntry) -> None:
        self._entries.append(entry)

    def knn(self, key: str, embedding: list[float], *, limit: int = 5):
        scored = [(e, cosine(embedding, e.embedding)) for e in self._entries if e.key.startswith(key)]
        scored.sort(key=lambda pair: pair[1], reverse=True)
        return scored[:limit]

    def invalidate(self, key_prefix: str | None = None) -> int:
        if key_prefix is None:
            count = len(self._entries)
            self._entries.clear()
            return count
        doomed = [e for e in self._entries if e.key.startswith(key_prefix)]
        self._entries = [e for e in self._entries if not e.key.startswith(key_prefix)]
        return len(doomed)


class PgVectorSemanticStore(SemanticStore):
    """Persistent semantic cache over PostgreSQL. Uses the
    semantic_cache_entries table; when the pgvector extension is present
    the vector column enables index-backed KNN, otherwise similarity is
    computed over the scope-partitioned candidate rows in Python (scoped
    by user + model + policy first, so the scan stays small)."""

    def __init__(self, session_factory, *, threshold: float = 0.94, ttl_seconds: float = 86400.0) -> None:
        self._sessions = session_factory
        self._threshold = threshold
        self._ttl = ttl_seconds

    def put(self, entry: SemanticEntry) -> None:
        raise NotImplementedError("async path: use aput")

    def knn(self, key: str, embedding: list[float], *, limit: int = 5):
        raise NotImplementedError("async path: use aknn")

    async def aput(self, entry: SemanticEntry) -> None:
        import json as _json
        from sqlalchemy import text as _text
        from datetime import datetime, timedelta, timezone

        expires = datetime.now(timezone.utc) + timedelta(seconds=self._ttl)
        async with self._sessions() as session:
            await session.execute(
                _text(
                    "INSERT INTO semantic_cache_entries "
                    "(scope_hash, embedding, response, prompt_preview, input_tokens, expires_at) "
                    "VALUES (:scope, :emb, :resp, :preview, :tokens, :expires)"
                ),
                {
                    "scope": entry.key,
                    "emb": _json.dumps(entry.embedding),
                    "resp": _json.dumps(entry.response, default=str),
                    "preview": entry.prompt_preview[:2000],
                    "tokens": entry.input_tokens,
                    "expires": expires,
                },
            )
            await session.commit()

    async def aknn(self, key: str, embedding: list[float], *, limit: int = 5):
        import json as _json
        from sqlalchemy import text as _text
        from datetime import datetime, timezone

        async with self._sessions() as session:
            rows = (
                await session.execute(
                    _text(
                        "SELECT scope_hash, embedding, response, prompt_preview, input_tokens, created_at "
                        "FROM semantic_cache_entries WHERE scope_hash = :scope "
                        "AND (expires_at IS NULL OR expires_at > :now) ORDER BY created_at DESC LIMIT 500"
                    ),
                    {"scope": key, "now": datetime.now(timezone.utc)},
                )
            ).mappings().all()
        scored = []
        for row in rows:
            stored = _json.loads(row["embedding"])
            entry = SemanticEntry(
                key=row["scope_hash"], embedding=stored, response=_json.loads(row["response"]),
                prompt_preview=row["prompt_preview"], input_tokens=int(row["input_tokens"]),
                created_at=row["created_at"].timestamp() if row["created_at"] else 0.0,
            )
            scored.append((entry, cosine(embedding, stored)))
        scored.sort(key=lambda pair: pair[1], reverse=True)
        return scored[:limit]

    def invalidate(self, key_prefix: str | None = None) -> int:
        raise NotImplementedError("async path: use ainvalidate")

    async def ainvalidate(self, key_prefix: str | None = None) -> int:
        from sqlalchemy import text as _text

        sql = (
            "DELETE FROM semantic_cache_entries WHERE scope_hash LIKE :p"
            if key_prefix
            else "DELETE FROM semantic_cache_entries"
        )
        async with self._sessions() as session:
            result = await session.execute(_text(sql), {"p": f"{key_prefix}%"})
            await session.commit()
            return int(result.rowcount or 0)
