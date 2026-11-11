"""The durable task engine: persistent task state, atomic claiming,
worker leases, retries with backoff + jitter, dead-lettering, event
log, cancellation/pause/approval, crash recovery and per-user
concurrency limits.

Everything here is durable state in the database. A worker process can
die at any point; a new one picks tasks up from the last committed
checkpoint without redoing completed external actions.
"""

from __future__ import annotations

import random
from datetime import datetime, timedelta, timezone
from uuid import UUID

from sqlalchemy import delete, func, select, update

from mina_api.database.models import DurableTask, TaskEvent
from mina_api.taskengine.errors import PermanentTaskError
from mina_api.taskengine.states import (
    CLAIMABLE_STATES,
    TERMINAL_STATES,
    TaskState,
)


class TaskOwnershipError(Exception):
    """The task does not exist or belongs to another user."""


class TaskStateError(Exception):
    """The requested transition is not valid from the current state."""


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def as_utc(value: datetime | None) -> datetime | None:
    if value is None:
        return None
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value


class TaskEngine:
    """Operations on durable tasks. Stateless beyond its retry tuning;
    every call takes the database session it should run on."""

    def __init__(
        self,
        *,
        lease_seconds: float = 60.0,
        backoff_base_seconds: float = 5.0,
        backoff_cap_seconds: float = 3600.0,
        jitter_fraction: float = 0.3,
        per_user_concurrency: int = 3,
    ) -> None:
        self.lease_seconds = lease_seconds
        self.backoff_base_seconds = backoff_base_seconds
        self.backoff_cap_seconds = backoff_cap_seconds
        self.jitter_fraction = jitter_fraction
        self.per_user_concurrency = per_user_concurrency

    # ---- creation ------------------------------------------------------

    async def create_task(
        self,
        db,
        user_id: UUID,
        *,
        kind: str,
        title: str,
        payload: dict | None = None,
        idempotency_key: str | None = None,
        scheduled_at: datetime | None = None,
        requires_approval: bool = False,
        max_attempts: int = 3,
    ) -> tuple[DurableTask, bool]:
        """Create a task. With an idempotency key, a repeat submission
        returns the existing row and creates nothing, so a client that
        retries a request can never trigger the same external action
        twice. Returns (task, created)."""
        payload = payload or {}
        if idempotency_key:
            existing = (
                await db.execute(
                    select(DurableTask).where(
                        DurableTask.user_id == user_id,
                        DurableTask.idempotency_key == idempotency_key,
                    )
                )
            ).scalar_one_or_none()
            if existing is not None:
                return existing, False

        now = utcnow()
        run_after = as_utc(scheduled_at) or now
        status = (
            TaskState.WAITING_FOR_APPROVAL
            if requires_approval
            else TaskState.QUEUED
        )
        task = DurableTask(
            user_id=user_id,
            kind=kind,
            title=title,
            payload=payload,
            status=status,
            idempotency_key=idempotency_key,
            max_attempts=max_attempts,
            scheduled_at=as_utc(scheduled_at),
            run_after=run_after,
        )
        db.add(task)
        await db.flush()
        if requires_approval:
            await self._event(db, task, "approval_required", {"reason": "task created requiring approval"})
        else:
            await self._event(db, task, "task_queued", {"scheduled": scheduled_at is not None})
        await db.commit()
        await db.refresh(task)
        return task, True

    # ---- claiming and leases --------------------------------------------

    async def claim_next(
        self,
        db,
        *,
        worker_id: str,
        lease_seconds: float | None = None,
        per_user_concurrency: int | None = None,
    ) -> DurableTask | None:
        """Atomically claim one runnable task for a worker.

        The claim is a single UPDATE whose WHERE selects the candidate
        by id inside the same statement, so two workers racing on one
        task cannot both win: the second finds the row already RUNNING
        with a fresh lease. A task is claimable when it is QUEUED or
        RETRYING, its run_after has passed, and it holds no live lease
        (a live lease belongs to a worker that may still be alive).
        """
        lease = lease_seconds if lease_seconds is not None else self.lease_seconds
        concurrency = per_user_concurrency or self.per_user_concurrency
        now = utcnow()
        tried: set = set()

        for _ in range(8):  # a handful of candidates per tick is plenty
            conditions = [
                DurableTask.status.in_([s.value for s in CLAIMABLE_STATES]),
                (DurableTask.run_after.is_(None)) | (DurableTask.run_after <= now),
                (DurableTask.lease_expires_at.is_(None)) | (DurableTask.lease_expires_at <= now),
            ]
            if tried:
                conditions.append(DurableTask.id.not_in(tried))
            candidate_id = (
                await db.execute(
                    select(DurableTask.id)
                    .where(*conditions)
                    .order_by(DurableTask.run_after.asc().nullsfirst(), DurableTask.created_at.asc())
                    .limit(1)
                )
            ).scalar_one_or_none()
            if candidate_id is None:
                return None

            claimed = await db.execute(
                update(DurableTask)
                .where(
                    DurableTask.id == candidate_id,
                    DurableTask.status.in_([s.value for s in CLAIMABLE_STATES]),
                    (DurableTask.lease_expires_at.is_(None)) | (DurableTask.lease_expires_at <= now),
                )
                .values(
                    status=TaskState.RUNNING,
                    lease_owner=worker_id,
                    lease_expires_at=now + timedelta(seconds=lease),
                    started_at=func.coalesce(DurableTask.started_at, now),
                    updated_at=now,
                )
                .execution_options(synchronize_session=False)
            )
            if claimed.rowcount == 0:
                tried.add(candidate_id)
                continue  # someone else won the race; try the next candidate

            task = await self._reload(db, candidate_id)

            # Per-user concurrency: a user never has more than the
            # limit of live tasks running at once.
            live = (
                await db.execute(
                    select(func.count())
                    .select_from(DurableTask)
                    .where(
                        DurableTask.user_id == task.user_id,
                        DurableTask.status.in_([TaskState.RUNNING, TaskState.PLANNING]),
                        DurableTask.lease_expires_at > now,
                    )
                )
            ).scalar_one()
            if live > concurrency:
                await db.execute(
                    update(DurableTask)
                    .where(DurableTask.id == task.id)
                    .values(status=TaskState.QUEUED, lease_owner=None, lease_expires_at=None, updated_at=utcnow())
                    .execution_options(synchronize_session=False)
                )
                await db.commit()
                tried.add(task.id)
                continue

            await self._event(db, task, "task_started", {"worker": worker_id, "attempt": task.attempts + 1})
            await db.commit()
            await db.refresh(task)
            return task
        return None

    async def claim_by_id(self, db, task_id: UUID, *, worker_id: str, lease_seconds: float | None = None) -> DurableTask | None:
        """Claim one specific task if it is still claimable; None when
        another worker holds it or the state forbids claiming."""
        lease = lease_seconds if lease_seconds is not None else self.lease_seconds
        now = utcnow()
        claimed = await db.execute(
            update(DurableTask)
            .where(
                DurableTask.id == task_id,
                DurableTask.status.in_([s.value for s in CLAIMABLE_STATES]),
                (DurableTask.lease_expires_at.is_(None)) | (DurableTask.lease_expires_at <= now),
            )
            .values(
                status=TaskState.RUNNING,
                lease_owner=worker_id,
                lease_expires_at=now + timedelta(seconds=lease),
                started_at=func.coalesce(DurableTask.started_at, now),
                updated_at=now,
            )
            .execution_options(synchronize_session=False)
        )
        if (claimed.rowcount or 0) == 0:
            return None
        task = await self._reload(db, task_id)
        await self._event(db, task, "task_started", {"worker": worker_id, "attempt": task.attempts + 1})
        await db.commit()
        await db.refresh(task)
        return task

    async def renew_lease(
        self, db, task_id: UUID, worker_id: str, *, lease_seconds: float | None = None
    ) -> bool:
        """Extend the lease. Only the current lease holder can renew;
        a worker that lost its lease (crashed, or recovered elsewhere)
        must stop touching the task."""
        lease = lease_seconds if lease_seconds is not None else self.lease_seconds
        now = utcnow()
        result = await db.execute(
            update(DurableTask)
            .where(
                DurableTask.id == task_id,
                DurableTask.lease_owner == worker_id,
                DurableTask.status.in_([TaskState.RUNNING, TaskState.PLANNING]),
                DurableTask.lease_expires_at > now,
            )
            .values(lease_expires_at=now + timedelta(seconds=lease), updated_at=now)
            .execution_options(synchronize_session=False)
        )
        await db.commit()
        return (result.rowcount or 0) > 0

    async def release_lease(self, db, task_id: UUID, worker_id: str) -> None:
        await db.execute(
            update(DurableTask)
            .where(DurableTask.id == task_id, DurableTask.lease_owner == worker_id)
            .values(lease_owner=None, lease_expires_at=None, updated_at=utcnow())
        )
        await db.commit()

    # ---- checkpoints and progress ---------------------------------------

    async def save_checkpoint(
        self,
        db,
        task_id: UUID,
        worker_id: str,
        *,
        checkpoint: dict,
        progress: dict | None = None,
        status: str | None = None,
        event_type: str = "task_checkpoint",
    ) -> DurableTask:
        """Persist progress after a completed step. Guarded by the
        lease: only the worker that owns the task may write its state."""
        now = utcnow()
        result = await db.execute(
            update(DurableTask)
            .where(
                DurableTask.id == task_id,
                DurableTask.lease_owner == worker_id,
                DurableTask.status.in_([TaskState.RUNNING, TaskState.PLANNING]),
            )
            .values(
                checkpoint=checkpoint,
                progress=progress,
                updated_at=now,
                **({"status": status} if status else {}),
            )
        )
        if (result.rowcount or 0) == 0:
            raise TaskOwnershipError("task is not owned by this worker")
        task = await self._reload(db, task_id)
        await self._event(db, task, event_type, {"checkpoint": checkpoint, "progress": progress or {}})
        await db.commit()
        await db.refresh(task)
        return task

    # ---- completion, failure, retry, dead-letter -------------------------

    async def complete_task(self, db, task: DurableTask, worker_id: str, result: str | None = None) -> DurableTask:
        now = utcnow()
        task.status = TaskState.COMPLETED
        task.result = result
        task.error = None
        task.finished_at = now
        task.lease_owner = None
        task.lease_expires_at = None
        task.updated_at = now
        await self._event(db, task, "task_completed", {"result": (result or "")[:500]})
        await db.commit()
        await db.refresh(task)
        return task

    async def fail_task(
        self, db, task: DurableTask, worker_id: str, error: str, *, transient: bool = True
    ) -> DurableTask:
        """Record a failure. Transient failures retry with exponential
        backoff and jitter; permanent ones fail immediately. When the
        attempt budget is spent the task dead-letters: status FAILED,
        no further retries, a dead-letter event for observability."""
        now = utcnow()
        task.attempts = (task.attempts or 0) + 1
        task.lease_owner = None
        task.lease_expires_at = None
        task.error = (error or "")[:2000]
        task.updated_at = now

        if not transient or isinstance(error, PermanentTaskError) or task.attempts >= task.max_attempts:
            task.status = TaskState.FAILED
            task.finished_at = now
            await self._event(
                db, task, "task_dead_letter" if task.attempts >= task.max_attempts else "task_failed",
                {"error": task.error, "attempts": task.attempts},
            )
        else:
            delay = min(
                self.backoff_base_seconds * (2 ** (task.attempts - 1)),
                self.backoff_cap_seconds,
            )
            delay += random.uniform(0, delay * self.jitter_fraction)
            task.status = TaskState.RETRYING
            task.run_after = now + timedelta(seconds=delay)
            await self._event(
                db, task, "task_retrying",
                {"error": task.error, "attempt": task.attempts, "retry_in_seconds": round(delay, 3)},
            )
        await db.commit()
        await db.refresh(task)
        return task

    # ---- cancellation, pause, approval ----------------------------------

    async def cancel_task(self, db, user_id: UUID, task_id: UUID) -> DurableTask:
        """User cancellation. Running tasks stop cooperatively at the
        next checkpoint (cancel_requested); everything else cancels at
        once. Terminal tasks refuse."""
        task = await self.get_owned(db, user_id, task_id)
        if task.status in TERMINAL_STATES:
            raise TaskStateError(f"task is already {task.status}")
        now = utcnow()
        if task.status in {TaskState.RUNNING, TaskState.PLANNING}:
            task.cancel_requested = True
            task.updated_at = now
            await self._event(db, task, "task_cancel_requested", {})
        else:
            task.status = TaskState.CANCELLED
            task.finished_at = now
            task.lease_owner = None
            task.lease_expires_at = None
            await self._event(db, task, "task_cancelled", {})
        await db.commit()
        await db.refresh(task)
        return task

    async def finalize_cancel(self, db, task: DurableTask, worker_id: str) -> DurableTask:
        now = utcnow()
        task.status = TaskState.CANCELLED
        task.cancel_requested = False
        task.finished_at = now
        task.lease_owner = None
        task.lease_expires_at = None
        task.updated_at = now
        await self._event(db, task, "task_cancelled", {})
        await db.commit()
        await db.refresh(task)
        return task

    async def pause_task(self, db, user_id: UUID, task_id: UUID) -> DurableTask:
        task = await self.get_owned(db, user_id, task_id)
        if task.status in TERMINAL_STATES:
            raise TaskStateError(f"task is already {task.status}")
        now = utcnow()
        if task.status in {TaskState.RUNNING, TaskState.PLANNING}:
            task.pause_requested = True
            await self._event(db, task, "task_pause_requested", {})
        else:
            task.status = TaskState.PAUSED
            task.lease_owner = None
            task.lease_expires_at = None
            await self._event(db, task, "task_paused", {})
        task.updated_at = now
        await db.commit()
        await db.refresh(task)
        return task

    async def finalize_pause(self, db, task: DurableTask, worker_id: str) -> DurableTask:
        now = utcnow()
        task.status = TaskState.PAUSED
        task.pause_requested = False
        task.lease_owner = None
        task.lease_expires_at = None
        task.updated_at = now
        await self._event(db, task, "task_paused", {})
        await db.commit()
        await db.refresh(task)
        return task

    async def resume_task(self, db, user_id: UUID, task_id: UUID) -> DurableTask:
        task = await self.get_owned(db, user_id, task_id)
        if task.status != TaskState.PAUSED:
            raise TaskStateError(f"task is {task.status}, only PAUSED tasks can resume")
        now = utcnow()
        task.status = TaskState.QUEUED
        task.run_after = now
        task.pause_requested = False
        task.updated_at = now
        await self._event(db, task, "task_resumed", {})
        await db.commit()
        await db.refresh(task)
        return task

    async def approve_task(self, db, user_id: UUID, task_id: UUID, *, approved: bool = True) -> DurableTask:
        task = await self.get_owned(db, user_id, task_id)
        if task.status != TaskState.WAITING_FOR_APPROVAL:
            raise TaskStateError(f"task is {task.status}, not waiting for approval")
        now = utcnow()
        if approved:
            task.status = TaskState.QUEUED
            task.run_after = now
            await self._event(db, task, "task_approved", {})
        else:
            task.status = TaskState.CANCELLED
            task.finished_at = now
            await self._event(db, task, "task_cancelled", {"reason": "approval declined"})
        task.updated_at = now
        await db.commit()
        await db.refresh(task)
        return task

    async def request_approval(self, db, task: DurableTask, worker_id: str) -> DurableTask:
        """A running handler needs the user's go-ahead: the lease is
        released so no worker keeps the task hostage."""
        now = utcnow()
        task.status = TaskState.WAITING_FOR_APPROVAL
        task.lease_owner = None
        task.lease_expires_at = None
        task.updated_at = now
        await self._event(db, task, "approval_required", {})
        await db.commit()
        await db.refresh(task)
        return task

    # ---- crash recovery ---------------------------------------------------

    async def recover_stale(self, db, *, now: datetime | None = None) -> list[UUID]:
        """Requeue tasks whose worker died: RUNNING (or PLANNING) rows
        whose lease expired. Attempts are not burned by a crash, only
        by failures. Returns the recovered ids."""
        now = now or utcnow()
        rows = (
            await db.execute(
                select(DurableTask).where(
                    DurableTask.status.in_([TaskState.RUNNING, TaskState.PLANNING]),
                    (DurableTask.lease_expires_at.is_(None)) | (DurableTask.lease_expires_at <= now),
                )
            )
        ).scalars().all()
        recovered: list[UUID] = []
        for task in rows:
            task.status = TaskState.QUEUED
            task.run_after = now
            task.lease_owner = None
            task.lease_expires_at = None
            task.updated_at = now
            await self._event(db, task, "task_recovered", {"reason": "worker lease expired"})
            recovered.append(task.id)
        if recovered:
            await db.commit()
        return recovered

    # ---- reads ------------------------------------------------------------

    async def get_owned(self, db, user_id: UUID, task_id: UUID) -> DurableTask:
        task = await db.get(DurableTask, task_id)
        if task is None or task.user_id != user_id:
            raise TaskOwnershipError("task not found")
        return task

    async def list_tasks(self, db, user_id: UUID, *, status: str | None = None, kind: str | None = None, limit: int = 100) -> list[DurableTask]:
        query = select(DurableTask).where(DurableTask.user_id == user_id)
        if status:
            query = query.where(DurableTask.status == status)
        if kind:
            query = query.where(DurableTask.kind == kind)
        rows = (
            await db.execute(query.order_by(DurableTask.created_at.desc()).limit(min(limit, 500)))
        ).scalars().all()
        return rows

    async def events_after(
        self, db, user_id: UUID, task_id: UUID, *, after_seq: int = 0, limit: int = 200
    ) -> list[TaskEvent]:
        await self.get_owned(db, user_id, task_id)
        rows = (
            await db.execute(
                select(TaskEvent)
                .where(TaskEvent.task_id == task_id, TaskEvent.seq > after_seq)
                .order_by(TaskEvent.seq.asc())
                .limit(min(limit, 500))
            )
        ).scalars().all()
        return rows

    # ---- internals ---------------------------------------------------------

    async def reload(self, db, task_id: UUID) -> DurableTask:
        """Fresh copy of the row in this session (safe with objects
        that arrived from another session or a crashed process)."""
        return await self._reload(db, task_id)

    async def _reload(self, db, task_id: UUID) -> DurableTask:
        return (
            await db.execute(
                select(DurableTask).where(DurableTask.id == task_id).execution_options(populate_existing=True)
            )
        ).scalar_one()

    async def _event(self, db, task: DurableTask, type_: str, data: dict) -> TaskEvent:
        max_seq = (
            await db.execute(select(func.max(TaskEvent.seq)).where(TaskEvent.task_id == task.id))
        ).scalar_one()
        seq = (max_seq or 0) + 1
        event = TaskEvent(task_id=task.id, user_id=task.user_id, seq=seq, type=type_, data=data)
        db.add(event)
        await db.flush()
        return event
