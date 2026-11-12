"""Phase 6: Composio connections.

Every endpoint is tenant-checked twice: identity comes only from the
verified token, and Composio itself only returns accounts whose user_id
is this user's namespace. The Composio API key never appears in any
response.
"""

from __future__ import annotations

import logging
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy.ext.asyncio import AsyncSession

from mina_api.integrations.composio import ComposioService
from mina_api.schemas.composio import (
    ConnectionListOut,
    ConnectionOut,
    ConnectionRequestOut,
    ConnectRequest,
    ToolkitCatalogItem,
    ToolkitCatalogOut,
)
from mina_api.security.auth import CurrentUser
from mina_api.database.session import get_db

LOG = logging.getLogger("mina.composio")

router = APIRouter(prefix="/connections", tags=["connections"])

def _service(request: Request) -> ComposioService:
    service: ComposioService = request.app.state.composio
    if not service.available:
        raise HTTPException(
            503,
            detail="composio_unavailable: MINA_COMPOSIO_API_KEY is not configured",
        )
    return service


def _toolkit_slug(app: str) -> str:
    """Normalize the path segment; existence is checked against Composio's
    own catalog in the service, so any toolkit Composio offers works."""
    import re as _re

    slug = _re.sub(r"[^a-z0-9]", "", app.strip().lower())
    if not slug:
        raise HTTPException(404, detail=f"unknown_toolkit: {app}")
    return slug


@router.post("/{app}/connect", response_model=ConnectionRequestOut, status_code=201)
async def connect(
    app: str,
    payload: ConnectRequest,
    request: Request,
    user: CurrentUser,
    db: AsyncSession = Depends(get_db),
) -> ConnectionRequestOut:
    service = _service(request)
    toolkit = _toolkit_slug(app)
    started = await service.initiate_connect(
        user.id, db, toolkit, callback_url=payload.callback_url
    )
    LOG.info(
        "composio_connect_initiated",
        extra={"event": "composio_connect_initiated", "toolkit": toolkit},
    )
    return ConnectionRequestOut(**started)


@router.get("", response_model=ConnectionListOut)
async def list_connections(
    request: Request, user: CurrentUser, db: AsyncSession = Depends(get_db)
) -> ConnectionListOut:
    service = _service(request)
    items = await service.list_connections(user.id, db)
    return ConnectionListOut(items=[ConnectionOut(**item) for item in items])


@router.get("/catalog", response_model=ToolkitCatalogOut)
async def toolkit_catalog(request: Request, user: CurrentUser) -> ToolkitCatalogOut:
    """Toolkit catalog for the app's Connectors screen (usage-ordered)."""
    service = _service(request)
    items = await service.catalog()
    return ToolkitCatalogOut(items=[ToolkitCatalogItem(**item) for item in items])


@router.get("/{connection_id}", response_model=ConnectionOut)
async def connection_status(
    connection_id: str,
    request: Request,
    user: CurrentUser,
    db: AsyncSession = Depends(get_db),
) -> ConnectionOut:
    service = _service(request)
    item = await service.get_connection(user.id, db, connection_id)
    return ConnectionOut(**item)


@router.delete("/{connection_id}", status_code=204)
async def disconnect(
    connection_id: str,
    request: Request,
    user: CurrentUser,
    db: AsyncSession = Depends(get_db),
) -> None:
    service = _service(request)
    await service.delete_connection(user.id, db, connection_id)
