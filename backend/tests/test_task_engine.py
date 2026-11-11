"""Phase 9 tests: durable background task engine.

Covers atomic claiming, leases and renewal, crash recovery from
checkpoints, retry with backoff + jitter, dead-lettering, idempotency,
scheduled execution, cancellation, pause/resume, approval waiting,
progress events with SSE reconnection, per-user concurrency limits and
the Android API surface.
"""

from __future__ import annotations

import asyncio
from datetime import timedelta
from uuid import uuid4

import pytest

from mina_api.taskengine.engine import (
    TaskEngine,
    TaskOwnershipError,
    TaskStateError,
    as_utc,
    utcnow,
)
from mina_api.taskengine.errors import (
    PermanentTaskError,
    SimulatedWorkerCrash,
    TransientTaskError,
)
from mina_api.taskengine.states import TaskState
from mina_api.taskengine.worker import TaskWorker
from tests.conftest import headers_for


def _engine(app):
    return app.state.task_engine


def _worker(app):
    return app.state.worker


def _run(app, coro_fn):
    async def _go():
        async with app.state.database.session() as session:
            return await coro_fn(session)

    return asyncio.run(_go())


def _create(app, user, title="task", *, kind="noop", **kwargs):
    async def _go(db):
        return await _engine(app).create_task(db, user, kind=kind, title=title, **kwargs)

    return _run(app, _go)


def _register_sideeffect(app, store):
    async def handler(ctx):
        if not ctx.done("send_email"):
            store["sent"] = store.get("sent", 0) + 1
            await ctx.step("send_email", {"to": ctx.payload.get("to", "x")})
        if store.pop("crash_now", False):
            await ctx.crash()
        if store.pop("transient_now", False):
            raise TransientTaskError("rate limited")
        if store.pop("permanent_now", False):
            raise PermanentTaskError("bad request")
        await ctx.step("finish")
        return "ok"

    _worker(app).register("sideeffect", handler)



def _expire_lease(app, user, task):
    async def _go(db):
        row = await _engine(app).get_owned(db, user, task.id)
        row.lease_expires_at = utcnow() - timedelta(seconds=1)
        await db.commit()

    _run(app, _go)

# ---------------------------------------------------------------- claiming

def test_claim_is_atomic_two_workers_never_share(app, user_a):
    task, _ = _create(app, user_a)
    claimed = _run(app, lambda db: _engine(app).claim_next(db, worker_id="w1"))
    assert claimed is not None and claimed.id == task.id
    # The task is now RUNNING under w1's lease: a second worker that
    # races for work gets nothing.
    second = _run(app, lambda db: _engine(app).claim_next(db, worker_id="w2"))
    assert second is None


def test_per_user_concurrency_limit(app, user_a, user_b):
    engine = TaskEngine(lease_seconds=30, per_user_concurrency=2)

    async def _make_three(db):
        made = []
        for _ in range(3):
            task, _ = await engine.create_task(db, user_a, kind="noop", title="t")
            made.append(task)
        return made

    _run(app, _make_three)
    first = _run(app, lambda db: engine.claim_next(db, worker_id="w1"))
    second = _run(app, lambda db: engine.claim_next(db, worker_id="w2"))
    third = _run(app, lambda db: engine.claim_next(db, worker_id="w3"))
    assert first is not None and second is not None
    assert third is None, "user A hit the per-user running limit"

    # User B is unaffected by A's limit.
    async def _make_b(db):
        task, _ = await engine.create_task(db, user_b, kind="noop", title="b")
        return task

    _run(app, _make_b)
    got_b = _run(app, lambda db: engine.claim_next(db, worker_id="w1"))
    assert got_b is not None and got_b.user_id == user_b


def test_lease_renewal_only_by_holder(app, user_a):
    task, _ = _create(app, user_a)
    claimed = _run(app, lambda db: _engine(app).claim_next(db, worker_id="w1", lease_seconds=30))
    assert claimed.lease_owner == "w1"
    ok = _run(app, lambda db: _engine(app).renew_lease(db, task.id, "w1", lease_seconds=120))
    assert ok is True
    refreshed = _run(app, lambda db: _engine(app).get_owned(db, user_a, task.id))
    assert as_utc(refreshed.lease_expires_at) > utcnow() + timedelta(seconds=90)
    foreign = _run(app, lambda db: _engine(app).renew_lease(db, task.id, "w2", lease_seconds=120))
    assert foreign is False, "a worker that does not own the lease cannot renew it"


