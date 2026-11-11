"""Phase 6: Composio tool discovery, search, and execution.

Search returns light tool metadata; full schemas are fetched on demand so
the LLM never receives every connected tool's schema. Execution validates
arguments against the tool's own JSON schema, checks ownership of the
connected account, enforces the approval policy, prevents duplicate
in-flight executions, and logs metadata without secrets.
"""

from __future__ import annotations

from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from sqlalchemy.ext.asyncio import AsyncSession

from mina_api.integrations.composio import ComposioService
from mina_api.schemas.composio import (
    ExecutionOut,
    ToolExecuteRequest,
    ToolSearchOut,
)
from mina_api.security.auth import CurrentUser
from mina_api.database.session import get_db

router = APIRouter(prefix="/tools", tags=["tools"])


def _service(request: Request) -> ComposioService:
    service: ComposioService = request.app.state.composio
    if not service.available:
        raise HTTPException(
            503,
            detail="composio_unavailable: MINA_COMPOSIO_API_KEY is not configured",
        )
    return service


@router.get("/search", response_model=ToolSearchOut, response_model_exclude_none=True)
async def search_tools(
    request: Request,
    user: CurrentUser,
    q: str | None = Query(default=None, max_length=200),
    toolkits: str | None = Query(default=None, max_length=400),
    limit: int = Query(default=10, ge=1, le=25),
    include_schemas: bool = Query(default=False),
) -> ToolSearchOut:
    """Search tools by intent. Pass include_schemas=true only for the one
    tool you are about to call."""
    service = _service(request)
    toolkit_list = [t.strip() for t in toolkits.split(",") if t.strip()] if toolkits else None
    items = await service.search_tools(
        q, toolkits=toolkit_list, limit=limit, include_schema=include_schemas
    )
    return ToolSearchOut(items=items)


@router.post("/execute", response_model=ExecutionOut, status_code=201)
async def execute_tool(
    payload: ToolExecuteRequest,
    request: Request,
    user: CurrentUser,
    db: AsyncSession = Depends(get_db),
) -> ExecutionOut:
    service = _service(request)
    result = await service.execute(
        user.id, db, payload.tool_slug, payload.arguments,
        connected_account_id=payload.connected_account_id,
    )
    return ExecutionOut(**result)


@router.get("/executions/{execution_id}", response_model=ExecutionOut)
async def get_execution(
    execution_id: UUID,
    request: Request,
    user: CurrentUser,
    db: AsyncSession = Depends(get_db),
) -> ExecutionOut:
    service = _service(request)
    result = await service.get_execution(db, user.id, execution_id)
    return ExecutionOut(**result)
