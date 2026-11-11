"""Approval policy for agent runs.

Ask mode requires explicit approval before anything that spends money,
sends sensitive communications or destroys data. Autonomous mode
respects the user's configured permissions and spending limits and
NEVER bypasses mandatory safety confirmations: destructive steps and
Composio's own dangerous-tool gate still stop for approval.
"""

from __future__ import annotations

from uuid import UUID

from sqlalchemy import select

from mina_api.database.models import Approval

RISKY_KINDS = {"money", "sensitive", "destructive"}
MANDATORY_RISKS = {"destructive", "sensitive"}


def _risk_of(step_spec: dict) -> str:
    return step_spec.get("risk") or "safe"


def needs_approval(
    step_spec: dict, mode: str, spent_estimate_cents: int, spending_limit_cents: int | None = None
) -> tuple[bool, str]:
    """Returns (needs_approval, reason)."""
    risk = _risk_of(step_spec)
    cost = int(step_spec.get("cost_estimate_cents") or 0)
    if mode == "ask":
        if risk in RISKY_KINDS or cost > 0:
            return True, f"ask mode requires approval for {risk or 'spending'} steps"
        return False, ""
    # Autonomous mode.
    if risk in MANDATORY_RISKS:
        return True, "mandatory safety confirmation"  # never bypassed
    if cost > 0:
        if spending_limit_cents is None or spent_estimate_cents + cost > spending_limit_cents:
            return True, "spending limit missing or exceeded"
    return False, ""


async def create_approval(db, user_id: UUID, run_id: str, step_id: str, reason: str, summary: str) -> Approval:
    row = Approval(
        user_id=user_id,
        request={
            "kind": "agent_step",
            "run_id": run_id,
            "step_id": step_id,
            "reason": reason,
            "summary": summary,
        },
        status="pending",
    )
    db.add(row)
    await db.commit()
    return row


async def approval_decision(db, user_id: UUID, run_id: str, step_id: str) -> str | None:
    """The recorded decision for this step's approval, if any."""
    result = await db.execute(select(Approval).where(Approval.user_id == user_id))
    for row in result.scalars():
        request = row.request or {}
        if (
            request.get("kind") == "agent_step"
            and request.get("run_id") == run_id
            and request.get("step_id") == step_id
        ):
            return row.status
    return None


async def get_approval(db, user_id: UUID, approval_id: UUID) -> Approval | None:
    row = await db.get(Approval, approval_id)
    if row is None or row.user_id != user_id:
        return None
    return row


async def approved_for_step(db, user_id: UUID, run_id: str, step_id: str) -> bool:
    result = await db.execute(
        select(Approval).where(
            Approval.user_id == user_id,
            Approval.status == "approved",
        )
    )
    for row in result.scalars():
        request = row.request or {}
        if request.get("kind") == "agent_step" and request.get("run_id") == run_id and request.get("step_id") == step_id:
            return True
    return False