# ---------------------------------------------------------------- crash recovery

def test_crash_recovery_resumes_from_checkpoint_without_repeating(app, user_a):
    store = {"sent": 0, "crash_now": True}
    _register_sideeffect(app, store)
    task, _ = _create(app, user_a, kind="sideeffect")

    async def _run_and_crash(db):
        claimed = await _engine(app).claim_next(db, worker_id="w1", lease_seconds=0.1)
        try:
            await _worker(app).execute_claimed(db, claimed)
        except SimulatedWorkerCrash:
            return "crashed"
        return "no-crash"

    assert _run(app, _run_and_crash) == "crashed"
    # The external side effect happened exactly once and is checkpointed.
    row = _run(app, lambda db: _engine(app).get_owned(db, user_a, task.id))
    assert row.status == TaskState.RUNNING
    assert store["sent"] == 1
    assert "send_email" in row.checkpoint["completed_steps"]

    # The worker process is gone; its lease expires and a fresh worker
    # recovers the task without repeating the send.
    _expire_lease(app, user_a, task)
    recovered = _run(app, lambda db: _engine(app).recover_stale(db))
    assert task.id in recovered
    resumed = _run(app, lambda db: _engine(app).claim_next(db, worker_id="w2", lease_seconds=30))
    assert resumed.id == task.id
    outcome = _run(app, lambda db: _worker(app).execute_claimed(db, resumed))
    assert outcome == "completed"
    assert store["sent"] == 1, "the non-idempotent action must never repeat"


def test_worker_restart_durability(app, user_a):
    """A brand-new worker object over the same database finishes what
    the old process left behind."""
    store = {"sent": 0, "crash_now": True}
    _register_sideeffect(app, store)
    task, _ = _create(app, user_a, kind="sideeffect")

    async def _crash(db):
        claimed = await _engine(app).claim_next(db, worker_id="old-process", lease_seconds=0.1)
        try:
            await _worker(app).execute_claimed(db, claimed)
        except SimulatedWorkerCrash:
            return None

    _run(app, _crash)
    _expire_lease(app, user_a, task)
    fresh_worker = TaskWorker(_engine(app), dict(_worker(app).handlers), worker_id="restarted")
    summary = _run(app, lambda db: fresh_worker.run_once(db))
    assert summary["recovered"] == 1
    assert summary["outcome"] == "completed"
    row = _run(app, lambda db: _engine(app).get_owned(db, user_a, task.id))
    assert row.status == TaskState.COMPLETED
    assert store["sent"] == 1


# ---------------------------------------------------------------- retries

def test_transient_failure_retries_with_backoff_and_jitter(app, user_a):
    store = {"transient_now": True}
    _register_sideeffect(app, store)
    app.state.task_engine.backoff_base_seconds = 1.0
    app.state.task_engine.backoff_cap_seconds = 60
    engine = app.state.task_engine
    task, _ = _create(app, user_a, kind="sideeffect")

    async def _go(db):
        claimed = await engine.claim_next(db, worker_id="w1")
        return await _worker(app).execute_claimed(db, claimed)

    outcome = _run(app, _go)
    assert outcome == "retrying"
    row = _run(app, lambda db: engine.get_owned(db, user_a, task.id))
    assert row.status == TaskState.RETRYING
    assert row.attempts == 1
    delay = (as_utc(row.run_after) - utcnow()).total_seconds()
    assert 0.9 <= delay <= 1.4, f"backoff 1s + up to 30% jitter, got {delay}"


def test_dead_letter_after_max_attempts(app, user_a):
    store = {"transient_now": True}
    _register_sideeffect(app, store)
    app.state.task_engine.backoff_base_seconds = 0.5
    app.state.task_engine.backoff_cap_seconds = 10
    engine = app.state.task_engine
    task, _ = _create(app, user_a, kind="sideeffect", max_attempts=2)

    async def _one(db):
        claimed = await engine.claim_next(db, worker_id="w1")
        if claimed is None:
            return "idle"
        return await _worker(app).execute_claimed(db, claimed)

    async def _expire(db):
        row = await engine.get_owned(db, user_a, task.id)
        row.run_after = utcnow() - timedelta(seconds=1)
        await db.commit()

    assert _run(app, _one) == "retrying"
    _run(app, _expire)
    store["transient_now"] = True
    assert _run(app, _one) == "failed"
    row = _run(app, lambda db: engine.get_owned(db, user_a, task.id))
    assert row.status == TaskState.FAILED
    assert row.attempts == 2
    events = _run(app, lambda db: engine.events_after(db, user_a, task.id))
    assert any(e.type == "task_dead_letter" for e in events)


