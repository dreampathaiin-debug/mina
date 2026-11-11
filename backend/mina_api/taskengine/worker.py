"""The background worker: claims durable tasks and drives their
handlers to completion.

Design rules:
- A worker never trusts the UI's word that a task is still its: every
  write is lease-guarded, so a worker that crashed or lost its lease
  cannot corrupt a task another worker now owns.
- Handlers run step by step and checkpoint after each completed step,
  so a crash resumes from the last commit instead of repeating
  non-idempotent external actions.
- Cancellation, pause and approval are cooperative: they take effect
  at the next checkpoint.
- The worker loop is NOT a FastAPI BackgroundTask: it is a standalone
  loop (in-process when hosting allows it, or driven by an external
  cron hitting the tick endpoint when the platform sleeps idle apps).
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any, Awaitable, Callable

from mina_api.taskengine.engine import TaskEngine, utcnow
from mina_api.taskengine.errors import (
    PermanentTaskError,
    SimulatedWorkerCrash,
    TaskApprovalRequested,
    TaskCancelled,
    TaskPaused,
    TransientTaskError,
)
from mina_api.taskengine.states import TaskState

logger = logging.getLogger("mina.worker")


class TaskContext:
    """What a handler gets: the payload, its restored checkpoint, and
    safe ways to record progress or hand control back to the engine."""

    def __init__(self, engine: TaskEngine, db, task, worker_id: str) -> None:
        self.engine = engine
        self.db = db
        self.task = task
        self.worker_id = worker_id
        self._checkpoint: dict = dict(task.checkpoint or {})
        self._completed: list[str] = list(self._checkpoint.get("completed_steps", []))
        self._step_data: dict = dict(self._checkpoint.get("step_data", {}))
        self._cancelled = bool(task.cancel_requested)
        self._paused = bool(task.pause_requested)

    @property
    def payload(self) -> dict:
        return self.task.payload or {}

    @property
    def checkpoint(self) -> dict:
        return dict(self._checkpoint)

    def done(self, step: str) -> bool:
        """True when this step already completed before a crash: skip
        it, never repeat a non-idempotent external action."""

        return step in self._completed

    async def check_signals(self) -> None:
        """Re-read the task row and surface cancel/pause. Re-reading is
        what makes cancellation work even though the handler holds its
        own stale copy."""
        fresh = await self.engine.get_owned(self.db, self.task.user_id, self.task.id)
        if fresh.cancel_requested:
            raise TaskCancelled()
        if fresh.pause_requested:
            raise TaskPaused()
        if fresh.status not in {TaskState.RUNNING, TaskState.PLANNING}:
            raise TaskPaused()  # lost the task to someone else; stop touching it

    async def enter_planning(self) -> None:
        self.task = await self.engine.save_checkpoint(
            self.db, self.task.id, self.worker_id,
            checkpoint=self._checkpoint, status=TaskState.PLANNING,
            event_type="task_planning",
        )

    async def step(self, name: str, data: dict | None = None, *, progress: dict | None = None) -> None:
        """Complete one durable step: checkpoint it to the database so
        a crash never repeats it, then renew the lease."""
        if name in self._completed:
            return
        self._completed.append(name)
        self._step_data[name] = data or {}
        self._checkpoint = {
            "completed_steps": self._completed,
            "step_data": self._step_data,
            "last_step": name,
        }
        self.task = await self.engine.save_checkpoint(
            self.db, self.task.id, self.worker_id,
            checkpoint=self._checkpoint, progress=progress,
        )
        await self.engine.renew_lease(self.db, self.task.id, self.worker_id)
        await self.check_signals()

    async def set_progress(self, progress: dict) -> None:
        self.task = await self.engine.save_checkpoint(
            self.db, self.task.id, self.worker_id,
            checkpoint=self._checkpoint, progress=progress,
            event_type="task_progress",
        )
        await self.check_signals()

    async def request_approval(self) -> None:
        raise TaskApprovalRequested()

    async def crash(self) -> None:
        """Test hook: die exactly like a killed process would."""
        raise SimulatedWorkerCrash()


Handler = Callable[[TaskContext], Awaitable[Any]]


class TaskWorker:
    def __init__(
        self,
        engine: TaskEngine,
        handlers: dict[str, Handler] | None = None,
        *,
        worker_id: str = "worker",
        poll_seconds: float = 1.0,
    ) -> None:
        self.engine = engine
        self.handlers: dict[str, Handler] = dict(handlers or {})
        self.worker_id = worker_id
        self.poll_seconds = poll_seconds

    def register(self, kind: str, handler: Handler) -> None:
        self.handlers[kind] = handler

    async def run_once(self, db) -> dict:
        """One polling cycle: recover crashed tasks, claim one task,
        run it. Returns a summary for tests and the tick endpoint."""
        summary: dict[str, Any] = {"worker": self.worker_id, "recovered": 0, "claimed": 0, "outcome": None}
        recovered = await self.engine.recover_stale(db)
        summary["recovered"] = len(recovered)

        task = await self.engine.claim_next(db, worker_id=self.worker_id)
        if task is None:
            summary["outcome"] = "idle"
            return summary
        summary["claimed"] = 1
        summary["task_id"] = str(task.id)
        summary["outcome"] = await self.execute_claimed(db, task)
        return summary

    async def execute_claimed(self, db, task) -> str:
        """Run one attempt of an already-claimed task. The engine, not
        the handler, owns every state transition. The worker acts under
        the identity that holds the lease, and reloads the row in the
        session it is given, so the task may arrive from another
        session or process."""
        task = await self.engine.reload(db, task.id)
        worker_id = task.lease_owner or self.worker_id
        handler = self.handlers.get(task.kind)
        if handler is None:
            await self.engine.fail_task(
                db, task, worker_id,
                f"no handler registered for task kind '{task.kind}'",
                transient=False,
            )
            return "failed_no_handler"

        ctx = TaskContext(self.engine, db, task, worker_id)
        try:
            result = await handler(ctx)
            await self.engine.complete_task(db, task, worker_id, result=None if result is None else str(result))
            return "completed"
        except TaskCancelled:
            await self.engine.finalize_cancel(db, task, worker_id)
            return "cancelled"
        except TaskPaused:
            await self.engine.finalize_pause(db, task, worker_id)
            return "paused"
        except TaskApprovalRequested:
            await self.engine.request_approval(db, task, worker_id)
            return "waiting_approval"
        except TransientTaskError as exc:
            await self.engine.fail_task(db, task, worker_id, str(exc), transient=True)
            # The engine decides: retry or dead-letter when the attempt
            # budget is spent.
            return "retrying" if task.status == TaskState.RETRYING else "failed"
        except (PermanentTaskError, Exception) as exc:  # noqa: BLE001
            await self.engine.fail_task(db, task, worker_id, f"{type(exc).__name__}: {exc}", transient=False)
            return "failed"
        # SimulatedWorkerCrash (BaseException) escapes on purpose: the
        # row stays RUNNING under its lease, like a real dead worker,
        # until lease expiry recovery picks it up.

    async def run_forever(self, session_factory) -> None:
        """The long-running loop. One short database session per tick:
        sessions never outlive their work, so a crash between ticks
        leaves no dangling state."""
        logger.info("task worker %s started", self.worker_id)
        try:
            while True:
                try:
                    async with session_factory() as db:
                        await self.run_once(db)
                except asyncio.CancelledError:
                    raise
                except Exception:  # noqa: BLE001 - the loop must survive
                    logger.exception("task worker tick failed")
                await asyncio.sleep(self.poll_seconds)
        except asyncio.CancelledError:
            logger.info("task worker %s stopped", self.worker_id)
            raise
