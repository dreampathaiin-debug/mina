"""Embedding generation for memory content.

The default provider is a deterministic local hash embedding: it works
offline, in tests, and when no embedding model is configured, so memory
retrieval never depends on a network call. When an embedding provider
is configured later it plugs in behind the same interface. Embeddings
are cached by content hash so repeated saves and searches do not
recompute.
"""

from __future__ import annotations

import hashlib
import math
import re

EMBEDDING_DIM = 384

_TOKEN_RE = re.compile(r"[a-z0-9]+")
_STOPWORDS = frozenset(
    "a an and are as at be but by for from has have i in is it its my of on or that the this to was were will with you your".split()
)


def hash_embedding(text: str, dim: int = EMBEDDING_DIM) -> list[float]:
    """Deterministic bag-of-words hash embedding, L2-normalised."""

    vector = [0.0] * dim
    tokens = _TOKEN_RE.findall(text.lower())
    tokens = [t for t in tokens if t not in _STOPWORDS]
    for token in tokens:
        digest = hashlib.sha256(token.encode("utf-8")).digest()
        for offset in (0, 8):
            bucket = int.from_bytes(digest[offset : offset + 4], "big") % dim
            sign = 1.0 if digest[offset + 4] % 2 == 0 else -1.0
            vector[bucket] += sign
    norm = math.sqrt(sum(v * v for v in vector))
    if norm == 0.0:
        return vector
    return [v / norm for v in vector]


def cosine_similarity(a: list[float] | None, b: list[float] | None) -> float:
    if not a or not b or len(a) != len(b):
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    return max(0.0, min(1.0, dot))


def lexical_similarity(query: str, text: str) -> float:
    """Fallback overlap score when either side has no embedding."""

    query_tokens = {t for t in _TOKEN_RE.findall(query.lower()) if t not in _STOPWORDS}
    text_tokens = {t for t in _TOKEN_RE.findall(text.lower()) if t not in _STOPWORDS}
    if not query_tokens or not text_tokens:
        return 0.0
    overlap = len(query_tokens & text_tokens)
    return overlap / max(1, len(query_tokens))


def token_jaccard(a: str, b: str) -> float:
    a_tokens = {t for t in _TOKEN_RE.findall(a.lower()) if t not in _STOPWORDS}
    b_tokens = {t for t in _TOKEN_RE.findall(b.lower()) if t not in _STOPWORDS}
    if not a_tokens or not b_tokens:
        return 0.0
    return len(a_tokens & b_tokens) / len(a_tokens | b_tokens)
