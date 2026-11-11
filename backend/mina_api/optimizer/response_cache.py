"""Exact and semantic response caches.

Exact: hash of (scope, model, policy, temperature, max_tokens, messages,
tools). A hit is byte-identical replay.
Semantic: KNN over stored embeddings scoped by user + model + policy;
replays only above the similarity threshold.

Both go through CacheSafety before STORE and before REPLAY: a result from
an unsafe context is never stored, so it can never resurface.
"""

from __future__ import annotations

import hashlib
import json
import time

from mina_api.optimizer.safety import CacheDecision
from mina_api.optimizer.semantic import Embedder, SemanticEntry, SemanticStore
from mina_api.optimizer.stores import TemporaryCache


def scope_key(user_id: str, model_id: str, policy: str) -> str:
    return f"{user_id}|{model_id}|{policy}"


def exact_key(
    scope: str,
    *,
    messages: list[dict],
    tools: list[dict] | None,
    temperature: float | None,
    max_tokens: int | None,
) -> str:
    payload = json.dumps(
        {
            "scope": scope,
            "messages": messages,
            "tools": tools or [],
            "temperature": temperature,
            "max_tokens": max_tokens,
        },
        sort_keys=True,
        default=str,
    )
    return "exact:" + hashlib.sha256(payload.encode()).hexdigest()


class ExactResponseCache:
    def __init__(self, cache: TemporaryCache, ttl_seconds: float = 3600.0) -> None:
        self._cache = cache
        self._ttl = ttl_seconds

    def get(self, key: str) -> dict | None:
        return self._cache.get(key)

    def put(self, key: str, response: dict, *, user_id: str, conversation_id: str | None) -> None:
        tags = frozenset({f"user:{user_id}", "responses"} | (
            {f"conv:{conversation_id}"} if conversation_id else set()
        ))
        self._cache.set(key, response, self._ttl, tags=tags)

    def invalidate_user(self, user_id: str) -> int:
        return self._cache.invalidate_tags({f"user:{user_id}"})


class SemanticResponseCache:
    def __init__(
        self,
        store: SemanticStore,
        embedder: Embedder,
        *,
        threshold: float = 0.94,
        min_tokens_to_store: int = 512,
    ) -> None:
        self._store = store
        self._embedder = embedder
        self._threshold = threshold
        self._min_tokens = min_tokens_to_store

    def lookup(self, scope: str, prompt_text: str) -> tuple[dict, float] | None:
        embedding = self._embedder.embed(prompt_text)
        for entry, score in self._store.knn(scope, embedding):
            if score >= self._threshold:
                return entry.response, score
        return None

    def store(
        self,
        scope: str,
        prompt_text: str,
        response: dict,
        *,
        input_tokens: int,
        decision: CacheDecision,
    ) -> bool:
        if not decision.cacheable or input_tokens < self._min_tokens:
            return False
        self._store.put(
            SemanticEntry(
                key=scope,
                embedding=self._embedder.embed(prompt_text),
                response=response,
                prompt_preview=prompt_text,
                input_tokens=input_tokens,
                created_at=time.time(),
            )
        )
        return True

    def invalidate_scope(self, prefix: str) -> int:
        return self._store.invalidate(prefix)
