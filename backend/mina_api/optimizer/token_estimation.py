"""Token estimation before every model request.

No tokenizer ships with the backend, so counts are model-specific
estimates: every provider tokenizes at a different characters-per-token
rate and CJK text costs several times more than ASCII. The estimator is
the single source both the optimizer's budget math and the metrics use,
so savings are measured against the same ruler everywhere. When a real
tokenizer is added later, only this module changes.
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass

# Estimated characters per token for ordinary ASCII prose, per provider
# family. These follow public tokenizer measurements; they are estimates
# and the code says so.
_CHARS_PER_TOKEN = {
    "openai": 4.0,
    "anthropic": 3.8,
    "gemini": 4.0,
    "groq": 4.0,
    "nvidia_nim": 4.0,
    "openrouter": 4.0,
}
_DEFAULT_CPT = 4.0

# CJK / Devanagari and friends: roughly one token per 1-1.5 characters,
# i.e. ~3x the ASCII cost per character against a 4.0 chars/token base.
_CJK_WEIGHT = 4.0

_CJK_RANGES = (
    (0x2E80, 0x9FFF),    # CJK radicals through unified ideographs
    (0xAC00, 0xD7AF),    # Hangul
    (0x0900, 0x097F),    # Devanagari
    (0xF900, 0xFAFF),    # CJK compatibility ideographs
    (0x1F300, 0x1FAFF),  # emoji
)

_MESSAGE_OVERHEAD = 4  # role + delimiters per message


def _weighted_chars(text: str) -> float:
    weight = 0.0
    for ch in text:
        code = ord(ch)
        if code < 128:
            weight += 1.0
            continue
        in_cjk = any(lo <= code <= hi for lo, hi in _CJK_RANGES)
        weight += _CJK_WEIGHT if in_cjk else 2.0
    return weight


@dataclass(frozen=True)
class TokenCount:
    text_tokens: int
    message_overhead: int
    tools_tokens: int
    total: int


def estimate_text(text: str, provider: str | None = None) -> int:
    if not text:
        return 0
    cpt = _CHARS_PER_TOKEN.get(provider or "", _DEFAULT_CPT)
    return max(1, math.ceil(_weighted_chars(text) / cpt))


def estimate_message(message: dict, provider: str | None = None) -> int:
    parts: list[str] = []
    content = message.get("content")
    if isinstance(content, str):
        parts.append(content)
    elif content is not None:  # content parts (vision, tool blobs)
        parts.append(json.dumps(content, default=str))
    if message.get("tool_calls"):
        parts.append(json.dumps(message["tool_calls"], default=str))
    if message.get("tool_call_id"):
        parts.append(str(message["tool_call_id"]))
    if message.get("name"):
        parts.append(str(message["name"]))
    return _MESSAGE_OVERHEAD + sum(estimate_text(p, provider) for p in parts)


def estimate_messages(
    messages: list[dict],
    *,
    tools: list[dict] | None = None,
    provider: str | None = None,
) -> TokenCount:
    text_tokens = 0
    overhead = 0
    for message in messages:
        text_tokens += estimate_text(
            message.get("content") if isinstance(message.get("content"), str) else "", provider
        )
        overhead += _MESSAGE_OVERHEAD
        extra = estimate_message(message, provider) - _MESSAGE_OVERHEAD - estimate_text(
            message.get("content") if isinstance(message.get("content"), str) else "", provider
        )
        text_tokens += max(0, extra)
    tools_tokens = 0
    for tool in tools or []:
        tools_tokens += estimate_text(json.dumps(tool, default=str), provider) + 8
    return TokenCount(
        text_tokens=text_tokens,
        message_overhead=overhead,
        tools_tokens=tools_tokens,
        total=text_tokens + overhead + tools_tokens,
    )


def estimate_request(
    messages: list[dict],
    *,
    tools: list[dict] | None = None,
    provider: str | None = None,
) -> int:
    return estimate_messages(messages, tools=tools, provider=provider).total
