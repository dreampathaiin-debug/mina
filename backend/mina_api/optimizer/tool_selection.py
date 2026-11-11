"""Dynamic tool schema selection.

Sending every registered tool schema to every request wastes input
tokens. Only the tools whose names/descriptions match the request are
sent, the tools the task explicitly requires are always kept, and
serialized schemas are cached so repeated requests pay the estimate once.
"""

from __future__ import annotations

import hashlib
import json
import re

from mina_api.optimizer.memory_retrieval import _tokens
from mina_api.optimizer.token_estimation import estimate_text

_WORD = re.compile(r"\w+")


def _tool_name(tool: dict) -> str:
    function = tool.get("function") or tool
    return str(function.get("name", ""))


def _tool_description(tool: dict) -> str:
    function = tool.get("function") or tool
    return str(function.get("description", ""))


def fingerprint(tool: dict) -> str:
    return hashlib.sha256(
        json.dumps(tool, sort_keys=True, default=str).encode()
    ).hexdigest()[:16]


def select_tools(
    query_text: str,
    tools: list[dict] | None,
    *,
    required_tools: list[str] | None = None,
    budget_tokens: int = 4000,
    provider: str | None = None,
) -> list[dict]:
    if not tools:
        return []
    required = set(required_tools or [])
    scored: list[tuple[float, dict]] = []
    for tool in tools:
        name = _tool_name(tool)
        score = len(_tokens(query_text) & _tokens(f"{name} {_tool_description(tool)}"))
        if name in required:
            score += 1000  # required tools always win their place
        scored.append((float(score), tool))
    scored.sort(key=lambda pair: (-pair[0], _tool_name(pair[1])))
    selected: list[dict] = []
    used = 0
    for score, tool in scored:
        name = _tool_name(tool)
        cost = estimate_text(json.dumps(tool, default=str), provider) + 8
        if name in required:
            selected.append(tool)
            used += cost
            continue
        if score <= 0:
            continue
        if used + cost > budget_tokens:
            continue
        selected.append(tool)
        used += cost
    # A task that needs tools must not end up with none because everything
    # scored zero: keep the best-scoring tool as a floor.
    if not selected and tools and required_tools is None:
        best = max(
            tools,
            key=lambda t: len(
                _tokens(query_text) & _tokens(f"{_tool_name(t)} {_tool_description(t)}")
            ),
        )
        selected = [best]
    return selected
