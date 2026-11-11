"""HTTP surface, versioned under /v1."""

from fastapi import APIRouter

from mina_api.api.v1 import v1_router

api_v1_router = APIRouter()
api_v1_router.include_router(v1_router)

__all__ = ["api_v1_router"]
