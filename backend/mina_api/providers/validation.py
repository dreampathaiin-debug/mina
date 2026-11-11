"""Key validation: format check plus a live connectivity probe.

Every probe response is summarized into an ok flag, a latency and a short
detail. The key itself never appears in a detail string, a log line or an
exception message: only fixed phrases like 'key rejected by provider'."""

from __future__ import annotations

import time

import httpx

from mina_api.providers.registry import ProviderSpec


def _auth_headers(spec: ProviderSpec, key: str) -> tuple[dict[str, str], dict[str, str]]:
    headers: dict[str, str] = {}
    params: dict[str, str] = {}
    if spec.check_style == "bearer":
        headers["Authorization"] = f"Bearer {key}"
    elif spec.check_style == "x-api-key":
        headers["x-api-key"] = key
    elif spec.check_style == "bearer-plus-version":
        headers["Authorization"] = f"Bearer {key}"
        headers["anthropic-version"] = "2023-06-01"
    elif spec.check_style == "query":
        params["key"] = key
    return headers, params


async def check_connectivity(
    spec: ProviderSpec, key: str, http: httpx.AsyncClient
) -> dict[str, object]:
    headers, params = _auth_headers(spec, key)
    started = time.monotonic()
    try:
        response = await http.get(spec.check_url, headers=headers, params=params)
    except httpx.HTTPError:
        return {"ok": False, "latency_ms": None, "detail": "provider unreachable"}
    latency = int((time.monotonic() - started) * 1000)
    if 200 <= response.status_code < 300:
        return {"ok": True, "latency_ms": latency, "detail": "key accepted by provider"}
    if response.status_code in (401, 403):
        return {"ok": False, "latency_ms": latency, "detail": "key rejected by provider"}
    return {
        "ok": False,
        "latency_ms": latency,
        "detail": f"provider returned HTTP {response.status_code}",
    }
