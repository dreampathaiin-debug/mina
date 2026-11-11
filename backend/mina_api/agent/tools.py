"""Tool registry for the orchestrator.

Composio tools are the real registry: discovery by intent, schemas on
demand and validated execution all come from the Phase 6 service, which
already enforces ownership, argument schemas, approval policy and
no-secret logging. The orchestrator adds nothing around secrets.
"""

from __future__ import annotations

from typing import Any
from uuid import UUID

from fastapi import HTTPException


class ToolExecutionError(Exception):
    def __init__(self, kind: str, detail: Any, transient: bool = False) -> None:
        super().__init__(str(detail))
        self.kind = kind
        self.detail = detail
        self.transient = transient


class ToolRegistry:
    async def discover(self, db, user_id: UUID, query: str, limit: int = 5) -> list[dict]:
        raise NotImplementedError

    async def execute(
        self, db, user_id: UUID, tool: str, arguments: dict, connected_account_id: str | None = None
    ) -> dict:
        raise NotImplementedError


class ComposioToolRegistry(ToolRegistry):
    def __init__(self, composio_service) -> None:
        self.composio = composio_service

    async def discover(self, db, user_id: UUID, query: str, limit: int = 5) -> list[dict]:
        try:
            out = await self.composio.search_tools(db, user_id, query, limit=limit, include_schemas=False)
        except HTTPException as exc:
            if exc.status_code == 503:
                raise ToolExecutionError("unavailable", "tool registry unavailable", transient=True) from exc
            raise ToolExecutionError("discovery_failed", str(exc.detail)) from exc
        items = out.get("tools", []) if isinstance(out, dict) else out
        # Light items only: never ship full schemas to the model.
        return [
            {"name": t.get("name"), "description": t.get("description"), "toolkit": t.get("toolkit")}
            for t in items
        ]

    async def execute(
        self, db, user_id: UUID, tool: str, arguments: dict, connected_account_id: str | None = None
    ) -> dict:
        try:
            return await self.composio.execute(db, user_id, tool, arguments, connected_account_id)
        except HTTPException as exc:
            detail = exc.detail
            status = exc.status_code
            kind = {
                403: "approval_required",
                404: "tool_not_found",
                409: "conflict",
                422: "invalid_arguments",
                429: "rate_limited",
                503: "unavailable",
                504: "timeout",
            }.get(status, "tool_error")
            transient = status in {429, 503, 504}
            return {"_failed": True, "kind": kind, "detail": detail, "status": status, "transient": transient}