def test_permanent_failure_fails_immediately(app, user_a):
    store = {"permanent_now": True}
    _register_sideeffect(app, store)
    task, _ = _create(app, user_a, kind="sideeffect", max_attempts=5)

    async def _go(db):
        claimed = await _engine(app).claim_next(db, worker_id="w1")
        return await _worker(app).execute_claimed(db, claimed)

    assert _run(app, _go) == "failed"
    row = _run(app, lambda db: _engine(app).get_owned(db, user_a, task.id))
    assert row.status == TaskState.FAILED
    assert row.attempts == 1, "permanent failures do not retry"


def test_unknown_kind_dead_letters_not_hangs(app, user_a):
    task, _ = _create(app, user_a, kind="no_such_handler")

    async def _go(db):
        claimed = await _engine(app).claim_next(db, worker_id="w1")
        return await _worker(app).execute_claimed(db, claimed)

    assert _run(app, _go) == "failed_no_handler"
    row = _run(app, lambda db: _engine(app).get_owned(db, user_a, task.id))
    assert row.status == TaskState.FAILED


# ---------------------------------------------------------------- idempotency

def test_idempotency_key_never_creates_duplicates(app, user_a):
    first, created1 = _create(app, user_a, idempotency_key="job-42")
    second, created2 = _create(app, user_a, idempotency_key="job-42")
    assert created1 is True and created2 is False
    assert first.id == second.id
    other, created3 = _create(app, user_a, idempotency_key="job-43")
    assert created3 is True and other.id != first.id


# ---------------------------------------------------------------- scheduling

def test_scheduled_task_runs_only_after_its_time(app, user_a):
    future = utcnow() + timedelta(hours=1)
    task, _ = _create(app, user_a, scheduled_at=future)
    none = _run(app, lambda db: _engine(app).claim_next(db, worker_id="w1"))
    assert none is None, "a future-scheduled task must not be claimed early"

    async def _backdate(db):
        row = await _engine(app).get_owned(db, user_a, task.id)
        row.run_after = utcnow() - timedelta(seconds=1)
        await db.commit()

    _run(app, _backdate)
    claimed = _run(app, lambda db: _engine(app).claim_next(db, worker_id="w1"))
    assert claimed is not None and claimed.id == task.id


# ---------------------------------------------------------------- control

def test_cancel_queued_and_terminal_refusal(app, user_a):
    task, _ = _create(app, user_a)
    row = _run(app, lambda db: _engine(app).cancel_task(db, user_a, task.id))
    assert row.status == TaskState.CANCELLED
    claimed = _run(app, lambda db: _engine(app).claim_next(db, worker_id="w1"))
    assert claimed is None
    with pytest.raises(TaskStateError):
        _run(app, lambda db: _engine(app).cancel_task(db, user_a, task.id))


def test_cancel_running_is_cooperative(app, user_a):
    store = {"cancel_after_send": True}

    async def handler(ctx):
        if not ctx.done("send_email"):
            await ctx.step("send_email")
        store.pop("cancel_after_send", None)
        await ctx.check_signals()
        await ctx.step("finish")
        return "ok"

    _worker(app).register("cooperative", handler)
    task, _ = _create(app, user_a, kind="cooperative")

    async def _go(db):
        claimed = await _engine(app).claim_next(db, worker_id="w1", lease_seconds=30)
        await _engine(app).cancel_task(db, user_a, task.id)
        return await _worker(app).execute_claimed(db, claimed)

    assert _run(app, _go) == "cancelled"
    row = _run(app, lambda db: _engine(app).get_owned(db, user_a, task.id))
    assert row.status == TaskState.CANCELLED


def test_pause_and_resume(app, user_a):
    task, _ = _create(app, user_a)
    row = _run(app, lambda db: _engine(app).pause_task(db, user_a, task.id))
    assert row.status == TaskState.PAUSED
    assert _run(app, lambda db: _engine(app).claim_next(db, worker_id="w1")) is None
    row = _run(app, lambda db: _engine(app).resume_task(db, user_a, task.id))
    assert row.status == TaskState.QUEUED
    claimed = _run(app, lambda db: _engine(app).claim_next(db, worker_id="w1"))
    assert claimed is not None


