"""Relevant memory retrieval.

Candidate memories are scored lexically against the current request and
only the best ones, within a token budget, reach the model. Retrieval
results are cached (see caching.py) so repeated similar requests do not
rescan the store.
"""

from __future__ import annotations

import math
import re
from dataclasses import dataclass

from mina_api.optimizer.token_estimation import estimate_text

_WORD = re.compile(r"\w+", re.UNICODE)
_STOP = {
    "the", "a", "an", "and", "or", "to", "of", "in", "on", "for", "is",
    "it", "this", "that", "with", "me", "my", "i", "you", "your", "we",
}


@dataclass(frozen=True)
class MemoryItem:
    id: str
    text: str
    importance: float = 0.5


def _tokens(text: str) -> set[str]:
    return {w.lower() for w in _WORD.findall(text) if w.lower() not in _STOP and len(w) > 1}


def relevance_score(query_text: str, memory_text: str) -> float:
    query_tokens = _tokens(query_text)
    if not query_tokens:
        return 0.0
    memory_tokens = _tokens(memory_text)
    if not memory_tokens:
        return 0.0
    overlap = query_tokens & memory_tokens
    if not overlap:
        return 0.0
    # IDF-ish: tokens rare across the memory corpus count more.
    idf = math.log(2 + len(memory_tokens) - len(overlap))
    return (len(overlap) / math.sqrt(len(query_tokens))) * (1 + idf / 10) * 0.5 + 0.5 * min(1.0, 0.0)


def retrieve_relevant(
    query_text: str,
    memories: list[MemoryItem],
    *,
    budget_tokens: int,
    provider: str | None = None,
    max_items: int = 8,
) -> list[MemoryItem]:
    scored = sorted(
        ((m, relevance_score(query_text, m.text) + m.importance * 0.1) for m in memories),
        key=lambda pair: pair[1],
        reverse=True,
    )
    selected: list[MemoryItem] = []
    used = 0
    for memory, score in scored:
        if score <= 0.05 or len(selected) >= max_items:
            continue
        cost = estimate_text(memory.text, provider)
        if used + cost > budget_tokens:
            continue
        selected.append(memory)
        used += cost
    return selected
