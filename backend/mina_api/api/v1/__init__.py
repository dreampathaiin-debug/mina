"""All /v1 routers."""

from fastapi import APIRouter

from mina_api.api.v1 import (
    agent,
    admin,
    connections,
    conversations,
    health,
    me,
    memory,
    optimizer,
    providers,
    routing,
    tasks,
    tools,
)

v1_router = APIRouter()
v1_router.include_router(health.router)
v1_router.include_router(agent.router)
v1_router.include_router(me.router)
v1_router.include_router(conversations.router)
v1_router.include_router(providers.router)
v1_router.include_router(routing.router)
v1_router.include_router(optimizer.router)
v1_router.include_router(tasks.router)
v1_router.include_router(connections.router)
v1_router.include_router(tools.router)
v1_router.include_router(memory.router)
v1_router.include_router(admin.router)

__all__ = ["v1_router"]
