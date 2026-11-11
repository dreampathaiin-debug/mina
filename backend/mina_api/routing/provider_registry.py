"""Configurable model + provider capability registry.

No model name is hardcoded in code: every model the router can pick comes
from the registry, loaded from a JSON file (MINA_MODEL_REGISTRY_PATH) or
from the packaged default (default_registry.json next to this module).
Editing the JSON (or pointing MINA_MODEL_REGISTRY_PATH at a new file) is
the whole deployment surface for adding models or providers.

Model IDs in the packaged default were verified against provider
documentation in November 2026:
- OpenAI: https://developers.openai.com/api/docs/models
  (gpt-6-luna, gpt-6.1-sol, gpt-6-astra)
- Google: https://docs.cloud.google.com/gemini-enterprise-agent-platform
  /models/guides/gemini-3-8-flash and ai.google.dev/gemini-api/docs/models
  (gemini-3.5-flash-lite, gemini-3.8-flash, gemini-3.1-pro)
- Anthropic: https://platform.claude.com/docs/en/models/overview
  (claude-haiku-4-5, claude-sonnet-4-6, claude-opus-4-8)
- Groq: https://console.groq.com/docs/models
  (openai/gpt-oss-20b, openai/gpt-oss-120b, qwen/qwen3.6-27b; the older
  llama-3.1/3.3 ids were deprecated 2026-06-17)
- NVIDIA NIM ids follow the build.nvidia.com catalog ("org/model");
- OpenRouter ids follow the openrouter.ai/models slugs ("vendor/model").
Entries whose pricing Google/OpenAI did not publish a verified figure for
carry pricing: null; the router then reports cost as unknown instead of
guessing.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

DEFAULT_REGISTRY_PATH = Path(__file__).with_name("default_registry.json")


@dataclass(frozen=True)
class ModelSpec:
    model_id: str
    provider: str
    litellm_model: str
    display_name: str
    groups: frozenset[str]
    context_window: int
    max_output_tokens: int
    tools: bool
    vision: bool
    json_mode: bool
    priority: int
    timeout_seconds: float
    input_price: float | None
    output_price: float | None
    base_url: str | None = None
    enabled: bool = True

    def supports_group(self, group: str) -> bool:
        return group in self.groups


@dataclass(frozen=True)
class ProviderCapability:
    provider: str
    rpm_limit: int | None = None
    tpm_limit: int | None = None
    latency_class: str = "medium"  # fast | medium | slow


@dataclass(frozen=True)
class Registry:
    models: tuple[ModelSpec, ...]
    providers: dict[str, ProviderCapability]

    def get(self, model_id: str) -> ModelSpec | None:
        for spec in self.models:
            if spec.model_id == model_id:
                return spec
        return None

    def enabled_models(self) -> list[ModelSpec]:
        return [spec for spec in self.models if spec.enabled]

    def models_for_group(self, group: str, *, enabled_only: bool = True) -> list[ModelSpec]:
        pool = self.enabled_models() if enabled_only else list(self.models)
        return [spec for spec in pool if spec.supports_group(group)]

    def provider_caps(self, provider: str) -> ProviderCapability | None:
        return self.providers.get(provider)


def _parse_model(raw: dict[str, Any]) -> ModelSpec:
    pricing = raw.get("pricing_usd_per_mtok") or {}
    caps = raw.get("capabilities") or {}
    return ModelSpec(
        model_id=str(raw["model_id"]),
        provider=str(raw["provider"]).lower(),
        litellm_model=str(raw["litellm_model"]),
        display_name=str(raw.get("display_name", raw["model_id"])),
        groups=frozenset(raw["groups"]),
        context_window=int(raw["context_window"]),
        max_output_tokens=int(raw.get("max_output_tokens", 4096)),
        tools=bool(caps.get("tools", True)),
        vision=bool(caps.get("vision", False)),
        json_mode=bool(caps.get("json_mode", True)),
        priority=int(raw.get("priority", 100)),
        timeout_seconds=float(raw.get("timeout_seconds", 60.0)),
        input_price=pricing.get("input"),
        output_price=pricing.get("output"),
        base_url=raw.get("base_url"),
        enabled=bool(raw.get("enabled", True)),
    )


def _parse_provider(raw: dict[str, Any]) -> ProviderCapability:
    return ProviderCapability(
        provider=str(raw["provider"]).lower(),
        rpm_limit=raw.get("rpm_limit"),
        tpm_limit=raw.get("tpm_limit"),
        latency_class=str(raw.get("latency_class", "medium")),
    )


def registry_from_dict(data: dict[str, Any]) -> Registry:
    models = tuple(_parse_model(raw) for raw in data.get("models", []))
    providers = {
        _parse_provider(raw).provider: _parse_provider(raw) for raw in data.get("providers", [])
    }
    for spec in models:
        if spec.provider not in providers:
            raise ValueError(
                f"model {spec.model_id!r} references provider {spec.provider!r} "
                "which is missing from the registry's providers section"
            )
        if not spec.groups:
            raise ValueError(f"model {spec.model_id!r} declares no model group")
    return Registry(models=models, providers=providers)


def load_registry(path: str | Path | None = None) -> Registry:
    """Load the registry from MINA_MODEL_REGISTRY_PATH, or the packaged default."""
    source = Path(path) if path else DEFAULT_REGISTRY_PATH
    data = json.loads(source.read_text(encoding="utf-8"))
    return registry_from_dict(data)
