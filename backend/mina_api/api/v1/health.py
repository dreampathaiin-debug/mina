"""Operational endpoints: liveness, readiness and version."""

from __future__ import annotations

from fastapi import APIRouter, Request
from starlette.responses import JSONResponse

from mina_api.schemas.health import HealthResponse, VersionResponse

router = APIRouter(tags=["ops"])


@router.get("/health", response_model=HealthResponse, summary="Liveness probe")
async def health() -> HealthResponse:
    """Cheap liveness check: the process is up and serving."""
    return HealthResponse(status="ok")


@router.get("/ready", summary="Readiness probe")
async def ready(request: Request) -> JSONResponse:
    """Readiness: every configured dependency answers. Unset optional
    dependencies (database in Phase 1) report "skipped" and count as ready."""
    checks: dict[str, str] = {}
    database = request.app.state.database
    checks["database"] = await database.ready()
    status = "ready" if all(v in {"ok", "skipped"} for v in checks.values()) else "degraded"
    return JSONResponse(
        {"status": status, "checks": checks},
        status_code=200 if status == "ready" else 503,
    )


@router.get("/version", response_model=VersionResponse, summary="Build info")
async def version(request: Request) -> VersionResponse:
    settings = request.app.state.settings
    return VersionResponse(
        service="mina-api",
        version=settings.version,
        environment=settings.environment,
    )