def test_approval_gate(app, user_a):
    task, _ = _create(app, user_a, requires_approval=True)
    row = _run(app, lambda db: _engine(app).get_owned(db, user_a, task.id))
    assert row.status == TaskState.WAITING_FOR_APPROVAL
    assert _run(app, lambda db: _engine(app).claim_next(db, worker_id="w1")) is None
    row = _run(app, lambda db: _engine(app).approve_task(db, user_a, task.id))
    assert row.status == TaskState.QUEUED
    claimed = _run(app, lambda db: _engine(app).claim_next(db, worker_id="w1"))
    assert claimed is not None
    outcome = _run(app, lambda db: _worker(app).execute_claimed(db, claimed))
    assert outcome == "completed"


def test_handler_requested_approval_releases_task(app, user_a):
    async def handler(ctx):
        if not ctx.done("ask"):
            await ctx.step("ask")
            await ctx.request_approval()
        await ctx.step("finish")
        return "ok"

    _worker(app).register("needs_ok", handler)
    task, _ = _create(app, user_a, kind="needs_ok")

    async def _go(db):
        claimed = await _engine(app).claim_next(db, worker_id="w1")
        return await _worker(app).execute_claimed(db, claimed)

    assert _run(app, _go) == "waiting_approval"
    row = _run(app, lambda db: _engine(app).get_owned(db, user_a, task.id))
    assert row.status == TaskState.WAITING_FOR_APPROVAL
    assert row.lease_owner is None, "approval must not leave the task leased"
    _run(app, lambda db: _engine(app).approve_task(db, user_a, task.id))
    claimed = _run(app, lambda db: _engine(app).claim_next(db, worker_id="w2"))
    outcome = _run(app, lambda db: _worker(app).execute_claimed(db, claimed))
    assert outcome == "completed"


# ---------------------------------------------------------------- events

def test_progress_events_seq_and_reconnection(app, user_a):
    store = {"sent": 0}
    _register_sideeffect(app, store)
    task, _ = _create(app, user_a, kind="sideeffect")

    async def _go(db):
        claimed = await _engine(app).claim_next(db, worker_id="w1")
        return await _worker(app).execute_claimed(db, claimed)

    _run(app, _go)
    events = _run(app, lambda db: _engine(app).events_after(db, user_a, task.id))
    seqs = [e.seq for e in events]
    assert seqs == sorted(seqs) and len(set(seqs)) == len(seqs)
    assert seqs[0] == 1
    types = [e.type for e in events]
    assert "task_queued" in types and "task_started" in types and "task_completed" in types

    tail = _run(app, lambda db: _engine(app).events_after(db, user_a, task.id, after_seq=seqs[-1]))
    assert tail == []
    mid = _run(app, lambda db: _engine(app).events_after(db, user_a, task.id, after_seq=seqs[0]))
    assert [e.seq for e in mid] == seqs[1:]


# ---------------------------------------------------------------- API

def test_api_create_list_detail_roundtrip(app, client, user_a, user_b):
    response = client.post(
        "/v1/tasks",
        json={"title": "nightly digest", "payload": {"a": 1}},
        headers=headers_for(user_a),
    )
    assert response.status_code == 201
    body = response.json()
    assert body["title"] == "nightly digest"
    assert body["status"] == TaskState.QUEUED

    mine = client.get("/v1/tasks", headers=headers_for(user_a)).json()
    assert [t["id"] for t in mine] == [body["id"]]
    theirs = client.get("/v1/tasks", headers=headers_for(user_b)).json()
    assert theirs == []

    detail = client.get(f"/v1/tasks/{body['id']}", headers=headers_for(user_a))
    assert detail.status_code == 200
    assert detail.json()["payload"] == {"a": 1}
    assert client.get(f"/v1/tasks/{body['id']}", headers=headers_for(user_b)).status_code == 404


