"""All /v1 routers."""

from fastapi import APIRouter

from mina_api.api.v1 import admin, conversations, health, me, provider_keys, tasks

v1_router = APIRouter()
v1_router.include_router(health.router)
v1_router.include_router(me.router)
v1_router.include_router(conversations.router)
v1_router.include_router(provider_keys.router)
v1_router.include_router(tasks.router)
v1_router.include_router(admin.router)

__all__ = ["v1_router"]
