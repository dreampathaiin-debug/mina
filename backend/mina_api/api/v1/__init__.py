"""All /v1 routers."""

from fastapi import APIRouter

from mina_api.api.v1 import health

v1_router = APIRouter()
v1_router.include_router(health.router)

__all__ = ["v1_router"]