def test_api_control_endpoints_and_cross_user_404(app, client, user_a, user_b):
    task, _ = _create(app, user_a, kind="noop")
    task_id = str(task.id)
    for path in ("cancel", "pause", "resume", "approve"):
        assert client.post(f"/v1/tasks/{task_id}/{path}", headers=headers_for(user_b)).status_code == 404
        assert client.get(f"/v1/tasks/{task_id}/events", headers=headers_for(user_b)).status_code == 404
    assert client.post(f"/v1/tasks/{task_id}/execute", headers=headers_for(user_b)).status_code == 404

    paused = client.post(f"/v1/tasks/{task_id}/pause", headers=headers_for(user_a))
    assert paused.status_code == 200 and paused.json()["status"] == "PAUSED"
    resumed = client.post(f"/v1/tasks/{task_id}/resume", headers=headers_for(user_a))
    assert resumed.status_code == 200 and resumed.json()["status"] == "QUEUED"
    approved = client.post(f"/v1/tasks/{task_id}/approve", headers=headers_for(user_a))
    assert approved.status_code == 409, "only WAITING_FOR_APPROVAL tasks can be approved"
    cancelled = client.post(f"/v1/tasks/{task_id}/cancel", headers=headers_for(user_a))
    assert cancelled.status_code == 200 and cancelled.json()["status"] == "CANCELLED"


def test_api_execute_inline_completes(app, client, user_a):
    task, _ = _create(app, user_a, kind="noop")
    response = client.post(f"/v1/tasks/{task.id}/execute", headers=headers_for(user_a))
    assert response.status_code == 200
    assert response.json()["status"] == "COMPLETED"
    again = client.post(f"/v1/tasks/{task.id}/execute", headers=headers_for(user_a))
    assert again.status_code == 409, "a completed task is not executable"


def test_api_events_and_sse_reconnection(app, client, user_a):
    store = {"sent": 0}
    _register_sideeffect(app, store)
    task, _ = _create(app, user_a, kind="sideeffect")

    async def _go(db):
        claimed = await _engine(app).claim_next(db, worker_id="w1")
        return await _worker(app).execute_claimed(db, claimed)

    _run(app, _go)
    events = client.get(f"/v1/tasks/{task.id}/events", headers=headers_for(user_a)).json()
    assert len(events) >= 3
    last = events[-1]["seq"]

    tail = client.get(
        f"/v1/tasks/{task.id}/events", params={"after_seq": last}, headers=headers_for(user_a)
    ).json()
    assert tail == []

    stream = client.get(
        f"/v1/tasks/{task.id}/events/stream",
        params={"max_seconds": 1},
        headers={**headers_for(user_a), "Last-Event-ID": str(last)},
    )
    assert stream.status_code == 200
    assert stream.headers["content-type"].startswith("text/event-stream")
    body = stream.text
    assert f"id: {last}" not in body, "reconnection must not resend delivered events"
    # A fresh connection (no Last-Event-ID) replays the whole history.
    full = client.get(
        f"/v1/tasks/{task.id}/events/stream", params={"max_seconds": 1}, headers=headers_for(user_a)
    ).text
    assert "id: 1" in full and "task_completed" in full


def test_worker_tick_endpoint(app, client, user_a):
    _create(app, user_a, kind="noop")
    # Fail closed: no token configured -> 403, never an open tick endpoint.
    blocked = client.post("/v1/tasks/worker/tick")
    assert blocked.status_code == 403

    app.state.settings.worker_token = "secret-token"
    try:
        response = client.post("/v1/tasks/worker/tick?ticks=1", headers={"X-Worker-Token": "secret-token"})
        assert response.status_code == 200
        summary = response.json()["ticks"][0]
        assert summary["claimed"] == 1
        wrong = client.post("/v1/tasks/worker/tick", headers={"X-Worker-Token": "nope"})
        assert wrong.status_code == 403
    finally:
        app.state.settings.worker_token = None


def test_account_deletion_covers_durable_tasks(app, client, user_a):
    from mina_api.database.models import DurableTask, TaskEvent

    _create(app, user_a, kind="noop")
    response = client.delete("/v1/account", headers=headers_for(user_a))
    assert response.status_code in {200, 204}

    async def _go(db):
        import sqlalchemy as sa

        tasks = len((await db.execute(sa.select(DurableTask).where(DurableTask.user_id == user_a))).scalars().all())
        events = len((await db.execute(sa.select(TaskEvent).where(TaskEvent.user_id == user_a))).scalars().all())
        return tasks, events

    tasks_left, events_left = _run(app, _go)
    assert tasks_left == 0 and events_left == 0
