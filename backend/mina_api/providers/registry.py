"""Provider catalog: built-in providers plus LiteLLM-compatible extras from
configuration (MINA_EXTRA_PROVIDERS="name=https://base-url/v1/models,...").
Extra providers get a bearer-token check against their configured endpoint;
their key format is unknown, so format checks are skipped (never invented)."""

from __future__ import annotations

import re
from dataclasses import dataclass


@dataclass(frozen=True)
class ProviderSpec:
    name: str
    display_name: str
    # Regex over the full key; None means "format unknown, skip the check".
    key_format: re.Pattern[str] | None
    check_url: str
    check_style: str  # "bearer" | "x-api-key" | "query" | "bearer-plus-version"


BUILTIN: dict[str, ProviderSpec] = {
    "nvidia_nim": ProviderSpec(
        "nvidia_nim", "NVIDIA NIM",
        re.compile(r"^nvapi-[A-Za-z0-9_-]{8,}$"),
        "https://integrate.api.nvidia.com/v1/models", "bearer",
    ),
    "openrouter": ProviderSpec(
        "openrouter", "OpenRouter",
        re.compile(r"^sk-or-v1-[A-Za-z0-9]{16,}$"),
        "https://openrouter.ai/api/v1/models", "bearer",
    ),
    "gemini": ProviderSpec(
        "gemini", "Google Gemini",
        re.compile(r"^AIza[0-9A-Za-z_-]{30,}$"),
        "https://generativelanguage.googleapis.com/v1beta/models", "query",
    ),
    "groq": ProviderSpec(
        "groq", "Groq",
        re.compile(r"^gsk_[A-Za-z0-9]{20,}$"),
        "https://api.groq.com/openai/v1/models", "bearer",
    ),
    "openai": ProviderSpec(
        "openai", "OpenAI",
        re.compile(r"^sk-[A-Za-z0-9_-]{20,}$"),
        "https://api.openai.com/v1/models", "bearer",
    ),
    "anthropic": ProviderSpec(
        "anthropic", "Anthropic",
        re.compile(r"^sk-ant-[A-Za-z0-9_-]{20,}$"),
        "https://api.anthropic.com/v1/models", "bearer-plus-version",
    ),
}


class ProviderRegistry:
    def __init__(self, extras: dict[str, str] | None = None) -> None:
        self._specs: dict[str, ProviderSpec] = dict(BUILTIN)
        for name, url in (extras or {}).items():
            self._specs[name.lower()] = ProviderSpec(
                name.lower(), name, None, url, "bearer"
            )

    def get(self, name: str) -> ProviderSpec | None:
        return self._specs.get(name.lower())

    def all(self) -> list[ProviderSpec]:
        return list(self._specs.values())

    def format_ok(self, name: str, key: str) -> bool | None:
        """True/False when the format is known, None when it is not."""
        spec = self.get(name)
        if spec is None or spec.key_format is None:
            return None
        return bool(spec.key_format.fullmatch(key))


def parse_extra_providers(raw: str | None) -> dict[str, str]:
    """'together=https://.../models,deepseek=https://...' -> dict."""
    extras: dict[str, str] = {}
    if not raw:
        return extras
    for chunk in raw.split(","):
        chunk = chunk.strip()
        if not chunk or "=" not in chunk:
            continue
        name, _, url = chunk.partition("=")
        name, url = name.strip(), url.strip()
        if name and url:
            extras[name.lower()] = url
    return extras
