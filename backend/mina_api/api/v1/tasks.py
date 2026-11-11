"""Tasks: create, list, execute. Execution is tenant-checked; the AI
worker itself lands in a later phase."""

from __future__ import annotations

from uuid import UUID

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from mina_api.database.models import Task
from mina_api.database.session import get_db
from mina_api.schemas.tenant import TaskCreate, TaskOut
from mina_api.security.auth import CurrentUser
from mina_api.services.tenant import get_owned, list_owned

router = APIRouter(prefix="/tasks", tags=["tasks"])


@router.post("", response_model=TaskOut, status_code=201)
async def create_task(payload: TaskCreate, user: CurrentUser, db: AsyncSession = Depends(get_db)) -> Task:
    task = Task(user_id=user.id, title=payload.title, payload=payload.payload)
    db.add(task)
    await db.commit()
    await db.refresh(task)
    return task


@router.get("", response_model=list[TaskOut])
async def list_tasks(user: CurrentUser, db: AsyncSession = Depends(get_db)) -> list[Task]:
    return await list_owned(db, Task, user.id)


@router.get("/{task_id}", response_model=TaskOut)
async def get_task(task_id: UUID, user: CurrentUser, db: AsyncSession = Depends(get_db)) -> Task:
    return await get_owned(db, Task, task_id, user.id)


@router.post("/{task_id}/execute", response_model=TaskOut)
async def execute_task(task_id: UUID, user: CurrentUser, db: AsyncSession = Depends(get_db)) -> Task:
    task = await get_owned(db, Task, task_id, user.id)
    if task.status not in {"queued", "failed"}:
        from fastapi import HTTPException

        raise HTTPException(status_code=409, detail=f"Task is {task.status}, not executable")
    task.status = "running"
    await db.commit()
    await db.refresh(task)
    return task
