"""Phase 6: production-grade Composio integration.

Server-side, managed Composio: the Composio API key lives in server
settings and never reaches Android; Android only ever receives
authorization URLs and stable statuses. Every Mina user gets their own
Composio user namespace ("mina:<supabase-uuid>"), which is the isolation
boundary: Composio only returns, lists, or executes connected accounts
whose user_id equals that namespace, so accounts can never be shared.

The official SDK (composio, the v3 Python SDK) is used directly and
verified at import time; nothing here re-implements its calls. SDK calls
are synchronous, so they run in worker threads via asyncio.to_thread and
never block the event loop.

Tool results are untrusted content: they are stored only as a length-
capped excerpt, logged without argument values, and returned to the
client as data, never as instructions.
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import re
import threading
import time
from datetime import datetime, timezone
from typing import Any, Callable
from uuid import UUID

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from mina_api.database.models import Approval, ComposioConnection, ComposioExecution

LOG = logging.getLogger("mina.composio")

USER_NS_PREFIX = "mina:"
RESULT_EXCERPT_LIMIT = 2000
MAX_SEARCH_LIMIT = 25
DEFAULT_SEARCH_LIMIT = 10
ACTIVE_STATUSES = ("ACTIVE",)
EXECUTABLE_TOOLKIT_STATUSES = ("ACTIVE",)
ACTIVE_CONNECTION_FILTER = ("ACTIVE",)

# Tools whose slugs write, send, or destroy something external require an
# explicit user-approved approval record before execution. Read-only verbs
# are exempt; this list is deliberately narrow so approval is asked of the
# actions that carry real consequences.
_DANGEROUS_TOOL = re.compile(
    r"(_send|send_|_delete|delete_|_remove|remove_|_trash|_forward|_invite|"
    r"_cancel|cancel_|_archive|archive_|_revoke|revoke_|_transfer|transfer_|"
    r"_withdraw|_unsubscribe|_ban|ban_)"
)

# Exceptions we translate into stable HTTP statuses. The composio package
# raises its own semantic errors on top of the composio_client transport
# errors, so both families are mapped.
import composio_client  # noqa: E402
from composio import exceptions as composio_errors  # noqa: E402
from composio_client._exceptions import (  # noqa: E402
    APIStatusError as _ClientAPIStatusError,
    APITimeoutError as _ClientAPITimeoutError,
    AuthenticationError as _ClientAuthenticationError,
    BadRequestError as _ClientBadRequestError,
    NotFoundError as _ClientNotFoundError,
    PermissionDeniedError as _ClientPermissionDeniedError,
    RateLimitError as _ClientRateLimitError,
)

from composio import Composio as _Composio  # noqa: E402


def user_namespace(user_id: UUID | str) -> str:
    """The Composio user id a Mina user maps to. One per Mina user, forever."""
    return f"{USER_NS_PREFIX}{user_id}"


def is_dangerous_tool(tool_slug: str) -> bool:
    return bool(_DANGEROUS_TOOL.search(tool_slug.lower()))


def arguments_fingerprint(arguments: dict) -> str:
    """Stable SHA-256 of the canonical argument JSON. No values are logged."""
    canonical = json.dumps(arguments, sort_keys=True, separators=(",", ":"), default=str)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _excerpt(value: Any) -> tuple[str, bool]:
    text = json.dumps(value, default=str) if not isinstance(value, str) else value
    truncated = len(text) > RESULT_EXCERPT_LIMIT
    return (text[:RESULT_EXCERPT_LIMIT], truncated)


def _default_client_factory(api_key: str, timeout: float):
    return _Composio(api_key=api_key, timeout=timeout)


def _toolkit_of(item: Any) -> str | None:
    toolkit = getattr(item, "toolkit", None)
    if toolkit is None:
        return None
    if isinstance(toolkit, str):
        return toolkit
    return getattr(toolkit, "slug", None) or getattr(toolkit, "name", None)


def _connection_out(item: Any) -> dict:
    return {
        "id": item.id,
        "toolkit": _toolkit_of(item),
        "status": item.status,
        "created_at": str(getattr(item, "created_at", "") or ""),
    }


def _tool_out(tool: Any, *, include_schema: bool = False) -> dict:
    out = {
        "slug": tool.slug,
        "name": tool.name,
        "description": tool.description,
        "toolkit": getattr(tool, "toolkit", None),
        "tags": list(getattr(tool, "tags", None) or []),
    }
    if include_schema:
        # Schemas are fetched on demand only; search responses stay light.
        out["input_parameters"] = getattr(tool, "input_parameters", None)
        out["output_parameters"] = getattr(tool, "output_parameters", None)
    else:
        out.pop("input_parameters", None)
    return out


def _validate_against_schema(schema: Any, arguments: Any) -> list[str]:
    """Validate arguments against the tool's JSON-Schema input_parameters.

    Covers the JSON-Schema subset Composio tools actually use: object
    structure, required keys, primitive types, enums, array items and
    nested objects. Unknown keys are rejected when the schema sets
    additionalProperties to false.
    """
    if not isinstance(schema, dict):
        return []
    if not isinstance(arguments, dict):
        return ["arguments must be a JSON object"]

    errors: list[str] = []
    properties = schema.get("properties") or {}
    for name in schema.get("required") or []:
        if name not in arguments:
            errors.append(f"missing required argument: {name}")
    if schema.get("additionalProperties") is False:
        for name in arguments:
            if name not in properties:
                errors.append(f"unexpected argument: {name}")
    for name, value in arguments.items():
        spec = properties.get(name)
        if spec is not None:
            errors.extend(_validate_value(name, value, spec))
    return errors


def _validate_value(name: str, value: Any, spec: dict) -> list[str]:
    errors: list[str] = []
    expected = spec.get("type")
    checks = {
        "string": lambda v: isinstance(v, str),
        "number": lambda v: isinstance(v, (int, float)) and not isinstance(v, bool),
        "integer": lambda v: isinstance(v, int) and not isinstance(v, bool),
        "boolean": lambda v: isinstance(v, bool),
        "array": lambda v: isinstance(v, list),
        "object": lambda v: isinstance(v, dict),
        "null": lambda v: v is None,
    }
    if expected in checks and not checks[expected](value):
        errors.append(f"argument '{name}' must be {expected}")
        return errors

    enum = spec.get("enum")
    if enum and value not in enum:
        errors.append(f"argument '{name}' must be one of {enum!r}")

    if isinstance(value, list) and spec.get("items") is not None:
        for index, item in enumerate(value):
            errors.extend(_validate_value(f"{name}[{index}]", item, spec["items"]))
    if isinstance(value, dict) and spec.get("type") == "object" and spec.get("properties"):
        nested = dict(spec)
        nested.pop("required", None)
        nested_required = spec.get("required") or []
        for required_name in nested_required:
            if required_name not in value:
                errors.append(f"argument '{name}' is missing required key '{required_name}'")
        for key, sub_value in value.items():
            sub_spec = spec["properties"].get(key)
            if sub_spec is not None:
                errors.extend(_validate_value(f"{name}.{key}", sub_value, sub_spec))
    return errors


class ComposioService:
    """Owns the server-side Composio SDK client and every cross-check that
    keeps one Mina user's connections and executions isolated from another's."""

    def __init__(self, settings, client_factory: Callable[..., Any] | None = None) -> None:
        self._api_key = settings.composio_api_key
        self._timeout = settings.composio_timeout_seconds
        self._client_factory = client_factory or _default_client_factory
        self._client: Any | None = None
        self._client_lock = threading.Lock()
        self._inflight_lock = threading.Lock()
        self._inflight: set[str] = set()
        self._obs_recorder = None

    # -- lifecycle ---------------------------------------------------------

    @property
    def available(self) -> bool:
        return bool(self._api_key)

    def _sdk(self):
        if not self.available:
            raise HTTPException(
                status_code=503,
                detail="composio_unavailable: MINA_COMPOSIO_API_KEY is not configured",
            )
        with self._client_lock:
            if self._client is None:
                self._client = self._client_factory(self._api_key, self._timeout)
        return self._client

    # -- transport ---------------------------------------------------------

    async def _call(self, fn: Callable[..., Any], *args: Any, **kwargs: Any) -> Any:
        def _run() -> Any:
            return fn(*args, **kwargs)

        try:
            return await asyncio.to_thread(_run)
        except HTTPException:
            raise
        except Exception as exc:  # noqa: BLE001 - mapped below
            raise self._map_error(exc) from exc

    @staticmethod
    def _map_error(exc: Exception) -> HTTPException:
        if isinstance(
            exc,
            (
                composio_errors.ApiKeyError,
                composio_errors.ApiKeyNotProvidedError,
                composio_errors.UserApiKeyNotProvidedError,
            ),
        ):
            return HTTPException(503, detail="composio_unavailable: server key rejected")
        if isinstance(exc, composio_errors.ComposioMultipleConnectedAccountsError):
            return HTTPException(409, detail="already_connected: disconnect first to reconnect")
        if isinstance(
            exc,
            (
                composio_errors.ConnectedAccountNotFoundError,
                composio_errors.ToolNotFoundError,
                composio_errors.NotFoundError,
                _ClientNotFoundError,
            ),
        ):
            return HTTPException(404, detail="not_found")
        if isinstance(
            exc,
            (
                composio_errors.InvalidParams,
                composio_errors.ValidationError,
                composio_errors.InvalidEnum,
                composio_errors.EntityIDError,
                composio_errors.InvalidEntityIdError,
            ),
        ):
            return HTTPException(400, detail="invalid_request")
        if isinstance(exc, (_ClientAPITimeoutError, composio_errors.ComposioSDKTimeoutError)):
            return HTTPException(504, detail="composio_timeout")
        if isinstance(exc, _ClientRateLimitError):
            retry_after = None
            response = getattr(exc, "response", None)
            if response is not None:
                retry_after = getattr(response, "headers", {}).get("Retry-After")
            headers = {"Retry-After": retry_after} if retry_after else None
            return HTTPException(429, detail="composio_rate_limited", headers=headers)
        if isinstance(exc, (_ClientAuthenticationError, _ClientPermissionDeniedError)):
            return HTTPException(409, detail="reauthorization_required")
        if isinstance(exc, _ClientAPIStatusError):
            status = getattr(exc, "status_code", None)
            if status == 429:
                return HTTPException(429, detail="composio_rate_limited")
            if status and 400 <= status < 500:
                return HTTPException(400, detail="invalid_request")
            return HTTPException(502, detail="composio_error")
        if isinstance(exc, composio_errors.ComposioError):
            return HTTPException(502, detail="composio_error")
        return HTTPException(502, detail="composio_error")

    # -- auth configs ------------------------------------------------------

    async def ensure_auth_config(self, toolkit_slug: str) -> str:
        """Return the auth config id for a toolkit, creating a Composio-managed
        config on first use. Managed configs are the minimum-permission path:
        Composio requests exactly the scopes the toolkit's managed OAuth app
        defines, and no server or client ever holds the provider's client
        secret."""

        def _run() -> str:
            sdk = self._sdk()
            existing = sdk.auth_configs.list(toolkits=[toolkit_slug])
            items = list(getattr(existing, "items", None) or [])
            for item in items:
                if not getattr(item, "is_disabled", False):
                    return item.id
            created = sdk.auth_configs.create(
                toolkit_slug, {"type": "use_composio_managed_auth"}
            )
            return created.id

        return await self._call(_run)

    # -- connections -------------------------------------------------------

    async def initiate_connect(
        self, user_id: UUID, db: AsyncSession, toolkit_slug: str,
        callback_url: str | None = None,
    ) -> dict:
        """Start a user-scoped OAuth authorization and return the redirect URL
        for Android to open. The Composio API key never leaves the server."""

        auth_config_id = await self.ensure_auth_config(toolkit_slug)

        def _run(auth_config_id: str = auth_config_id) -> dict:
            sdk = self._sdk()
            # The toolkit must exist in Composio's catalog; any toolkit
            # Composio offers is connectable, unknown slugs 404.
            sdk.toolkits.get(slug=toolkit_slug)
            request = sdk.connected_accounts.initiate(
                user_namespace(user_id),
                auth_config_id=auth_config_id,
                callback_url=callback_url,
                allow_multiple=False,
            )
            return {
                "id": request.id,
                "toolkit": toolkit_slug,
                "status": request.status or "INITIATED",
                "redirect_url": request.redirect_url,
            }

        result = await self._call(_run)
        db.add(
            ComposioConnection(
                user_id=user_id,
                provider=toolkit_slug,
                account_id=result["id"],
                status="pending",
            )
        )
        await db.commit()
        return result

    async def catalog(self, limit: int = 80) -> list[dict]:
        """Toolkit catalog for the app's Connectors screen: Composio's catalog
        ordered by usage, with name/description/logo for each app."""
        sdk = self._sdk()

        def _run() -> list[Any]:
            return sdk.toolkits.list(sort_by="usage", limit=limit).items

        items = await self._call(_run)
        out: list[dict] = []
        for it in items or []:
            meta = getattr(it, "meta", None)
            cats = getattr(meta, "categories", None) or []
            out.append(
                {
                    "slug": (getattr(it, "slug", "") or ""),
                    "name": (getattr(it, "name", "") or ""),
                    "description": (getattr(meta, "description", None) or "")[:180],
                    "logo": (getattr(meta, "logo", None) or ""),
                    "categories": [str(getattr(c, "name", c)) for c in cats][:3],
                    "no_auth": bool(getattr(it, "no_auth", False) or False),
                }
            )
        return out

    async def list_connections(self, user_id: UUID, db: AsyncSession) -> list[dict]:
        """List this user's connected accounts only. The user_ids filter is
        Composio-enforced, so another user's accounts cannot appear."""

        def _run() -> list[Any]:
            sdk = self._sdk()
            items: list[Any] = []
            cursor: str | None = None
            while True:
                page = sdk.connected_accounts.list(user_ids=[user_namespace(user_id)], limit=100)
                items.extend(page.items or [])
                cursor = getattr(page, "next_cursor", None)
                if not cursor:
                    break
            return items

        items = await self._call(_run)
        await self._reconcile_rows(db, user_id, items)
        return [_connection_out(item) for item in items]

    async def get_connection(self, user_id: UUID, db: AsyncSession, account_id: str) -> dict:
        item = await self._fetch_owned_account(user_id, account_id)
        out = _connection_out(item)
        await self._upsert_row(db, user_id, out["toolkit"], account_id, out["status"])
        return out

    async def delete_connection(self, user_id: UUID, db: AsyncSession, account_id: str) -> None:
        """Disconnect and revoke the provider tokens. Ownership is checked
        against the Composio account's user_id before anything is deleted."""

        def _run() -> None:
            sdk = self._sdk()
            item = sdk.connected_accounts.get(account_id)
            if getattr(item, "user_id", None) != user_namespace(user_id):
                raise HTTPException(404, detail="not_found")
            sdk.connected_accounts.delete(account_id, revoke_on_delete=True)

        await self._call(_run)
        result = await db.execute(
            select(ComposioConnection).where(
                ComposioConnection.user_id == user_id,
                ComposioConnection.account_id == account_id,
            )
        )
        for row in result.scalars():
            await db.delete(row)
        await db.commit()

    async def _fetch_owned_account(self, user_id: UUID, account_id: str) -> Any:
        def _run() -> Any:
            item = self._sdk().connected_accounts.get(account_id)
            if getattr(item, "user_id", None) != user_namespace(user_id):
                raise HTTPException(404, detail="not_found")
            return item

        return await self._call(_run)

    async def _reconcile_rows(self, db: AsyncSession, user_id: UUID, items: list[Any]) -> None:
        for item in items:
            await self._upsert_row(
                db, user_id, _toolkit_of(item), item.id, item.status
            )

    async def _upsert_row(
        self, db: AsyncSession, user_id: UUID, toolkit: str | None,
        account_id: str, status: str,
    ) -> None:
        if toolkit is None:
            return
        result = await db.execute(
            select(ComposioConnection).where(
                ComposioConnection.user_id == user_id,
                ComposioConnection.account_id == account_id,
            )
        )
        row = result.scalars().first()
        if row is None:
            db.add(
                ComposioConnection(
                    user_id=user_id, provider=toolkit,
                    account_id=account_id, status=status,
                )
            )
        else:
            row.status = status
        await db.commit()

    # -- tool discovery ----------------------------------------------------

    async def search_tools(
        self, query: str | None, toolkits: list[str] | None = None,
        limit: int = DEFAULT_SEARCH_LIMIT, include_schema: bool = False,
    ) -> list[dict]:
        """Search the Composio tool catalog. Schemas stay out of the response
        unless explicitly requested, so the LLM never receives every schema."""

        def _run() -> list[Any]:
            sdk = self._sdk()
            kwargs: dict[str, Any] = {"limit": min(max(limit, 1), MAX_SEARCH_LIMIT)}
            if query:
                kwargs["search"] = query
            if toolkits:
                kwargs["toolkits"] = toolkits
            return list(sdk.tools.get_raw_composio_tools(**kwargs) or [])

        tools = await self._call(_run)
        return [_tool_out(tool, include_schema=include_schema) for tool in tools]

    async def get_tool_schema(self, tool_slug: str) -> dict | None:
        def _run() -> Any | None:
            sdk = self._sdk()
            found = sdk.tools.get_raw_composio_tools(tools=[tool_slug])
            return found[0] if found else None

        tool = await self._call(_run)
        return _tool_out(tool, include_schema=True) if tool else None

    def set_recorder(self, recorder) -> None:
        """Phase 10: attach the usage recorder for tool executions."""
        self._obs_recorder = recorder

    # -- execution ---------------------------------------------------------

    async def execute(
        self, user_id: UUID, db: AsyncSession, tool_slug: str,
        arguments: dict, connected_account_id: str | None = None,
    ) -> dict:
        """Validate, gate, and execute one tool call, then record it.

        Order of checks: tool exists, arguments match the schema, the
        connected account belongs to this user and is ACTIVE, approval
        policy passes, no identical execution is already running. The
        execution is logged with an argument fingerprint and key names
        only, never argument values.
        """
        started = time.monotonic()

        schema = await self.get_tool_schema(tool_slug)
        if schema is None:
            raise HTTPException(404, detail=f"tool_not_found: {tool_slug}")

        violations = _validate_against_schema(
            schema.get("input_parameters") or {}, arguments
        )
        if violations:
            raise HTTPException(422, detail={"schema_violations": violations})

        account = await self._resolve_account(
            user_id, tool_slug, schema.get("toolkit"), connected_account_id
        )
        fingerprint = arguments_fingerprint(arguments)

        if is_dangerous_tool(tool_slug):
            approved = await self._has_approved_approval(db, user_id, tool_slug, fingerprint)
            if not approved:
                raise HTTPException(
                    403,
                    detail={
                        "error": "approval_required",
                        "tool_slug": tool_slug,
                        "hint": "this tool writes or destroys external data; "
                        "approve it before running",
                    },
                )

        inflight_key = f"{user_namespace(user_id)}:{tool_slug}:{fingerprint}"
        with self._inflight_lock:
            if inflight_key in self._inflight:
                raise HTTPException(409, detail="duplicate_execution_in_progress")
            self._inflight.add(inflight_key)

        try:
            def _run() -> dict:
                sdk = self._sdk()
                return sdk.tools.execute(
                    tool_slug,
                    arguments,
                    connected_account_id=account,
                )

            response = await self._call(_run)
        finally:
            with self._inflight_lock:
                self._inflight.discard(inflight_key)

        successful = bool(response.get("successful")) if isinstance(response, dict) else False
        data = response.get("data") if isinstance(response, dict) else {}
        error = response.get("error") if isinstance(response, dict) else None
        excerpt, truncated = _excerpt(data)
        latency_ms = int((time.monotonic() - started) * 1000)

        record = ComposioExecution(
            user_id=user_id,
            tool_slug=tool_slug,
            connected_account_id=account,
            args_fingerprint=fingerprint,
            argument_keys=sorted(arguments.keys()),
            status="completed" if successful else "failed",
            successful=successful,
            result_excerpt=excerpt,
            result_truncated=truncated,
            error=error,
            latency_ms=latency_ms,
        )
        db.add(record)
        # Anti-replay: a dangerous action's approval is single-use. It stays
        # valid while the execution is retried after failures, and is consumed
        # the moment one attempt succeeds, so the same fingerprint can never
        # ride one approval into repeat sends/deletions.
        if successful and is_dangerous_tool(tool_slug):
            await self._consume_approval(db, user_id, tool_slug, fingerprint)
        await db.commit()

        # Phase 10: tool duration lands in the usage ledger too.
        if self._obs_recorder is not None:
            await self._obs_recorder.record_tool_execution(
                db,
                user_id=str(user_id),
                tool_slug=tool_slug,
                duration_ms=latency_ms,
                success=successful,
                error_category=None if successful else "tool_error",
            )

        return {
            "id": str(record.id),
            "tool_slug": tool_slug,
            "status": record.status,
            "successful": successful,
            "data_excerpt": excerpt,
            "result_truncated": truncated,
            "error": error,
            "latency_ms": latency_ms,
            "argument_keys": record.argument_keys,
            "created_at": str(record.created_at),
        }

    async def _resolve_account(
        self, user_id: UUID, tool_slug: str, toolkit: str | None,
        connected_account_id: str | None,
    ) -> str:
        if connected_account_id is not None:
            item = await self._fetch_owned_account(user_id, connected_account_id)
            account_toolkit = _toolkit_of(item)
            if toolkit and account_toolkit and account_toolkit != toolkit:
                raise HTTPException(
                    400,
                    detail=f"connected_account_toolkit_mismatch: {account_toolkit} vs {toolkit}",
                )
            if item.status not in ACTIVE_CONNECTION_FILTER:
                raise HTTPException(
                    409,
                    detail={
                        "error": "connection_inactive",
                        "status": item.status,
                        "hint": "reconnect this toolkit before executing tools",
                    },
                )
            return item.id

        def _run() -> Any | None:
            sdk = self._sdk()
            # No status filter: an EXPIRED or REVOKED account must surface
            # as "reconnect", not as "no connection".
            pages = sdk.connected_accounts.list(
                user_ids=[user_namespace(user_id)],
                toolkit_slugs=[toolkit] if toolkit else None,
                limit=10,
            )
            items = list(pages.items or [])
            for candidate in items:
                if candidate.status in ACTIVE_CONNECTION_FILTER:
                    return candidate
            return items[0] if items else None

        item = await self._call(_run)
        if item is None:
            raise HTTPException(
                409,
                detail={
                    "error": "connection_required",
                    "toolkit": toolkit,
                    "hint": "connect this toolkit before executing its tools",
                },
            )
        if item.status not in ACTIVE_CONNECTION_FILTER:
            raise HTTPException(
                409,
                detail={
                    "error": "connection_inactive",
                    "status": item.status,
                    "hint": "reconnect this toolkit before executing tools",
                },
            )
        return item.id

    async def _has_approved_approval(
        self, db: AsyncSession, user_id: UUID, tool_slug: str, fingerprint: str
    ) -> bool:
        result = await db.execute(
            select(Approval).where(
                Approval.user_id == user_id, Approval.status == "approved"
            )
        )
        for approval in result.scalars():
            request = approval.request or {}
            if (
                request.get("kind") == "composio_tool"
                and request.get("tool_slug") == tool_slug
                and request.get("args_fingerprint") == fingerprint
            ):
                return True
        return False

    async def _consume_approval(
        self, db: AsyncSession, user_id: UUID, tool_slug: str, fingerprint: str
    ) -> None:
        """Mark every matching approved approval consumed after success."""
        result = await db.execute(
            select(Approval).where(
                Approval.user_id == user_id, Approval.status == "approved"
            )
        )
        for approval in result.scalars():
            request = approval.request or {}
            if (
                request.get("kind") == "composio_tool"
                and request.get("tool_slug") == tool_slug
                and request.get("args_fingerprint") == fingerprint
            ):
                request = dict(request)
                request["consumed_at"] = datetime.now(timezone.utc).isoformat()
                approval.request = request
                approval.status = "consumed"

    async def get_execution(self, db: AsyncSession, user_id: UUID, execution_id: UUID) -> dict:
        result = await db.execute(
            select(ComposioExecution).where(
                ComposioExecution.id == execution_id,
                ComposioExecution.user_id == user_id,
            )
        )
        record = result.scalars().first()
        if record is None:
            raise HTTPException(404, detail="not_found")
        return {
            "id": str(record.id),
            "tool_slug": record.tool_slug,
            "status": record.status,
            "successful": record.successful,
            "data_excerpt": record.result_excerpt,
            "result_truncated": record.result_truncated,
            "error": record.error,
            "latency_ms": record.latency_ms,
            "argument_keys": record.argument_keys,
            "created_at": str(record.created_at),
        }
