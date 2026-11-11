"""The long-term memory service: storage, retrieval, ranking, dedup.

Everything is tenant-scoped by construction: every read and write takes
a user_id and filters on it, so no code path can touch another user's
memory. Retrieval never sends the whole store to a model; it returns
only the top-ranked slice within the requested budget.
"""

from __future__ import annotations

import hashlib
import json
import math
import uuid as uuid_mod
from datetime import datetime, timedelta, timezone
from uuid import UUID

from sqlalchemy import select, update

from mina_api.database.models import Memory
from mina_api.memory import safety
from mina_api.memory.embeddings import (
    EMBEDDING_DIM,
    cosine_similarity,
    hash_embedding,
    lexical_similarity,
    token_jaccard,
)
from mina_api.memory.types import (
    CONFIDENCE_CEILING,
    CONFIDENCE_FLOOR,
    CORRECTED_CONFIDENCE,
    DEDUP_SIMILARITY,
    EXTRACTED_CONFIDENCE_CAP,
    USER_STATED_CONFIDENCE,
    MemoryKind,
    MemorySource,
    MemoryStatus,
)

# Relevance weights and decay: semantic closeness dominates, recency
# and confidence modulate.
W_SEMANTIC = 0.60
W_RECENCY = 0.20
W_CONFIDENCE = 0.15
W_KIND = 0.05
RECENCY_HALFLIFE_DAYS = 30.0

_KIND_BOOST = {
    MemoryKind.WORKING: 1.0,
    MemoryKind.PROCEDURAL: 0.9,
    MemoryKind.SEMANTIC: 0.6,
    MemoryKind.FACT: 0.6,
    MemoryKind.EPISODIC: 0.3,
}


class MemoryError(Exception):
    """Base memory-service error; the API maps subclasses to HTTP codes."""


class MemoryNotFound(MemoryError):
    """The memory does not exist or belongs to another user."""


class Embedder:
    """Content -> vector. The default is the deterministic local one."""

    def embed(self, text: str) -> list[float]:
        return hash_embedding(text)


