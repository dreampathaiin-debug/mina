"""Tasks API: the durable background task engine (Phase 9).

Back-compatible with the Phase 2 surface (POST/GET /v1/tasks,
/{id}, /{id}/execute) and extended with cancel/pause/resume/approve,
progress events and an SSE stream with event ids for reconnection.

The worker itself is never a FastAPI BackgroundTask: it runs as a
standalone loop, or is driven by an external scheduler hitting
POST /v1/tasks/worker/tick.
"""

from __future__ import annotations

import asyncio
import json
from datetime import datetime, timedelta, timezone
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from fastapi.responses import StreamingResponse
from sqlalchemy.ext.asyncio import AsyncSession

from mina_api.database.models import DurableTask
from mina_api.database.session import get_db
from mina_api.schemas.tenant import TaskOut  # noqa: F401  (kept for OpenAPI history)
from mina_api.security.auth import CurrentUser
from mina_api.taskengine.engine import TaskEngine, TaskOwnershipError, TaskStateError, utcnow
from mina_api.taskengine.schemas import TaskCreateIn, TaskEventOut, TaskOut as DurableTaskOut
from mina_api.taskengine.states import TERMINAL_STATES

router = APIRouter(prefix="/tasks", tags=["tasks"])


def _engine(request: Request) -> TaskEngine:
    return request.app.state.task_engine


def _worker(request: Request):
    return request.app.state.worker


@router.post("", response_model=DurableTaskOut, status_code=201)
async def create_task(
    payload: TaskCreateIn,
    user: CurrentUser,
    request: Request,
    db: AsyncSession = Depends(get_db),
) -> DurableTask:
    task, _created = await _engine(request).create_task(
        db, user.id,
        kind=payload.kind,
        title=payload.title,
        payload=payload.payload,
        idempotency_key=payload.idempotency_key,
        scheduled_at=payload.scheduled_at,
        requires_approval=payload.requires_approval,
        max_attempts=payload.max_attempts or 3,
    )
    return task


@router.get("", response_model=list[DurableTaskOut])
async def list_tasks(
    user: CurrentUser,
    request: Request,
    status: str | None = None,
    kind: str | None = None,
    limit: int = Query(default=100, ge=1, le=500),
    db: AsyncSession = Depends(get_db),
) -> list:
    return await _engine(request).list_tasks(db, user.id, status=status, kind=kind, limit=limit)


@router.get("/{task_id}", response_model=DurableTaskOut)
async def get_task(
    task_id: UUID, user: CurrentUser, request: Request, db: AsyncSession = Depends(get_db)
) -> DurableTask:
    try:
        return await _engine(request).get_owned(db, user.id, task_id)
    except TaskOwnershipError:
        raise HTTPException(status_code=404, detail="task not found") from None


@router.post("/{task_id}/execute", response_model=DurableTaskOut)
async def execute_task(
    task_id: UUID, user: CurrentUser, request: Request, db: AsyncSession = Depends(get_db)
) -> DurableTask:
    """Back-compat path: claim and run one attempt inline for the
    owner. Cross-user access is indistinguishable from missing."""
    engine = _engine(request)
    try:
        task = await engine.get_owned(db, user.id, task_id)
    except TaskOwnershipError:
        raise HTTPException(status_code=404, detail="task not found") from None
    claimed = await engine.claim_by_id(db, task_id, worker_id="inline-execute")
    if claimed is None:
        raise HTTPException(status_code=409, detail=f"Task is {task.status}, not executable")
    outcome = await _worker(request).execute_claimed(db, claimed)
    return await engine.get_owned(db, user.id, task_id)


@router.post("/{task_id}/cancel", response_model=DurableTaskOut)
async def cancel_task(
    task_id: UUID, user: CurrentUser, request: Request, db: AsyncSession = Depends(get_db)
) -> DurableTask:
    try:
        return await _engine(request).cancel_task(db, user.id, task_id)
    except TaskOwnershipError:
        raise HTTPException(status_code=404, detail="task not found") from None
    except TaskStateError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc


