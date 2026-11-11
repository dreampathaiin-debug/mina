"""Global exception handling.

Unhandled errors return a safe 500 JSON body with the request id. Internal
details (tracebacks, provider errors, exception messages) go to the logs
only, never to the client.
"""

from __future__ import annotations

import logging

from fastapi import FastAPI, Request
from starlette.responses import JSONResponse

logger = logging.getLogger("mina.errors")

from mina_api.core.logging import request_id_var


def register_exception_handlers(app: FastAPI) -> None:
    @app.exception_handler(Exception)
    async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
        logger.exception(
            "unhandled_exception",
            extra={"event": "unhandled_exception", "method": request.method, "path": request.url.path},
        )
        request_id = getattr(request.state, "request_id", None) or request_id_var.get() or request.headers.get("x-request-id", "")
        return JSONResponse(
            {"error": "internal_error", "detail": "Something went wrong.", "request_id": request_id},
            status_code=500,
            headers={"x-request-id": request_id},
        )