class MemoryService:
    def __init__(self, embedder: Embedder | None = None, cache=None) -> None:
        self.embedder = embedder or Embedder()
        self._cache = cache
        self._local_cache: dict[str, list[float]] = {}

    # ---- embeddings -------------------------------------------------

    def _embedding_for(self, text: str) -> list[float]:
        key = hashlib.sha256(text.encode("utf-8")).hexdigest()
        if key in self._local_cache:
            return self._local_cache[key]
        cached = None
        if self._cache is not None:
            try:
                raw = self._cache.get("mememb", key)
                if raw:
                    cached = json.loads(raw)
            except Exception:
                cached = None
        if cached is None:
            cached = self.embedder.embed(text)
            if self._cache is not None:
                try:
                    self._cache.set("mememb", key, json.dumps(cached), ttl=86400)
                except Exception:
                    pass
        self._local_cache[key] = cached
        return cached

    # ---- write path --------------------------------------------------

    async def create(
        self,
        db,
        user_id: UUID,
        content: str,
        *,
        kind: str = "semantic",
        source: str = "user_stated",
        confidence: float | None = None,
        source_ref: dict | None = None,
        user_approved: bool = False,
        reason: str | None = None,
        expires_at: datetime | None = None,
    ) -> tuple[Memory, bool]:
        """Store one memory. Returns (row, created_new).

        Rules enforced here: secrets are never stored; sensitive
        categories need user approval plus a reason; a near-duplicate of
        an existing active memory updates that row instead of piling up
        copies; extracted content never carries user-stated confidence.
        """
        content = content.strip()
        if not content:
            raise MemoryError("memory content is empty")

        sensitive, category = safety.screen_content(content)
        if sensitive and not (user_approved and reason):
            raise safety.SensitiveWithoutPermissionError(
                f"memory is sensitive ({category}); storing it needs explicit user approval and a reason"
            )

        if source == MemorySource.USER_STATED:
            floor = USER_STATED_CONFIDENCE
        elif source == MemorySource.CORRECTED:
            floor = CORRECTED_CONFIDENCE
        else:
            floor = EXTRACTED_CONFIDENCE_CAP
        default = floor
        conf = default if confidence is None else min(max(float(confidence), CONFIDENCE_FLOOR), CONFIDENCE_CEILING)
        conf = min(conf, floor)

        existing = await self._find_duplicate(db, user_id, content, kind)
        if existing is not None:
            # Same fact again: keep the existing row, merge the source
            # trail, and lift confidence only toward the new bound.
            existing.confidence = max(existing.confidence, conf)
            existing.source = source if existing.source == source else existing.source
            existing.updated_at = datetime.now(timezone.utc)
            merged_ref = existing.source_ref if isinstance(existing.source_ref, dict) else None
            if source_ref:
                refs = list(merged_ref.get("refs", [])) if merged_ref else []
                refs.append(source_ref)
                existing.source_ref = {"refs": refs[-5:]}
            await db.commit()
            await db.refresh(existing)
            return existing, False

        row = Memory(
            user_id=user_id,
            kind=kind,
            content=content,
            confidence=conf,
            source=source,
            source_ref=source_ref,
            status=MemoryStatus.ACTIVE,
            user_approved=sensitive,
            expires_at=expires_at,
            embedding=self._embedding_for(content),
        )
        db.add(row)
        await db.commit()
        await db.refresh(row)
        return row, True

    async def _find_duplicate(self, db, user_id: UUID, content: str, kind: str) -> Memory | None:
        """Nearest active same-kind memory at or above the dedup threshold."""

        target = self._embedding_for(content)
        rows = (
            await db.execute(
                select(Memory).where(
                    Memory.user_id == user_id,
                    Memory.status == MemoryStatus.ACTIVE,
                    Memory.kind.in_([kind, MemoryKind.FACT if kind != MemoryKind.FACT else MemoryKind.SEMANTIC]),
                )
            )
        ).scalars().all()
        best: tuple[float, Memory] | None = None
        for row in rows:
            vec = row.embedding if isinstance(row.embedding, list) else None
            lex = max(
                lexical_similarity(content, row.content),
                lexical_similarity(row.content, content),
            )
            jac = token_jaccard(content, row.content)
            score = max(cosine_similarity(target, vec), lex, jac)
            if best is None or score > best[0]:
                best = (score, row, lex, jac)
        if best is not None and (
            best[0] >= DEDUP_SIMILARITY or (best[3] >= 0.5 and best[2] >= 0.7)
        ):
            return best[1]
        return None

    # ---- read path ---------------------------------------------------

    async def get(self, db, user_id: UUID, memory_id: UUID) -> Memory:
        row = await db.get(Memory, memory_id)
        if row is None or row.user_id != user_id or row.status == MemoryStatus.DELETED:
            raise MemoryNotFound("memory not found")
        return row

    async def list_memories(
        self, db, user_id: UUID, *, kind: str | None = None, limit: int = 100
    ) -> list[Memory]:
        await self._expire_rows(db, user_id)
        query = select(Memory).where(
            Memory.user_id == user_id,
            Memory.status.in_([MemoryStatus.ACTIVE]),
        )
        if kind is not None:
            query = query.where(Memory.kind == kind)
        rows = (
            await db.execute(query.order_by(Memory.updated_at.desc()).limit(min(limit, 500)))
        ).scalars().all()
        return rows

    async def search(
        self,
        db,
        user_id: UUID,
        query_text: str,
        *,
        k: int = 8,
        kinds: list[str] | None = None,
        min_confidence: float | None = None,
    ) -> list[dict]:
        """Ranked retrieval. Only the top-k slice leaves the store.

        Every row scored is the user's own; the tenant filter is part
        of the query itself, never an afterthought.
        """
        await self._expire_rows(db, user_id)
        query = select(Memory).where(Memory.user_id == user_id, Memory.status == MemoryStatus.ACTIVE)
        if kinds:
            allowed = list(kinds)
            if MemoryKind.SEMANTIC in allowed:
                allowed.append(MemoryKind.FACT)
            query = query.where(Memory.kind.in_(allowed))
        if min_confidence is not None:
            query = query.where(Memory.confidence >= float(min_confidence))
        rows = (await db.execute(query.limit(1000))).scalars().all()

        qvec = self._embedding_for(query_text)
        now = datetime.now(timezone.utc)
        scored: list[dict] = []
        for row in rows:
            row_vec = row.embedding if isinstance(row.embedding, list) else None
            semantic = max(
                cosine_similarity(qvec, row_vec),
                lexical_similarity(query_text, row.content),
                lexical_similarity(row.content, query_text),
                token_jaccard(query_text, row.content),
            )
            recency = self._recency(row.updated_at, now)
            kind_score = _KIND_BOOST.get(row.kind, 0.5)
            score = (
                W_SEMANTIC * semantic
                + W_RECENCY * recency
                + W_CONFIDENCE * row.confidence
                + W_KIND * kind_score
            )
            scored.append({"row": row, "score": score})
        scored.sort(key=lambda item: item["score"], reverse=True)
        top = scored[: max(1, k)]

        results = []
        for item in top:
            row = item["row"]
            row.last_accessed_at = now
            results.append(
                {
                    "id": str(row.id),
                    "kind": row.kind,
                    "content": row.content,
                    "confidence": row.confidence,
                    "score": round(item["score"], 4),
                    "source": row.source,
                    "user_approved": row.user_approved,
                    "updated_at": row.updated_at,
                }
            )
        await db.commit()
        return results

    @staticmethod
    def _recency(updated_at, now: datetime) -> float:
        try:
            age_days = max(0.0, (now - updated_at).total_seconds() / 86400.0)
        except TypeError:
            return 0.5
        return math.pow(0.5, age_days / RECENCY_HALFLIFE_DAYS)

    # ---- update / delete ---------------------------------------------

    async def update(
        self, db, user_id: UUID, memory_id: UUID, *, content: str | None = None,
        kind: str | None = None, confidence: float | None = None,
        status: str | None = None, expires_at: datetime | None = None,
        user_approved: bool | None = None, reason: str | None = None,
    ) -> Memory:
        """User edits. A content change is a user correction: the old
        row is superseded (never deleted) and a corrected row carries
        the full-confidence replacement, so the audit trail survives."""
        row = await self.get(db, user_id, memory_id)

        if content is not None and content.strip() != row.content.strip():
            new_content = content.strip()
            sensitive, category = safety.screen_content(new_content)
            if sensitive and not (user_approved and reason):
                raise safety.SensitiveWithoutPermissionError(
                    f"corrected memory is sensitive ({category}); needs explicit approval and a reason"
                )
            row.status = MemoryStatus.SUPERSEDED
            replacement = Memory(
                user_id=user_id,
                kind=kind or row.kind,
                content=new_content,
                confidence=CORRECTED_CONFIDENCE,
                source=MemorySource.CORRECTED,
                source_ref={"corrects": str(row.id)},
                status=MemoryStatus.ACTIVE,
                user_approved=sensitive,
                expires_at=expires_at,
                embedding=self._embedding_for(new_content),
            )
            db.add(replacement)
            await db.flush()
            row.superseded_by = replacement.id
            row.updated_at = datetime.now(timezone.utc)
            await db.commit()
            await db.refresh(replacement)
            return replacement

        if kind is not None:
            row.kind = kind
        if confidence is not None:
            row.confidence = min(max(float(confidence), CONFIDENCE_FLOOR), CONFIDENCE_CEILING)
        if status is not None:
            row.status = status
        if expires_at is not None:
            row.expires_at = expires_at
        if user_approved is not None:
            row.user_approved = user_approved
        row.updated_at = datetime.now(timezone.utc)
        await db.commit()
        await db.refresh(row)
        return row

    async def delete(self, db, user_id: UUID, memory_id: UUID, *, purge: bool = False) -> dict:
        row = await db.get(Memory, memory_id)
        if row is None or row.user_id != user_id:
            raise MemoryNotFound("memory not found")
        if purge:
            await db.delete(row)
            await db.commit()
            return {"id": str(memory_id), "purged": True}
        row.status = MemoryStatus.DELETED
        row.updated_at = datetime.now(timezone.utc)
        await db.commit()
        return {"id": str(memory_id), "deleted": True}

    # ---- expiry -------------------------------------------------------

    async def _expire_rows(self, db, user_id: UUID) -> None:
        now = datetime.now(timezone.utc)
        rows = (
            await db.execute(
                select(Memory.id, Memory.expires_at).where(
                    Memory.user_id == user_id,
                    Memory.status == MemoryStatus.ACTIVE,
                    Memory.expires_at.isnot(None),
                )
            )
        ).all()
        stale_ids = [
            row_id
            for row_id, expires_at in rows
            if _as_utc(expires_at) is not None and _as_utc(expires_at) < now
        ]
        if stale_ids:
            await db.execute(
                update(Memory)
                .where(Memory.id.in_(stale_ids))
                .values(status=MemoryStatus.EXPIRED)
            )


    # ---- extraction ---------------------------------------------------

    async def extract(
        self, db, user_id: UUID, conversation_text: str, caller, *,
        max_memories: int = 10,
    ) -> list[dict]:
        """Extract memories from conversation text through the model.

        The extractor is told to mark anything it inferred as not
        confirmed; such candidates are capped at the extracted
        confidence and never stored as facts. Secrets are dropped
        before storage.
        """
        from mina_api.agent.schemas import parse_model_json
        from mina_api.memory.schemas import MemoryCandidate

        system = (
            "You extract durable long-term memories for a user. Extract only facts, "
            "preferences, past interactions worth remembering, and approved workflows. "
            "Set confirmed=true ONLY for what the user stated directly; set "
            "confirmed=false for anything inferred. Never extract passwords, API keys, "
            "tokens, or secrets. Reply with ONLY JSON: {\"memories\": [{\"content\": str, "
            "\"kind\": \"working|episodic|semantic|procedural\", \"confirmed\": bool, "
            "\"source_ref\": {}}]}"
        )
        try:
            raw, _model_id = await caller.call(
                db, user_id, task_type="memory_extraction", system=system,
                user=conversation_text[:6000], max_tokens=1024,
            )
            payload = parse_model_json(raw, _ExtractionEnvelope).model_dump()
        except Exception as exc:  # noqa: BLE001 - extraction is best-effort
            raise MemoryError(f"memory extraction failed: {exc}") from exc

        results: list[dict] = []
        for candidate in payload.get("memories", [])[:max_memories]:
            parsed = MemoryCandidate.model_validate(candidate)
            content = parsed.content.strip()
            try:
                safety.screen_content(content)
            except safety.SecretDetectedError:
                continue  # a secret is never stored, extracted or not
            kind = parsed.kind if parsed.kind in {k.value for k in MemoryKind} else "semantic"
            row, created = await self.create(
                db, user_id, content,
                kind=kind,
                source=MemorySource.EXTRACTED,
                confidence=None,
                source_ref=parsed.source_ref,
            )
            results.append(
                {
                    "id": str(row.id),
                    "content": row.content,
                    "kind": row.kind,
                    "confirmed": parsed.confirmed,
                    "created": created,
                }
            )
        return results


class _ExtractionEnvelope(__import__("pydantic").BaseModel):
    memories: list[dict] = []


def _as_utc(value):
    if value is None:
        return None
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value