@router.post("/{task_id}/pause", response_model=DurableTaskOut)
async def pause_task(
    task_id: UUID, user: CurrentUser, request: Request, db: AsyncSession = Depends(get_db)
) -> DurableTask:
    try:
        return await _engine(request).pause_task(db, user.id, task_id)
    except TaskOwnershipError:
        raise HTTPException(status_code=404, detail="task not found") from None
    except TaskStateError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc


@router.post("/{task_id}/resume", response_model=DurableTaskOut)
async def resume_task(
    task_id: UUID, user: CurrentUser, request: Request, db: AsyncSession = Depends(get_db)
) -> DurableTask:
    try:
        return await _engine(request).resume_task(db, user.id, task_id)
    except TaskOwnershipError:
        raise HTTPException(status_code=404, detail="task not found") from None
    except TaskStateError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc


@router.post("/{task_id}/approve", response_model=DurableTaskOut)
async def approve_task(
    task_id: UUID,
    user: CurrentUser,
    request: Request,
    approved: bool = Query(default=True),
    db: AsyncSession = Depends(get_db),
) -> DurableTask:
    try:
        return await _engine(request).approve_task(db, user.id, task_id, approved=approved)
    except TaskOwnershipError:
        raise HTTPException(status_code=404, detail="task not found") from None
    except TaskStateError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc


@router.get("/{task_id}/events", response_model=list[TaskEventOut])
async def task_events(
    task_id: UUID,
    user: CurrentUser,
    request: Request,
    after_seq: int = Query(default=0, ge=0),
    db: AsyncSession = Depends(get_db),
) -> list:
    try:
        return await _engine(request).events_after(db, user.id, task_id, after_seq=after_seq)
    except TaskOwnershipError:
        raise HTTPException(status_code=404, detail="task not found") from None


@router.get("/{task_id}/events/stream")
async def task_event_stream(
    task_id: UUID,
    user: CurrentUser,
    request: Request,
    after_seq: int = Query(default=None),
    max_seconds: int = Query(default=30, ge=1, le=300),
) -> StreamingResponse:
    """SSE progress stream. Every event carries an id (its seq); a
    reconnecting client sends Last-Event-ID (or ?after_seq=) and
    resumes exactly where it left off, no gaps, no duplicates."""
    engine = _engine(request)
    header_seq = request.headers.get("last-event-id")
    start_seq = after_seq
    if start_seq is None:
        start_seq = int(header_seq) if (header_seq or "").isdigit() else 0

    async def stream():
        last = start_seq
        deadline = asyncio.get_event_loop().time() + max_seconds
        yield "retry: 3000\n\n"
        while True:
            try:
                async with request.app.state.database.session() as db:
                    events = await engine.events_after(db, user.id, task_id, after_seq=last)
                    task = await engine.get_owned(db, user.id, task_id)
            except TaskOwnershipError:
                yield ": task not found\n\n"
                return
            for event in events:
                last = event.seq
                payload = json.dumps(
                    {"type": event.type, "data": event.data, "seq": event.seq},
                    default=str,
                )
                yield f"id: {event.seq}\nevent: {event.type}\ndata: {payload}\n\n"
            if events:
                continue  # drain immediately before polling again
            if task.status in TERMINAL_STATES:
                return
            if asyncio.get_event_loop().time() > deadline:
                return  # client reconnects; Last-Event-ID continues the stream
            await asyncio.sleep(0.25)

    return StreamingResponse(
        stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@router.post("/worker/tick")
async def worker_tick(request: Request, ticks: int = Query(default=1, ge=1, le=50)) -> dict:
    """Cron-callable worker tick for hosting that sleeps idle apps.
    Guarded by X-Worker-Token when MINA_WORKER_TOKEN is configured."""
    import os

    expected = getattr(request.app.state.settings, "worker_token", None)
    # Fail closed: without a configured token this endpoint can trigger real
    # task executions, so it must never run unauthenticated.
    if not expected or request.headers.get("x-worker-token") != expected:
        raise HTTPException(status_code=403, detail="worker token required")
    worker = _worker(request)
    summaries = []
    async with request.app.state.database.session() as db:
        for _ in range(ticks):
            summaries.append(await worker.run_once(db))
    return {"ticks": summaries}
