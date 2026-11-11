"""The registry is config, not code: load, query, validate."""
import json

import pytest

from mina_api.routing.provider_registry import (
    DEFAULT_REGISTRY_PATH,
    load_registry,
    registry_from_dict,
)


def test_default_registry_loads():
    registry = load_registry()
    assert len(registry.models) >= 10
    assert {"openai", "gemini", "anthropic", "groq"} <= set(registry.providers)


def test_all_four_groups_have_models():
    registry = load_registry()
    for group in ("fast", "cheap", "balanced", "powerful"):
        assert registry.models_for_group(group), f"{group} group is empty"


def test_model_referencing_unknown_provider_is_rejected():
    data = json.loads(DEFAULT_REGISTRY_PATH.read_text())
    data["models"].append(
        {"model_id": "bogus-1", "provider": "nope", "litellm_model": "nope/bogus-1",
         "groups": ["fast"], "context_window": 1000, "capabilities": {}}
    )
    with pytest.raises(ValueError):
        registry_from_dict(data)


def test_external_registry_file_is_honoured(tmp_path):
    custom = {
        "providers": [{"provider": "acme", "rpm_limit": 5}],
        "models": [
            {"model_id": "acme-mini", "provider": "acme", "litellm_model": "openai/acme-mini",
             "groups": ["fast"], "context_window": 8000, "priority": 1, "capabilities": {"tools": False}}
        ],
    }
    path = tmp_path / "registry.json"
    path.write_text(json.dumps(custom))
    registry = load_registry(path)
    assert registry.get("acme-mini") is not None
    fast = registry.models_for_group("fast")
    assert [s.model_id for s in fast] == ["acme-mini"]


def test_default_registry_model_ids_match_provider_docs():
    """The packaged defaults verified against provider docs in Nov 2026."""
    registry = load_registry()
    ids = {spec.model_id for spec in registry.models}
    assert {"gpt-6-luna", "gpt-6.1-sol", "gpt-6-astra"} <= ids          # OpenAI docs
    assert {"gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-3.1-pro"} <= ids  # Google docs
    assert {"claude-haiku-4-5", "claude-sonnet-4-6", "claude-opus-4-8"} <= ids      # Anthropic docs
    groq = {spec.litellm_model for spec in registry.models if spec.provider == "groq"}
    assert "groq/openai/gpt-oss-20b" in groq and "groq/openai/gpt-oss-120b" in groq


def test_disabled_models_are_not_served():
    data = json.loads(DEFAULT_REGISTRY_PATH.read_text())
    data["models"][0]["enabled"] = False
    registry = registry_from_dict(data)
    first = data["models"][0]
    assert registry.get(first["model_id"]) is not None  # still queryable
    assert all(spec.enabled for spec in registry.enabled_models())
