"""Request-id propagation and request timeouts.

Request-id: taken from the client's X-Request-ID when sane, else generated.
Bound into logging context and echoed on the response. Timeouts: every
request is bounded so a stuck upstream can never pin a free-tier worker.
"""

from __future__ import annotations

import logging
import time
import uuid
from typing import Any, Awaitable, Callable

import anyio
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse, Response

from mina_api.core.logging import request_id_var

logger = logging.getLogger("mina.request")

AccessLog = Callable[[Request], Awaitable[Response]]


class RequestIdMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next: AccessLog) -> Response:
        raw = request.headers.get("x-request-id", "")
        # Accept standard id shapes (alnum plus - and _); anything odd is
        # replaced so a hostile header can never poison logs or responses.
        request_id = raw if raw.replace("-", "").replace("_", "").isalnum() and 0 < len(raw) <= 64 else uuid.uuid4().hex
        # Shared scope state: the 500 handler needs the id after this
        # middleware has already reset the contextvar on the way out.
        request.state.request_id = request_id
        token = request_id_var.set(request_id)
        started = time.perf_counter()
        try:
            response = await call_next(request)
        finally:
            request_id_var.reset(token)
        duration_ms = round((time.perf_counter() - started) * 1000, 2)
        response.headers["x-request-id"] = request_id
        # Deliberately no headers here: authorization must never hit the logs.
        logger.info(
            "http_request",
            extra={
                "event": "http_request",
                "method": request.method,
                "path": request.url.path,
                "status": response.status_code,
                "duration_ms": duration_ms,
            },
        )
        return response


class TimeoutMiddleware(BaseHTTPMiddleware):
    def __init__(self, app: Any, seconds: float) -> None:
        super().__init__(app)
        self.seconds = seconds

    async def dispatch(self, request: Request, call_next: AccessLog) -> Response:
        try:
            with anyio.fail_after(self.seconds):
                return await call_next(request)
        except TimeoutError:
            logger.warning(
                "request_timeout",
                extra={"event": "request_timeout", "method": request.method, "path": request.url.path},
            )
            return JSONResponse(
                {"error": "request_timeout", "detail": f"Request exceeded {self.seconds}s"},
                status_code=503,
                headers={"x-request-id": request_id_var.get() or ""},
            )
