"""Request/response schemas for the Phase 6 Composio integration.

Stable Android-facing shapes: authorization URLs, connection statuses and
execution records. Note what is absent: the Composio API key (server-side
only) and any client-supplied user_id (identity comes from the verified
token only).
"""

from __future__ import annotations

from uuid import UUID

from pydantic import BaseModel, Field


class ConnectRequest(BaseModel):
    """Body for POST /v1/connections/{app}/connect."""

    callback_url: str | None = Field(
        default=None,
        max_length=2048,
        description="Optional URL Composio redirects to after OAuth completes.",
    )


class ConnectionRequestOut(BaseModel):
    """A started OAuth authorization: Android opens redirect_url in a
    WebView or browser, then polls GET /v1/connections/{id}."""

    id: str
    toolkit: str
    status: str
    redirect_url: str | None = None


class ConnectionOut(BaseModel):
    id: str
    toolkit: str | None
    status: str
    created_at: str


class ConnectionListOut(BaseModel):
    items: list[ConnectionOut]


class ToolSearchItem(BaseModel):
    slug: str
    name: str | None = None
    description: str | None = None
    toolkit: str | None = None
    tags: list[str] = Field(default_factory=list)
    input_parameters: dict | None = None
    output_parameters: dict | None = None


class ToolkitCatalogItem(BaseModel):
    slug: str
    name: str
    description: str = ""
    logo: str = ""
    categories: list[str] = Field(default_factory=list)
    no_auth: bool = False


class ToolkitCatalogOut(BaseModel):
    items: list[ToolkitCatalogItem]


class ToolSearchOut(BaseModel):
    items: list[ToolSearchItem]


class ToolExecuteRequest(BaseModel):
    tool_slug: str = Field(min_length=1, max_length=200)
    arguments: dict = Field(default_factory=dict)
    connected_account_id: str | None = Field(default=None, max_length=160)


class ExecutionOut(BaseModel):
    id: UUID
    tool_slug: str
    status: str
    successful: bool | None = None
    data_excerpt: str | None = None
    result_truncated: bool = False
    error: str | None = None
    latency_ms: int | None = None
    argument_keys: list[str] = Field(default_factory=list)
    created_at: str
