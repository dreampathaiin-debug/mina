"""Provider validation: format checks and mocked connectivity probes."""

import httpx
import pytest

from mina_api.providers.registry import ProviderRegistry, parse_extra_providers
from mina_api.providers.validation import check_connectivity

import asyncio


def test_builtin_formats():
    registry = ProviderRegistry()
    assert registry.format_ok("openai", "sk-abcdefghijklmnopqrst") is True
    assert registry.format_ok("openai", "gsk_not_openai") is False
    assert registry.format_ok("anthropic", "sk-ant-abcdefghijklmnopqrstuvwx") is True
    assert registry.format_ok("groq", "gsk_abcdefghijklmnopqrstuvwxyz") is True
    assert registry.format_ok("gemini", "AIzaSyA1234567890abcdefghijklmnopqrstu") is True
    assert registry.format_ok("nvidia_nim", "nvapi-abcdefgh1234") is True
    assert registry.format_ok("openrouter", "sk-or-v1-abcdefghijklmnop") is True


def test_unknown_format_returns_none():
    registry = ProviderRegistry(parse_extra_providers("together=https://api.together.xyz/v1/models"))
    assert registry.format_ok("together", "anything-at-all") is None
    assert registry.get("together") is not None
    assert registry.get("nope") is None


def test_parse_extra_providers():
    extras = parse_extra_providers("A=https://x/v1/models, deepseek=https://y/v1/models ,bad")
    assert extras == {"a": "https://x/v1/models", "deepseek": "https://y/v1/models"}
    assert parse_extra_providers(None) == {}


def _probe(key: str, status: int) -> dict:
    def handler(request: httpx.Request) -> httpx.Response:
        auth = request.headers.get("Authorization", "")
        return httpx.Response(status, json={}) if auth.endswith(key) else httpx.Response(401)

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    spec = ProviderRegistry().get("openai")
    return asyncio.run(check_connectivity(spec, key, client))


def test_connectivity_ok():
    assert _probe("sk-valid-key-value", 200)["ok"] is True


def test_connectivity_rejected():
    result = _probe("sk-bad-key-value", 401)
    assert result["ok"] is False
    assert result["detail"] == "key rejected by provider"


def test_connectivity_unreachable():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("down")

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    spec = ProviderRegistry().get("openai")
    result = asyncio.run(check_connectivity(spec, "sk-any-key-value", client))
    assert result["ok"] is False
    assert result["detail"] == "provider unreachable"


def test_detail_never_contains_key():
    for result in (_probe("sk-secret-key-value", 200), _probe("sk-secret-key-value", 403)):
        assert "sk-secret-key-value" not in str(result)
