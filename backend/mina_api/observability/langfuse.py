"""Optional Langfuse trace export.

Langfuse receives the SAME metadata-only events as the local ledger:
ids, model, provider, tokens, ESTIMATED cost, latency, error category.
Never key material, never raw prompt or completion text (traces are
names/metadata only). Export failures are swallowed: observability must
never break a user request. Disabled unless host + keys are configured.
"""

from __future__ import annotations

import json
import logging
import uuid
from datetime import datetime, timezone

import httpx

logger = logging.getLogger("mina.observability")

_FORBIDDEN_SUBSTRINGS = (
    "authorization", "api_key", "apikey", "password", "secret", "token",
    "bearer", "credential", "prompt", "messages", "content",
)


def sanitize_metadata(data: dict | None) -> dict:
    """Strip any field whose key looks like it could carry secrets or raw
    model I/O. Used for every payload that leaves the process."""
    if not data:
        return {}
    safe: dict = {}
    for key, value in data.items():
        lowered = str(key).lower()
        if any(fragment in lowered for fragment in _FORBIDDEN_SUBSTRINGS):
            continue
        if isinstance(value, dict):
            safe[key] = sanitize_metadata(value)
        elif isinstance(value, list):
            safe[key] = [sanitize_metadata(v) if isinstance(v, dict) else v for v in value]
        else:
            safe[key] = value
    return safe


class LangfuseExporter:
    def __init__(self, *, host: str, public_key: str, secret_key: str, timeout_seconds: float = 5.0) -> None:
        self._host = host.rstrip("/")
        self._auth = (public_key, secret_key)
        self._timeout = timeout_seconds

    async def export(self, events: list[dict]) -> bool:
        """Batch events to /api/public/ingestion. Returns True on 2xx."""
        if not events:
            return True
        batch = []
        now = datetime.now(timezone.utc).isoformat()
        for event in events:
            trace_id = str(uuid.uuid4())
            batch.append(
                {
                    "id": trace_id,
                    "type": "trace",
                    "timestamp": event.get("created_at") or now,
                    "name": event.get("kind", "usage_event"),
                    "userId": event.get("user_id"),
                    "metadata": sanitize_metadata(
                        {
                            "request_id": event.get("request_id"),
                            "conversation_id": event.get("conversation_id"),
                            "task_id": event.get("task_id"),
                            "provider": event.get("provider"),
                            "model": event.get("model"),
                            "selection_reason": event.get("selection_reason"),
                            "input_tokens": event.get("input_tokens"),
                            "output_tokens": event.get("output_tokens"),
                            "estimated_cost_usd": event.get("estimated_cost_usd"),
                            "cost_basis": event.get("cost_basis", "estimate"),
                            "billing_source": event.get("billing_source"),
                            "cache_hit": event.get("cache_hit"),
                            "latency_ms": event.get("latency_ms"),
                            "retry_count": event.get("retry_count"),
                            "fallback_count": event.get("fallback_count"),
                            "tool_slug": event.get("tool_slug"),
                            "tool_duration_ms": event.get("tool_duration_ms"),
                            "success": event.get("success"),
                            "error_category": event.get("error_category"),
                        }
                    ),
                }
            )
        try:
            async with httpx.AsyncClient(timeout=self._timeout) as client:
                response = await client.post(
                    f"{self._host}/api/public/ingestion",
                    auth=self._auth,
                    json={"batch": batch},
                    headers={"content-type": "application/json"},
                )
                ok = 200 <= response.status_code < 300
                if not ok:
                    logger.warning("langfuse_export_failed status=%s", response.status_code)
                return ok
        except Exception:  # noqa: BLE001 - export must never break requests
            logger.warning("langfuse_export_error", exc_info=True)
            return False

    @staticmethod
    def from_settings(settings) -> "LangfuseExporter | None":
        host = getattr(settings, "langfuse_host", None)
        public_key = getattr(settings, "langfuse_public_key", None)
        secret_key = getattr(settings, "langfuse_secret_key", None)
        if not (host and public_key and secret_key):
            return None
        return LangfuseExporter(host=host, public_key=public_key, secret_key=secret_key)
