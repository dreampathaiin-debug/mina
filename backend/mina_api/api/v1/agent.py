"""Phase 7: agent orchestrator API.

POST /v1/agent/runs starts a run and drives it synchronously until it
completes, fails or reaches a waiting checkpoint (approval, device
result, pause). Waiting runs persist their state and continue through
the resume/approval/device-result endpoints. Cancellation and pausing
are accepted for waiting runs immediately and honoured by in-flight
runs at their next checkpoint.
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Annotated, Any, Literal
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field

from mina_api.agent import approvals as approval_policy
from mina_api.agent.events import AgentEvent, AgentEventType
from mina_api.agent.orchestrator import Orchestrator
from mina_api.agent.state import RunStatus, StepStatus
from mina_api.database.models import AgentEvent as AgentEventRow
from mina_api.database.models import AgentRun, Conversation
from mina_api.security.auth import CurrentUser

router = APIRouter(prefix="/agent", tags=["agent"])


def _orchestrator(request: Request) -> Orchestrator:
    orchestrator = getattr(request.app.state, "agent_orchestrator", None)
    if orchestrator is None:
        raise HTTPException(503, detail="agent orchestrator unavailable")
    return orchestrator


class RunStartIn(BaseModel):
    input: str = Field(min_length=1, max_length=8000)
    conversation_id: UUID | None = None
    mode: Literal["ask", "autonomous"] = "ask"
    max_steps: int = Field(default=15, ge=1, le=40)
    timeout_seconds: float = Field(default=300.0, ge=5.0, le=3600.0)
    spending_limit_cents: int | None = Field(default=None, ge=0)


class ApprovalDecisionIn(BaseModel):
    decision: Literal["approved", "denied"]


class DeviceResultIn(BaseModel):
    step_id: str = Field(min_length=1, max_length=40)
    success: bool = True
    result: dict[str, Any] = Field(default_factory=dict)


def _run_summary(row: AgentRun, events: list[AgentEvent] | None = None) -> dict:
    out = {
        "run_id": str(row.id),
        "conversation_id": str(row.conversation_id) if row.conversation_id else None,
        "mode": row.mode,
        "status": row.status,
        "goal": row.goal,
        "final_response": row.final_response,
        "error": row.error,
        "steps": (row.payload or {}).get("steps", []),
        "created_at": str(row.created_at),
    }
    if events is not None:
        out["events"] = [e.model_dump(mode="json") for e in events]
    return out


async def _load_run(db, user_id: UUID, run_id: UUID) -> AgentRun:
    row = await db.get(AgentRun, run_id)
    if row is None or row.user_id != user_id:
        raise HTTPException(404, detail="run_not_found")
    return row


async def _persist(db, user_id: UUID, row: AgentRun, state, events: list[AgentEvent]) -> None:
    """Persist run state and events. Events are appended idempotently by seq."""
    seqs = {
        r[0]
        for r in (
            await db.execute(
                __import__("sqlalchemy").select(AgentEventRow.seq).where(AgentEventRow.run_id == row.id)
            )
        ).all()
    }
    for event in events:
        if event.seq in seqs:
            continue
        db.add(
            AgentEventRow(
                run_id=row.id, user_id=user_id, seq=event.seq,
                type=event.type.value, payload=event.payload,
            )
        )
    payload = state.to_dict() if state is not None else (row.payload or {})
    row.payload = payload
    row.goal = getattr(state, "goal", row.goal)
    row.error = getattr(state, "error", row.error)
    row.final_response = getattr(state, "final_response", row.final_response)
    row.status = state._status.value if state is not None and hasattr(state, "_status") else row.status
    row.updated_at = datetime.now(timezone.utc)
    await db.commit()
    await db.refresh(row)


@router.post("/runs")
async def start_run(
    body: RunStartIn,
    user: CurrentUser,
    request: Request,
    orchestrator: Annotated[Orchestrator, Depends(_orchestrator)],
) -> dict:
    db = request.app.state.database.session
    async with db() as session:
        if body.conversation_id is not None:
            conversation = await session.get(Conversation, body.conversation_id)
            if conversation is None or conversation.user_id != user.id:
                raise HTTPException(404, detail="conversation_not_found")
        row = AgentRun(
            user_id=user.id,
            conversation_id=body.conversation_id,
            mode=body.mode,
            status="running",
            input_text=body.input,
        )
        session.add(row)
        await session.commit()
        await session.refresh(row)

        state = await orchestrator.start_run(
            session, user.id, body.input,
            conversation_id=body.conversation_id,
            mode=body.mode,
            max_steps=body.max_steps,
            timeout_seconds=body.timeout_seconds,
            spending_limit_cents=body.spending_limit_cents,
            run_id=str(row.id),
        )
        # Persist the assistant turn so conversation context carries forward.
        if state._status == RunStatus.COMPLETED and state.final_response:
            if body.conversation_id is not None:
                from mina_api.database.models import Message

                session.add(Message(
                    conversation_id=body.conversation_id, user_id=user.id,
                    role="assistant", content=state.final_response[:8000],
                ))
                await session.commit()
        await _persist(session, user.id, row, state, state._events)
        return _run_summary(row, state._events)


@router.get("/runs")
async def list_runs(
    user: CurrentUser,
    request: Request,
    status: str | None = None,
    limit: int = 25,
) -> dict:
    import sqlalchemy as sa

    db = request.app.state.database.session
    async with db() as session:
        query = sa.select(AgentRun).where(AgentRun.user_id == user.id).order_by(AgentRun.created_at.desc()).limit(min(limit, 100))
        if status:
            query = query.where(AgentRun.status == status)
        rows = (await session.execute(query)).scalars().all()
        return {"runs": [_run_summary(r) for r in rows]}


@router.get("/runs/{run_id}")
async def get_run(run_id: UUID, user: CurrentUser, request: Request) -> dict:
    db = request.app.state.database.session
    async with db() as session:
        row = await _load_run(session, user.id, run_id)
        return _run_summary(row)


@router.get("/runs/{run_id}/events")
async def get_run_events(run_id: UUID, user: CurrentUser, request: Request) -> dict:
    import sqlalchemy as sa

    db = request.app.state.database.session
    async with db() as session:
        await _load_run(session, user.id, run_id)
        rows = (
            await session.execute(
                sa.select(AgentEventRow)
                .where(AgentEventRow.run_id == run_id, AgentEventRow.user_id == user.id)
                .order_by(AgentEventRow.seq)
            )
        ).scalars().all()
        return {
            "events": [
                {"seq": r.seq, "type": r.type, "timestamp": str(r.created_at), "payload": r.payload}
                for r in rows
            ]
        }


@router.post("/runs/{run_id}/cancel")
async def cancel_run(run_id: UUID, user: CurrentUser, request: Request) -> dict:
    db = request.app.state.database.session
    async with db() as session:
        row = await _load_run(session, user.id, run_id)
        if row.status in {RunStatus.COMPLETED.value, RunStatus.FAILED.value, RunStatus.CANCELLED.value}:
            raise HTTPException(409, detail="run_already_finished")
        row.status = RunStatus.CANCELLED.value
        payload = row.payload or {}
        for step in payload.get("steps", []):
            if step.get("status") in {StepStatus.PENDING.value, StepStatus.RUNNING.value,
                                      StepStatus.WAITING_APPROVAL.value, StepStatus.WAITING_DEVICE.value}:
                step["status"] = StepStatus.CANCELLED.value
        row.payload = payload
        await session.commit()
        request.app.state.agent_orchestrator.request_cancel(str(row.id))
        return {"run_id": str(row.id), "status": row.status}


@router.post("/runs/{run_id}/pause")
async def pause_run(run_id: UUID, user: CurrentUser, request: Request) -> dict:
    db = request.app.state.database.session
    async with db() as session:
        row = await _load_run(session, user.id, run_id)
        if row.status not in {RunStatus.RUNNING.value, RunStatus.WAITING_APPROVAL.value,
                              RunStatus.WAITING_DEVICE.value}:
            raise HTTPException(409, detail="run_not_pausable")
        row.status = RunStatus.PAUSED.value
        row.payload = {**(row.payload or {}), "paused": True}
        await session.commit()
        request.app.state.agent_orchestrator.request_pause(str(row.id))
        return {"run_id": str(row.id), "status": row.status}


@router.post("/runs/{run_id}/resume")
async def resume_run(run_id: UUID, user: CurrentUser, request: Request) -> dict:
    db = request.app.state.database.session
    async with db() as session:
        row = await _load_run(session, user.id, run_id)
        if row.status != RunStatus.PAUSED.value:
            raise HTTPException(409, detail="run_not_paused")
        state = _rebuild_state(row)
        orchestrator: Orchestrator = request.app.state.agent_orchestrator
        orchestrator.clear_control(str(row.id))
        state = await orchestrator.continue_run(session, user.id, state)
        await _persist(session, user.id, row, state, state._events)
        return _run_summary(row, state._events)


@router.post("/runs/{run_id}/approvals/{approval_id}")
async def decide_approval(
    run_id: UUID, approval_id: UUID, body: ApprovalDecisionIn,
    user: CurrentUser, request: Request,
) -> dict:
    db = request.app.state.database.session
    async with db() as session:
        row = await _load_run(session, user.id, run_id)
        approval = await approval_policy.get_approval(session, user.id, approval_id)
        if approval is None or (approval.request or {}).get("run_id") != str(run_id):
            raise HTTPException(404, detail="approval_not_found")
        if approval.status != "pending":
            raise HTTPException(409, detail="approval_already_decided")
        approval.status = body.decision
        await session.commit()
        if body.decision == "approved":
            state = _rebuild_state(row)
            orchestrator: Orchestrator = request.app.state.agent_orchestrator
            state = await orchestrator.continue_run(session, user.id, state)
            await _persist(session, user.id, row, state, state._events)
            return _run_summary(row, state._events)
        # Denied: mark the step failed and finish the run.
        payload = row.payload or {}
        step_id = (approval.request or {}).get("step_id")
        for step in payload.get("steps", []):
            if step.get("id") == step_id:
                step["status"] = StepStatus.FAILED.value
                step["error"] = "approval_denied"
        row.payload = payload
        row.status = RunStatus.FAILED.value
        row.error = f"approval_denied:{step_id}"
        await session.commit()
        return {"run_id": str(row.id), "status": row.status, "error": row.error}


@router.post("/runs/{run_id}/device-results")
async def submit_device_result(
    run_id: UUID, body: DeviceResultIn, user: CurrentUser, request: Request,
) -> dict:
    db = request.app.state.database.session
    async with db() as session:
        row = await _load_run(session, user.id, run_id)
        if row.status != RunStatus.WAITING_DEVICE.value:
            raise HTTPException(409, detail="run_not_awaiting_device")
        payload = row.payload or {}
        step = next((s for s in payload.get("steps", []) if s.get("id") == body.step_id), None)
        if step is None or step.get("status") != StepStatus.WAITING_DEVICE.value:
            raise HTTPException(404, detail="step_not_awaiting_device")
        state = _rebuild_state(row)
        orchestrator: Orchestrator = request.app.state.agent_orchestrator
        result = dict(body.result)
        if not body.success:
            result = {"_failed": True, "kind": "device_action_failed", "detail": result}
        state = await orchestrator.continue_run(
            session, user.id, state, device_results={body.step_id: result}
        )
        await _persist(session, user.id, row, state, state._events)
        return _run_summary(row, state._events)


def _rebuild_state(row: AgentRun):
    from mina_api.agent.state import RunState

    payload = row.payload or {}
    state = RunState.from_dict({**payload, "run_id": str(row.id)})
    state._events = []  # type: ignore[attr-defined]
    state._input = row.input_text  # type: ignore[attr-defined]
    status_map = {
        "running": RunStatus.RUNNING, "waiting_approval": RunStatus.WAITING_APPROVAL,
        "waiting_device": RunStatus.WAITING_DEVICE, "paused": RunStatus.PAUSED,
        "cancelled": RunStatus.CANCELLED, "completed": RunStatus.COMPLETED,
        "failed": RunStatus.FAILED,
    }
    state._status = status_map.get(row.status, RunStatus.RUNNING)  # type: ignore[attr-defined]
    state.final_response = row.final_response
    return state
