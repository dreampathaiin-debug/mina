"""Provider prompt caching where supported.

Providers discount or speed up repeated prefixes. The optimizer marks the
stable prefix (system instructions, memory, summary, oldest kept turns)
with the provider's own caching syntax and leaves the fresh tail alone,
because a cache marker on the newest user message would cache the part
that changes every request.

  anthropic: explicit cache_control breakpoints on up to 4 prefix blocks
  openai:    implicit automatic prefix caching (no markers; this module
             only reports support and the minimum stable prefix)
  gemini:    implicit context caching on large prefixes
"""

from __future__ import annotations

from dataclasses import dataclass

# Minimum stable-prefix size before caching is worth it (tokens).
MIN_PREFIX_TOKENS = 1024

# Providers with prompt-caching support and how they take it.
_PROMPT_CACHING: dict[str, str] = {
    "anthropic": "explicit",
    "openai": "implicit",
    "gemini": "implicit",
}


@dataclass(frozen=True)
class CacheMarkResult:
    provider: str
    mode: str | None          # "explicit" | "implicit" | None
    marked_prefix: int        # messages carrying cache markers
    prefix_tokens: int


def supports_prompt_caching(provider: str) -> str | None:
    return _PROMPT_CACHING.get(provider)


def apply_cache_markers(messages: list[dict], provider: str, prefix_tokens: int) -> CacheMarkResult:
    mode = _PROMPT_CACHING.get(provider)
    if mode != "explicit" or prefix_tokens < MIN_PREFIX_TOKENS:
        return CacheMarkResult(provider, mode, 0, prefix_tokens)
    marked = 0
    marked_messages: list[dict] = []
    for message in messages:
        if marked >= 4:
            continue
        is_stable_prefix = message.get("role") in {"system"} or (
            message.get("role") in {"user", "assistant"} and marked == 0 and False
        )
        # Explicit-mode providers cache stable non-final blocks; mark system
        # blocks (and only them) so the changing tail stays uncached.
        if message.get("role") == "system":
            content = message.get("content")
            if isinstance(content, list):
                blocks = [dict(b) for b in content]
            else:
                blocks = [{"type": "text", "text": content}]
            blocks[-1] = {**blocks[-1], "cache_control": {"type": "ephemeral"}}
            message["content"] = blocks
            marked += 1
    return CacheMarkResult(provider, mode, marked, prefix_tokens)
